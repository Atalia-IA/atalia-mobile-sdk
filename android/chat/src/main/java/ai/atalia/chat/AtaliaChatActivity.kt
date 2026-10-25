package ai.atalia.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import java.io.File

/** Tela do chat aberta por [Atalia.open]. */
class AtaliaChatActivity : ComponentActivity(), AtaliaChatView.Host {

    companion object {
        internal const val EXTRA_CONVERSATION_ID = "ai.atalia.chat.CONVERSATION_ID"
        private const val DEFAULT_PRIMARY = "#1036F2"
    }

    private lateinit var chatView: AtaliaChatView
    private var topInset: View? = null
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
            finish()
            return
        }

        // Bordas a bordas: barras do sistema transparentes, o topo (atrás da barra de status) na cor
        // do cabeçalho do canal com ícones claros, o rodapé claro, e o teclado encolhe o chat em vez
        // de cobrir o campo de mensagem. Sem o enableEdgeToEdge o Android 14 pinta as barras de cinza/preto.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val topInset = View(this).apply { setBackgroundColor(parsePrimary(core.primaryColor)) }
        this.topInset = topInset
        val bottomInset = View(this).apply { setBackgroundColor(Color.WHITE) }
        chatView = AtaliaChatView(this).also { it.host = this }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(topInset, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            addView(chatView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomInset, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            topInset.updateLayoutParams { height = bars.top }
            bottomInset.updateLayoutParams { height = maxOf(bars.bottom, ime.bottom) }
            view.updatePadding(left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        chatView.load(conversationIdFrom(intent))
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
            chatView.destroy()
            deliverFiles(null)
            if (isFinishing) Atalia.coreOrNull()?.emit(AtaliaEvent.Closed)
        }
        super.onDestroy()
    }

    private fun conversationIdFrom(intent: Intent?): Long? =
        intent?.takeIf { it.hasExtra(EXTRA_CONVERSATION_ID) }?.getLongExtra(EXTRA_CONVERSATION_ID, 0L)?.takeIf { it > 0 }

    // AtaliaChatView.Host

    override fun onCloseRequested() = finish()

    override fun onPrimaryColorChanged(color: String) {
        topInset?.setBackgroundColor(parsePrimary(color))
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
