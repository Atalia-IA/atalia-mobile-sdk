package ai.atalia.chat

import ai.atalia.chat.internal.AtaliaLog
import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import java.io.File
import kotlin.math.roundToInt

/**
 * Tela do chat aberta por [Atalia.open]. Por padrão é uma folha que sobe sobre o app (fundo
 * escurecido, cantos arredondados, alça para arrastar e fechar); com
 * [AtaliaOptions.Presentation.FULL_SCREEN], ocupa a tela inteira.
 */
class AtaliaChatActivity : ComponentActivity(), AtaliaChatView.Host {

    companion object {
        internal const val EXTRA_CONVERSATION_ID = "ai.atalia.chat.CONVERSATION_ID"
        private const val DEFAULT_PRIMARY = "#1036F2"
        private const val SCRIM_ALPHA = 0.45f
        private const val HANDLE_DP = 22
        private const val CORNER_DP = 20
        private const val SHEET_GAP_DP = 10
    }

    private lateinit var chatView: AtaliaChatView
    private var sheet = true
    private var panel: View? = null
    private var scrim: View? = null
    private var topBar: View? = null
    private var closing = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutput: Uri? = null
    private var permissionResult: ((Boolean) -> Unit)? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?: cameraOutput?.takeIf { result.resultCode == RESULT_OK }?.let { arrayOf(it) }
        cameraOutput = null
        deliverFiles(picked)
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionResult?.invoke(granted)
        permissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = Atalia.coreOrNull() ?: run {
            // Volta do fundo depois que o sistema matou o processo: sem configure no Application.onCreate
            // o SDK não existe aqui e o chat fecha. O log não aparece nesse caso (configure liga o log),
            // por isso o aviso vai direto pelo Log.
            android.util.Log.w(AtaliaLog.TAG, "chat fechado: Atalia.configure ainda não foi chamado. Chame no Application.onCreate, não numa Activity")
            finish()
            return
        }
        sheet = core.options.presentation == AtaliaOptions.Presentation.SHEET

        // Bordas a bordas: barras transparentes e o teclado encolhe o chat em vez de cobrir o campo.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        // O chat pré-carregado pelo botão flutuante, se ainda serve; senão, um novo. Os dois num
        // MutableContextWrapper, para o chat poder voltar a esperar escondido quando esta tela fechar.
        val conversationId = conversationIdFrom(intent)
        chatView = ChatPrewarm.take(this)?.also { prepared ->
            prepared.host = this
            conversationId?.let(prepared::openConversation)
            prepared.activate()
        } ?: AtaliaChatView(MutableContextWrapper(this)).also {
            it.host = this
            it.load(conversationId)
        }

        val primary = parsePrimary(core.primaryColor)
        val topBar = FrameLayout(this).apply { setBackgroundColor(primary) }
        this.topBar = topBar
        if (sheet) topBar.addView(grabber(), FrameLayout.LayoutParams(dp(36), dp(5), Gravity.CENTER))
        // Faixa abaixo do chat (barra de navegação e teclado): o fundo do widget, que muda com a tela.
        val bottomInset = View(this).apply { setBackgroundColor(chatView.pageBackground ?: Color.WHITE) }
        chatView.onPageBackgroundChanged = { color -> bottomInset.setBackgroundColor(color) }
        (chatView.parent as? ViewGroup)?.removeView(chatView)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                if (sheet) cornerRadii = FloatArray(8) { if (it < 4) dp(CORNER_DP).toFloat() else 0f }
            }
            clipToOutline = true
            addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            addView(chatView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomInset, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        }
        this.panel = panel
        val scrim = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            if (sheet) setOnClickListener { dismiss() }
        }
        this.scrim = scrim
        val root = FrameLayout(this).apply {
            addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Folha: começa logo abaixo da barra de status, com a alça. Tela cheia: a cor do canal
            // passa por trás da barra de status.
            panel.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = if (sheet) bars.top + dp(SHEET_GAP_DP) else 0 }
            topBar.updateLayoutParams { height = if (sheet) dp(HANDLE_DP) else bars.top }
            bottomInset.updateLayoutParams { height = maxOf(bars.bottom, ime.bottom) }
            panel.updatePadding(left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        if (sheet) enableHandleDrag(topBar)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        })
        if (savedInstanceState == null) panel.doOnPreDraw { animateIn() }
        core.emit(AtaliaEvent.Opened)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        conversationIdFrom(intent)?.let { chatView.openConversation(it) }
    }

    override fun onStart() {
        super.onStart()
        if (::chatView.isInitialized) chatView.onAppForeground()
    }

    override fun onStop() {
        if (::chatView.isInitialized) chatView.onAppBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (::chatView.isInitialized) {
            deliverFiles(null)
            // O chat pode voltar a esperar escondido: não pode segurar esta tela pelo callback.
            chatView.onPageBackgroundChanged = null
            // Com botão flutuante na tela, o chat volta a esperar escondido; senão, é destruído.
            if (!ChatPrewarm.recycle(chatView, applicationContext)) chatView.destroy()
            if (isFinishing) Atalia.coreOrNull()?.emit(AtaliaEvent.Closed)
        }
        super.onDestroy()
    }

    private fun conversationIdFrom(intent: Intent?): Long? =
        intent?.takeIf { it.hasExtra(EXTRA_CONVERSATION_ID) }?.getLongExtra(EXTRA_CONVERSATION_ID, 0L)?.takeIf { it > 0 }

    // Entrada, saída e arrasto da folha

    private fun animateIn() {
        val panel = panel ?: return
        panel.translationY = panel.height.toFloat()
        panel.animate().translationY(0f).setDuration(420).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
        scrim?.animate()?.alpha(if (sheet) SCRIM_ALPHA else 0f)?.setDuration(260)?.start()
    }

    /** Fecha com a folha descendo; a activity só termina no fim da animação. */
    private fun dismiss() {
        if (closing) return
        closing = true
        val panel = panel ?: return finishWithoutAnimation()
        panel.animate().translationY(panel.height.toFloat()).setDuration(240).setInterpolator(AccelerateInterpolator(1.4f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishWithoutAnimation()
            }).start()
        scrim?.animate()?.alpha(0f)?.setDuration(240)?.start()
    }

    private fun finishWithoutAnimation() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Arrastar a alça para baixo leva a folha junto; solta longe ou rápido, fecha; senão, volta. */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableHandleDrag(handle: View) {
        var startY = 0f
        var tracker: VelocityTracker? = null
        handle.setOnTouchListener { _, event ->
            val panel = panel ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    panel.animate().cancel()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dy = (event.rawY - startY).coerceAtLeast(0f)
                    panel.translationY = dy
                    scrim?.alpha = SCRIM_ALPHA * (1f - dy / panel.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocity = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    if (panel.translationY > panel.height * 0.25f || velocity > dp(1100)) {
                        dismiss()
                    } else {
                        panel.animate().translationY(0f).setDuration(260).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
                        scrim?.animate()?.alpha(SCRIM_ALPHA)?.setDuration(200)?.start()
                    }
                }
            }
            true
        }
    }

    private fun grabber() = View(this).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(150, 255, 255, 255))
            cornerRadius = dp(3).toFloat()
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    // AtaliaChatView.Host

    override fun onCloseRequested() = dismiss()

    override fun onPrimaryColorChanged(color: String) {
        topBar?.setBackgroundColor(parsePrimary(color))
    }

    private fun parsePrimary(color: String?): Int =
        runCatching { Color.parseColor(color ?: DEFAULT_PRIMARY) }.getOrDefault(Color.parseColor(DEFAULT_PRIMARY))

    override fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        deliverFiles(null)
        fileCallback = callback
        val wantsImages = params.acceptTypes.isEmpty() || params.acceptTypes.any { it.isBlank() || it.startsWith("image/") || it == "*/*" }

        // `capture` no input (botão "Câmera" do chat): vai direto para a câmera.
        if (params.isCaptureEnabled && wantsImages) {
            withCamera(askIfNeeded = true) { allowed ->
                val camera = if (allowed) cameraIntent() else null
                if (camera != null) launchChooser(camera) else deliverFiles(null)
            }
            return true
        }

        val content = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        withCamera(askIfNeeded = false) { allowed ->
            val camera = if (allowed && wantsImages) cameraIntent() else null
            val chooser = Intent.createChooser(content, null)
            camera?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
            launchChooser(chooser)
        }
        return true
    }

    override fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        when {
            // Permissão que o app não declarou nunca é concedida: o chat mostra o aviso dele.
            !isDeclared(permission) -> onResult(false)
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> onResult(true)
            else -> {
                permissionResult?.invoke(false)
                permissionResult = onResult
                permissionRequest.launch(permission)
            }
        }
    }

    // Arquivos e câmera

    /**
     * A intent da câmera dispensa permissão, a não ser que o app declare CAMERA no manifest: aí o
     * Android exige a permissão concedida. [askIfNeeded] só pergunta quando o visitante pediu a câmera.
     */
    private fun withCamera(askIfNeeded: Boolean, then: (Boolean) -> Unit) {
        val permission = Manifest.permission.CAMERA
        when {
            !isDeclared(permission) -> then(true)
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> then(true)
            askIfNeeded -> requestPermission(permission, then)
            else -> then(false)
        }
    }

    private fun cameraIntent(): Intent? = runCatching {
        val directory = File(cacheDir, "atalia").apply { mkdirs() }
        val file = File(directory, "camera-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.atalia.fileprovider", file)
        cameraOutput = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrNull()

    private fun launchChooser(intent: Intent) {
        try {
            fileChooser.launch(intent)
        } catch (_: ActivityNotFoundException) {
            cameraOutput = null
            deliverFiles(null)
        }
    }

    /** O WebView exige uma resposta para cada pedido de arquivo, até o cancelamento (null). */
    private fun deliverFiles(files: Array<Uri>?) {
        fileCallback?.onReceiveValue(files)
        fileCallback = null
    }

    private fun isDeclared(permission: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)
}
