package ai.atalia.chat

import ai.atalia.chat.internal.AtaliaCore
import ai.atalia.chat.internal.AtaliaLog
import ai.atalia.chat.internal.BridgeMessage
import ai.atalia.chat.internal.ChatUrl
import ai.atalia.chat.internal.NoticeTarget
import ai.atalia.chat.internal.OpenChatRequest
import ai.atalia.chat.internal.PopupChange
import ai.atalia.chat.internal.WebBridge
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Camada dos balões (protocol/README.md): um WebView transparente por cima das telas do app, acima
 * do botão flutuante, que desenha os balões do widget com o chat fechado (aviso das regras por
 * página, mensagem nova e boas-vindas). Criada e destruída pelo [AtaliaLauncherView].
 *
 * Fica na hierarquia desde o começo, com alfa 0 e sem aceitar toque, porque a página só roda num
 * WebView que está na tela; aparece quando a página avisa `popup_changed`. Das mensagens dela só
 * valem `popup_changed`, `open_chat`, `open_url` e `ready`: quem fala pela sessão e pelos eventos
 * públicos é a tela do chat.
 */
@SuppressLint("ViewConstructor")
internal class AtaliaPopupLayer private constructor(
    private val launcher: AtaliaLauncherView,
) : FrameLayout(launcher.context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var webView: WebView? = null
    private var destroyed = false

    /** Canal, origem e sessão com que a página foi carregada. Sessão trocada = página recarregada. */
    private var loadedFor: Triple<String, String, String?>? = null
    private var failed = false
    private var ready = false

    /** O que a página já sabe: o estado com que nasceu (ou o último enviado) e a tela do fragmento. */
    private var sentState = APP_FOREGROUND
    private var sentPage: String? = null

    private var chatOpen = Atalia.isChatOpen.value
    private var appStarted = false

    /** A página quer o balão na tela, e com que altura. */
    private var wanted = false
    private var wantedHeight = 0
    private var shown = false

    private val appObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appStarted = true
            webView?.onResume()
            sync()
            syncAppState()
        }

        override fun onStop(owner: LifecycleOwner) {
            appStarted = false
            hide(immediate = true)
            syncAppState()
            webView?.onPause()
        }
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        // O botão mudou de lugar, de lado ou sumiu (arrasto, borda, opções, barras do sistema).
        refresh()
        true
    }

    init {
        alpha = 0f
        setHidden(true)
    }

    private fun start() {
        appStarted = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appObserver)
        scope.launch {
            Atalia.isChatOpen.collect { open ->
                chatOpen = open
                // Chat na tela: o balão some na hora e a página fecha a conexão. Na volta ela
                // recarrega a sessão e pega o que mudou enquanto o chat estava aberto.
                if (open) hide(immediate = true) else sync()
                syncAppState()
            }
        }
        // Sessão trocada (identify, logout, session_changed da tela do chat): página nova.
        scope.launch { Atalia.sessionTokenFlow.collect { sync() } }
        // O configure pode chegar depois do botão: a aparência do canal só existe depois dele.
        scope.launch { Atalia.brandingFlow.collect { sync() } }
        scope.launch { Atalia.pageFlow.collect { syncPage() } }
    }

    /** Desliga tudo e destrói o WebView. A view sai da hierarquia por quem chamou. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        scope.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appObserver)
        animate().cancel()
        destroyWebView()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    // Página

    /** Carrega (ou recarrega) quando o canal ou a sessão não são mais os da página. */
    private fun sync() {
        if (destroyed) return
        val core = Atalia.coreOrNull() ?: return
        // Com o chat na tela ou o app fora dela a página estaria parada de qualquer jeito: espera a volta.
        if (chatOpen || !appStarted) return
        val key = Triple(core.channelToken, core.options.baseUrl, Atalia.sessionTokenFlow.value)
        if (webView != null && !failed && key == loadedFor) return
        load(core, key)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(core: AtaliaCore, key: Triple<String, String, String?>) {
        destroyWebView()
        hide(immediate = true)
        ready = false
        failed = false
        loadedFor = key

        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // O som do balão de mensagem nova toca sem o visitante ter tocado na página.
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.webViewClient = client
        WebBridge.install(webView, key.second, currentUrl = { this.webView?.url }, receive = ::receive)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        this.webView = webView

        sentState = APP_FOREGROUND
        sentPage = core.page
        val fragment = ChatUrl.popupFragment(WebBridge.appVersion(context), key.third, sentPage)
        AtaliaLog.i("balões: carregando a camada dos balões")
        webView.loadUrl(ChatUrl.build(key.second, key.first, core.options.customDomain, fragment, popup = true))
    }

    private fun destroyWebView() {
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        ready = false
    }

    private fun isTrusted(url: String?): Boolean = loadedFor?.let { WebBridge.isTrusted(url, it.second) } ?: false

    // Bridge

    private fun receive(raw: String?) {
        if (destroyed) return
        val message = BridgeMessage.parse(raw) ?: return
        when (message.type) {
            "ready" -> {
                ready = true
                syncAppState()
                syncPage()
            }
            "popup_changed" -> message.toPopupChange()?.let(::popupChanged)
            "open_chat" -> message.toOpenChat()?.let(::openChat)
            "open_url" -> message.string("url")?.let { WebBridge.openExternal(context, it, Atalia.coreOrNull()?.options?.urlHandler) }
            // session_changed, unread_changed, error e o resto: ignorados de propósito. Nada desta
            // página chega ao núcleo nem vira evento público.
        }
    }

    private fun send(type: String, payload: JSONObject?) {
        val webView = webView ?: return
        if (ready) WebBridge.send(webView, type, payload)
    }

    /**
     * `background` com o chat na tela ou o app fora dela; `foreground` na volta. Antes do `ready`
     * nada é mandado: vale o último estado, e só se for diferente do que a página já sabe.
     */
    private fun syncAppState() {
        if (!ready || webView == null) return
        val state = if (appStarted && !chatOpen) APP_FOREGROUND else APP_BACKGROUND
        if (state == sentState) return
        sentState = state
        send("app_state", JSONObject().put("state", state))
    }

    private fun syncPage() {
        if (!ready || webView == null) return
        val page = Atalia.pageFlow.value
        if (page == sentPage) return
        sentPage = page
        send("set_page", JSONObject().put("url", page ?: JSONObject.NULL))
    }

    private fun popupChanged(change: PopupChange) {
        if (change.visible) {
            change.height?.takeIf { it > 0 }?.let { wantedHeight = dp(it) }
            wanted = true
            AtaliaLog.i("balões: balão na tela (${change.kind ?: "sem tipo"})")
        } else {
            wanted = false
        }
        refresh()
    }

    private fun openChat(request: OpenChatRequest) {
        hide(immediate = true)
        val context = launcher.context
        runCatching {
            when {
                request.conversationId != null -> Atalia.openConversation(context, request.conversationId)
                request.noticeRuleId != null -> Atalia.openNotice(context, NoticeTarget(request.noticeRuleId, request.noticeButton))
                else -> Atalia.open(context)
            }
        }.onFailure { AtaliaLog.w("balões: não foi possível abrir o chat", it) }
    }

    // Lugar, tamanho e visibilidade

    /** Confere o lugar e o tamanho contra o botão e mostra ou esconde o balão. */
    private fun refresh() {
        if (destroyed) return
        place()
        val show = wanted && !chatOpen && appStarted && webView != null && launcher.visibility == VISIBLE
        if (show == shown) return
        shown = show
        setHidden(!show)
        animate().cancel()
        animate().alpha(if (show) 1f else 0f).setDuration(FADE_MS).start()
    }

    private fun hide(immediate: Boolean) {
        wanted = false
        if (!immediate) return refresh()
        shown = false
        setHidden(true)
        animate().cancel()
        alpha = 0f
    }

    /** Escondido, o balão não existe para o toque, o foco nem o leitor de tela. */
    private fun setHidden(hidden: Boolean) {
        importantForAccessibility = if (hidden) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        descendantFocusability = if (hidden) FOCUS_BLOCK_DESCENDANTS else FOCUS_AFTER_DESCENDANTS
    }

    /**
     * Largura do SDK (314dp, ou a do app menos 16dp), altura da página. A borda de baixo fica 4dp
     * acima do círculo do botão: a página já tem 12px de margem transparente, o que dá os mesmos
     * 16px do site. Na lateral, a borda do WebView coincide com a do botão (as duas margens
     * transparentes são de 12), ou seja, a margem lateral do botão menos 12dp.
     */
    private fun place() {
        val host = parent as? ViewGroup ?: return
        val lp = layoutParams as? LayoutParams ?: return
        if (host.width == 0 || launcher.height == 0) return

        launcher.getLocationInWindow(launcherAt)
        host.getLocationInWindow(hostAt)
        // Posição de descanso na horizontal: a aba da borda e o elástico deslocam o botão, não o balão.
        val launcherLeft = launcherAt[0] - hostAt[0] - launcher.translationX
        val buttonTop = launcherAt[1] - hostAt[1] + (launcher.height - launcher.buttonSize) / 2
        val bottom = buttonTop - dp(GAP_DP)
        val insets = ViewCompat.getRootWindowInsets(host)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val topLimit = ((insets?.top ?: 0) - hostAt[1]).coerceAtLeast(0)

        val width = min(dp(WIDTH_DP), host.width - dp(SCREEN_MARGIN_DP)).coerceAtLeast(1)
        val height = min(if (wantedHeight > 0) wantedHeight else dp(PROVISIONAL_HEIGHT_DP), bottom - topLimit).coerceAtLeast(1)
        if (lp.width != width || lp.height != height) {
            lp.width = width
            lp.height = height
            layoutParams = lp
        }

        val rtl = launcher.layoutDirection == LAYOUT_DIRECTION_RTL
        val onLeft = (launcher.currentSide == AtaliaLauncherOptions.Side.START) != rtl
        val left = if (onLeft) launcherLeft else launcherLeft + launcher.width - width
        val targetX = left.coerceIn(0f, (host.width - width).coerceAtLeast(0).toFloat())
        val targetY = (bottom - height).toFloat()
        if (abs(x - targetX) > 0.5f) x = targetX
        if (abs(y - targetY) > 0.5f) y = targetY
    }

    private val launcherAt = IntArray(2)
    private val hostAt = IntArray(2)

    /** Só recebe toque com o balão na tela, e só no balão: a margem transparente deixa o toque passar para o app. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!shown) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val inset = dp(PAGE_INSET_DP)
            if (event.x < inset || event.y < inset || event.x > width - inset || event.y > height - inset) return false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame && isTrusted(request.url.toString())) return false
            // Qualquer outro destino sai do WebView, como na tela do chat.
            WebBridge.openExternal(context, request.url.toString(), Atalia.coreOrNull()?.options?.urlHandler)
            return true
        }

        /** Sem rede: sem balões por enquanto. Tenta de novo quando o app voltar ou a sessão mudar. */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || view !== webView) return
            AtaliaLog.w("balões: a camada dos balões não carregou")
            failed = true
            hide(immediate = true)
        }

        /** O sistema matou o processo da página (memória). Sem tratar, o app inteiro cairia. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                failed = true
                hide(immediate = true)
                destroyWebView()
            }
            return true
        }
    }

    companion object {
        private const val WIDTH_DP = 314
        private const val SCREEN_MARGIN_DP = 16
        private const val PROVISIONAL_HEIGHT_DP = 320
        private const val GAP_DP = 4
        private const val PAGE_INSET_DP = 12
        private const val FADE_MS = 180L
        private const val APP_FOREGROUND = "foreground"
        private const val APP_BACKGROUND = "background"

        /**
         * Põe a camada por cima do conteúdo da tela em que o botão está. Devolve null quando o
         * botão não está numa activity (sem `android.R.id.content` não há onde sobrepor).
         */
        // Gravidade fixa na esquerda de propósito: o lugar é dado em x/y absolutos, já com o lado do botão.
        @SuppressLint("RtlHardcoded")
        fun attach(launcher: AtaliaLauncherView): AtaliaPopupLayer? {
            val host = launcher.rootView?.findViewById<View>(android.R.id.content) as? FrameLayout ?: return null
            val layer = AtaliaPopupLayer(launcher)
            val density = launcher.resources.displayMetrics.density
            host.addView(
                layer,
                LayoutParams((WIDTH_DP * density).roundToInt(), (PROVISIONAL_HEIGHT_DP * density).roundToInt(), Gravity.TOP or Gravity.LEFT),
            )
            layer.start()
            return layer
        }
    }
}
