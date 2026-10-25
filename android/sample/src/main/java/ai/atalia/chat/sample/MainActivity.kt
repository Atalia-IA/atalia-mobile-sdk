package ai.atalia.chat.sample

import ai.atalia.chat.Atalia
import ai.atalia.chat.AtaliaException
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** Tela mínima: abrir o chat, identificar com um JWT de teste, sair e ver as não lidas. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply { textSize = 16f }
        val jwt = EditText(this).apply {
            hint = "JWT de identificação (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 2, padding, padding)
            addView(status)
            addView(Button(context).apply {
                text = "Abrir chat"
                setOnClickListener { Atalia.open(this@MainActivity) }
            })
            addView(jwt)
            addView(Button(context).apply {
                text = "Identificar"
                setOnClickListener {
                    lifecycleScope.launch {
                        try {
                            val contact = Atalia.identify(jwt.text.toString().trim())
                            toast("Identificado: ${contact.externalId}")
                        } catch (error: AtaliaException) {
                            toast("Falhou: ${error.code}")
                        }
                    }
                }
            })
            addView(Button(context).apply {
                text = "Sair"
                setOnClickListener { lifecycleScope.launch { Atalia.logout() } }
            })
            // Sem Firebase no exemplo: entrega ao SDK o mesmo `data` que a Atalia manda no push.
            addView(Button(context).apply {
                text = "Simular notificação"
                setOnClickListener {
                    val data = mapOf(
                        "atalia" to "1",
                        "v" to "1",
                        "channel_token" to BuildConfig.ATALIA_CHANNEL_TOKEN,
                        "conversation_id" to "101",
                        "message_id" to "1",
                        "sender_type" to "agent",
                    )
                    Atalia.handleNotification(this@MainActivity, data, "Ana", "Seu pedido saiu para entrega")
                }
            })
        }
        setContentView(layout)
        requestNotificationPermission()
        // Toque na notificação mostrada pelo sistema com o app em segundo plano.
        Atalia.handleNotificationTap(this, intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Atalia.unreadCount.collect { status.text = render(it, Atalia.isIdentified.value) } }
                launch { Atalia.isIdentified.collect { status.text = render(Atalia.unreadCount.value, it) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Atalia.handleNotificationTap(this, intent)
    }

    /** Android 13+: sem a permissão, a notificação não aparece. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun render(unread: Int, identified: Boolean) =
        "Não lidas: $unread\n${if (identified) "Usuário identificado" else "Visitante anônimo"}"

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
