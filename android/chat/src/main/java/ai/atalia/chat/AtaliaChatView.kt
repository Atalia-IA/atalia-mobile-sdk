package ai.atalia.chat

import ai.atalia.chat.internal.AtaliaCore
import ai.atalia.chat.internal.BridgeMessage
import ai.atalia.chat.internal.ChatBridgeTarget
import ai.atalia.chat.internal.ChatUrl
import ai.atalia.chat.internal.LegacyBridge
import ai.atalia.chat.internal.SdkFragment
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * O chat da Atalia num WebView, ligado ao app pelo bridge. Use [Atalia.open] para a tela pronta;
 * embuta esta view (numa aba, por exemplo) implementando [Host].
 */
class AtaliaChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** O que a view pede a quem a hospeda: fechar, escolher arquivo e pedir permissão. */
    interface Host {
        fun onCloseRequested()

        /** Devolva `false` se não puder abrir o seletor (o anexo é cancelado). */
        fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean

        fun requestPermission(permission: String, onResult: (Boolean) -> Unit)

        /** A cor do canal chegou com a tela já aberta (primeira abertura, ou o canal mudou de cor). */
        fun onPrimaryColorChanged(color: String) {}
    }

    var host: Host? = null

    private var webView: WebView? = null
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private var errorView: View? = null
    private var ready = false
    private val pendingCommands = mutableListOf<Pair<String, JSONObject?>>()
    private var currentConversationId: Long? = null
    /** Estado com que a página nasceu e o último pedido antes do `ready` (só vale o último). */
    private var bornState = APP_FOREGROUND
    private var pendingAppState: String? = null

    /** Canal, origem e sessão com que a página foi carregada: o chat pré-carregado só serve a eles. */
    private var loadedFor: Triple<String, String, String?>? = null

    /**
     * Fundo do painel do widget (`background_changed`: branco na home, o da conversa nela). A tela do
     * chat pinta com ele a faixa abaixo do chat. Guardado porque o pré-carregado recebe escondido.
     */
    internal var pageBackground: Int? = lastPageBackground
        private set
    internal var onPageBackgroundChanged: ((Int) -> Unit)? = null

    private val bridgeTarget = object : ChatBridgeTarget {
        override fun sendCommand(type: String, payload: JSONObject?) = this@AtaliaChatView.sendCommand(type, payload)

        override fun primaryColorChanged(color: String) {
            host?.onPrimaryColorChanged(color)
        }
    }

    init {
        setBackgroundColor(Color.WHITE)
        addView(progress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private val core: AtaliaCore? get() = Atalia.coreOrNull()

    /** Carrega o chat; com [conversationId], direto nessa conversa. */
    fun load(conversationId: Long? = null) = load(conversationId, background = false)

    /**
     * [background]: chat pré-carregado escondido. A página nasce em segundo plano (sem socket, sem
     * presença, sem marcar lidas) e só vira o chat da tela no [activate].
     */
    @SuppressLint("SetJavaScriptEnabled")
    internal fun load(conversationId: Long?, background: Boolean) {
        val core = core ?: return
        currentConversationId = conversationId
        destroyWebView()
        hideError()
        ready = false
        bornState = if (background) APP_BACKGROUND else APP_FOREGROUND
        pendingAppState = null
        progress.visibility = VISIBLE

        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Sem janelas novas: window.open e target=_blank navegam aqui e o WebViewClient manda para fora.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        WebView.setWebContentsDebuggingEnabled(core.options.debug)
        webView.webViewClient = client
        webView.webChromeClient = chromeClient
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            download(url, URLUtil.guessFileName(url, contentDisposition, mimeType), mimeType)
        }
        installBridge(webView, core)
        addView(webView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        this.webView = webView
        if (!background) core.activeChat = bridgeTarget
        loadedFor = Triple(core.channelToken, core.options.baseUrl, core.storedSessionToken)

        val fragment = SdkFragment(
            appVersion = appVersion(),
            session = core.storedSessionToken,
            conversationId = conversationId,
            closeButton = core.options.closeButton,
            background = background,
        )
        webView.loadUrl(ChatUrl.build(core.options.baseUrl, core.channelToken, core.options.customDomain, fragment))
    }

    /** Troca de conversa com o chat já aberto (toque numa notificação). */
    fun openConversation(conversationId: Long) {
        currentConversationId = conversationId
        sendCommand("open_conversation", JSONObject().put("conversationId", conversationId))
    }

    /**
     * Chame no `onStart`/`onStop` de quem hospeda. Em segundo plano o widget fecha o WebSocket e
     * avisa a Atalia que o visitante saiu; é o que libera as notificações push.
     */
    fun onAppForeground() {
        webView?.onResume()
        sendCommand("app_state", JSONObject().put("state", APP_FOREGROUND))
    }

    fun onAppBackground() {
        sendCommand("app_state", JSONObject().put("state", APP_BACKGROUND))
        webView?.onPause()
    }

    /** Apareceu na tela: vira o chat do app (identify passa por ele, notificação não aparece) e reconecta. */
    internal fun activate() {
        core?.activeChat = bridgeTarget
        onAppForeground()
    }

    /** Saiu da tela, mas fica carregado: volta a esperar escondido, sem socket. */
    internal fun deactivate() {
        val core = core
        if (core?.activeChat === bridgeTarget) core.activeChat = null
        sendCommand("app_state", JSONObject().put("state", APP_BACKGROUND))
    }

    /** Carregou sem erro, para o mesmo canal e a mesma sessão de agora. */
    internal fun isReusable(core: AtaliaCore): Boolean =
        webView != null && errorView == null && loadedFor == Triple(core.channelToken, core.options.baseUrl, core.storedSessionToken)

    /** Chame no `onDestroy` de quem hospeda. */
    fun destroy() {
        val core = core
        if (core?.activeChat === bridgeTarget) core.activeChat = null
        destroyWebView()
    }

    // Bridge

    private fun installBridge(webView: WebView, core: AtaliaCore) {
        val origin = ChatUrl.origin(core.options.baseUrl)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // O objeto só existe nas páginas da origem da Atalia, e só o frame principal fala.
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) receive(message.data)
            }
        } else {
            @Suppress("JavascriptInterface")
            webView.addJavascriptInterface(
                LegacyBridge { json -> if (isTrusted(this.webView?.url)) receive(json) },
                BRIDGE_NAME,
            )
        }
    }

    private fun receive(raw: String?) {
        val message = BridgeMessage.parse(raw) ?: return
        when (message.type) {
            "ready" -> {
                ready = true
                progress.visibility = GONE
                flushPendingCommands()
            }
            "error" -> if (message.string("command") == null) progress.visibility = GONE
            // A sessão nova (criada ou trocada pelo widget) passa a ser a desta página.
            "session_changed" -> message.string("sessionToken")?.let { token -> loadedFor = loadedFor?.copy(third = token) }
            "close" -> host?.onCloseRequested()
            "background_changed" -> message.string("color")
                ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                ?.let { color ->
                    pageBackground = color
                    lastPageBackground = color
                    onPageBackgroundChanged?.invoke(color)
                }
            "open_url" -> message.string("url")?.let(::openExternal)
            "download" -> message.string("url")?.let { download(it, message.string("filename"), message.string("mimeType")) }
        }
        core?.handle(message)
    }

    private fun sendCommand(type: String, payload: JSONObject?) {
        val webView = webView
        if (!ready || webView == null) {
            // Do estado do app, só o último pedido importa, e só se for diferente do que a página nasceu.
            if (type == "app_state") pendingAppState = payload?.optString("state") else pendingCommands += type to payload
            return
        }
        val command = JSONObject().put("type", type).apply { payload?.let { put("payload", it) } }
        webView.evaluateJavascript("window.AtaliaChat&&window.AtaliaChat._native&&window.AtaliaChat._native($command);", null)
    }

    private fun flushPendingCommands() {
        pendingAppState?.takeIf { it != bornState }?.let { sendCommand("app_state", JSONObject().put("state", it)) }
        pendingAppState = null
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { (type, payload) -> sendCommand(type, payload) }
    }

    private fun isTrusted(url: String?): Boolean {
        val core = core ?: return false
        return url != null && ChatUrl.origin(url).equals(ChatUrl.origin(core.options.baseUrl), ignoreCase = true)
    }

    // Links e arquivos

    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (core?.options?.urlHandler?.invoke(context, uri) == true) return
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.atalia_chat_no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * DownloadManager grava em Downloads com o nome certo e avisa na barra de notificações.
     * Antes do Android 10 isso exige permissão de armazenamento: sem ela, o navegador baixa.
     */
    private fun download(url: String, filename: String?, mimeType: String?) {
        val name = filename?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(url, null, mimeType)
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name.replace(Regex("[/\\\\:]"), "_"))
            mimeType?.takeIf { it.isNotBlank() }?.let { request.setMimeType(it) }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, R.string.atalia_chat_download_started, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            openExternal(url)
        }
    }

    // Erro de carregamento

    private fun showError() {
        progress.visibility = GONE
        if (errorView != null) return
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
            addView(TextView(context).apply {
                setText(R.string.atalia_chat_load_error)
                gravity = Gravity.CENTER
                textSize = 16f
            })
            addView(Button(context).apply {
                setText(R.string.atalia_chat_retry)
                setOnClickListener { load(currentConversationId) }
            })
            addView(Button(context).apply {
                setText(R.string.atalia_chat_close)
                setOnClickListener { host?.onCloseRequested() }
            })
        }
        addView(layout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        errorView = layout
    }

    private fun hideError() {
        errorView?.let(::removeView)
        errorView = null
    }

    private fun destroyWebView() {
        webView?.apply {
            stopLoading()
            webChromeClient = null
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
    }

    private fun appVersion(): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame && isTrusted(request.url.toString())) return false
            // Qualquer outro destino sai do WebView: o chat nunca é trocado por outro site.
            openExternal(request.url.toString())
            return true
        }

        /**
         * A página carregou, mas o chat ainda busca a configuração e a conversa: o spinner fica até
         * o `ready` do widget. O prazo só cobre um widget que nunca avise (o erro dele já tem tela própria).
         */
        override fun onPageFinished(view: WebView, url: String?) {
            postDelayed({ if (!ready) progress.visibility = GONE }, READY_FALLBACK_MS)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError()
        }

        /** O sistema matou o processo da página (memória). Sem tratar, o app inteiro cairia. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                destroyWebView()
                showError()
            }
            return true
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = host?.showFileChooser(filePathCallback, fileChooserParams) ?: false

        /** Nota de voz. Só o microfone, e só para a página da Atalia. */
        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (!wantsAudio || !isTrusted(request.origin.toString())) {
                request.deny()
                return
            }
            val host = host ?: return request.deny()
            host.requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
            }
        }
    }

    private companion object {
        const val BRIDGE_NAME = "AtaliaAndroid"
        const val READY_FALLBACK_MS = 8_000L
        const val APP_FOREGROUND = "foreground"
        const val APP_BACKGROUND = "background"

        /** Último fundo avisado pelo widget: a próxima tela já nasce com ele. */
        var lastPageBackground: Int? = null
    }
}
