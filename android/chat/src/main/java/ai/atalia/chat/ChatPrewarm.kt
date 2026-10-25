package ai.atalia.chat

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup

/**
 * Chat pré-carregado enquanto há botão flutuante na tela: a folha abre com o chat pronto, sem
 * esperar a página. Um só por processo. A página nasce em segundo plano (sem socket, sem presença,
 * sem marcar lidas), então a Atalia segue mandando push e nada é lido escondido.
 *
 * O WebView é criado num [MutableContextWrapper] do app e troca para a activity do chat quando
 * aparece (diálogos e seletor de arquivo precisam da activity), e de volta quando ela fecha.
 */
internal object ChatPrewarm {
    /** Espera o app assentar antes de carregar, para não disputar a abertura dele. */
    private const val PREPARE_DELAY_MS = 1_500L

    /** Uma troca de tela tira um botão e põe outro: só libera se nenhum voltar nesse tempo. */
    private const val RELEASE_DELAY_MS = 3_000L

    private val handler = Handler(Looper.getMainLooper())
    private var launchers = 0
    private var chat: AtaliaChatView? = null

    private val enabled: Boolean get() = launchers > 0

    /** Um botão flutuante com pré-carga apareceu. */
    fun launcherAttached(context: Context) {
        launchers++
        val app = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ prepare(app) }, PREPARE_DELAY_MS)
    }

    fun launcherDetached() {
        launchers = (launchers - 1).coerceAtLeast(0)
        if (enabled) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (!enabled) release() }, RELEASE_DELAY_MS)
    }

    private fun prepare(app: Context) {
        val core = Atalia.coreOrNull() ?: return
        if (!enabled || core.activeChat != null) return
        val current = chat
        if (current != null && current.isReusable(core)) return
        current?.destroy()
        chat = AtaliaChatView(MutableContextWrapper(app)).also { it.load(null, background = true) }
    }

    /** A tela do chat pega o pré-carregado, se ainda serve (mesmo canal e mesma sessão). */
    fun take(activity: Activity): AtaliaChatView? {
        val core = Atalia.coreOrNull() ?: return null
        val prepared = chat ?: return null
        chat = null
        if (!prepared.isReusable(core)) {
            prepared.destroy()
            return null
        }
        (prepared.context as? MutableContextWrapper)?.baseContext = activity
        return prepared
    }

    /**
     * A tela do chat fechou: com botão flutuante na tela, o chat volta a esperar escondido e a
     * próxima abertura é instantânea. Devolve `false` quando a tela deve destruir o chat.
     */
    fun recycle(view: AtaliaChatView, app: Context): Boolean {
        val core = Atalia.coreOrNull() ?: return false
        val wrapper = view.context as? MutableContextWrapper ?: return false
        if (!enabled || chat != null || !view.isReusable(core)) return false
        (view.parent as? ViewGroup)?.removeView(view)
        view.host = null
        view.deactivate()
        wrapper.baseContext = app.applicationContext
        chat = view
        return true
    }

    private fun release() {
        chat?.destroy()
        chat = null
    }
}
