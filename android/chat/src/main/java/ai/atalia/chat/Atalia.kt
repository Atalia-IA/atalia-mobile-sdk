package ai.atalia.chat

import ai.atalia.chat.internal.AtaliaCore
import ai.atalia.chat.internal.AtaliaStateHolder
import ai.atalia.chat.internal.HttpApiClient
import ai.atalia.chat.internal.KeystoreSessionStorage
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ponto de entrada do SDK do chat da Atalia.
 *
 * ```kotlin
 * Atalia.configure(context, "wct_...")
 * Atalia.open(activity)
 * ```
 */
object Atalia {
    const val SDK_VERSION = "0.1.0"

    private val state = AtaliaStateHolder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var core: AtaliaCore? = null
    private var lifecycleObserved = false

    /** Mensagens do atendente, da IA ou do sistema ainda não lidas pelo visitante. */
    @JvmStatic
    val unreadCount: StateFlow<Int> = state.unread.asStateFlow()

    /** A sessão atual pertence a um usuário identificado por [identify]. */
    @JvmStatic
    val isIdentified: StateFlow<Boolean> = state.identified.asStateFlow()

    @JvmStatic
    val events: SharedFlow<AtaliaEvent> = state.events.asSharedFlow()

    /**
     * Chame uma vez, no `Application.onCreate`. O token do canal fica em
     * Configurações › Canais › Webchat, no painel da Atalia.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, channelToken: String, options: AtaliaOptions = AtaliaOptions()) {
        val appContext = context.applicationContext
        val created = AtaliaCore(
            channelToken = channelToken,
            options = options,
            api = HttpApiClient(options.baseUrl, channelToken),
            store = KeystoreSessionStorage(appContext, options.baseUrl, channelToken),
            state = state,
            dispatchToMain = ::runOnMain,
        )
        core = created
        scope.launch { created.loadBranding() }
        runOnMain {
            // O observador do processo dispara onStart na hora em que é registrado (app já aberto)
            // ou quando o app abrir: é ele que busca as não lidas. Num segundo configure (outro
            // canal) ele já existe, então a busca é feita aqui.
            if (lifecycleObserved) {
                scope.launch { runCatching { created.refreshUnread() } }
            } else {
                observeAppForeground()
            }
        }
    }

    /** Abre o chat. */
    @JvmStatic
    fun open(context: Context) = start(context, null)

    /** Abre o chat direto numa conversa (toque numa notificação). Com o chat já aberto, só troca de conversa. */
    @JvmStatic
    fun openConversation(context: Context, conversationId: Long) = start(context, conversationId)

    /**
     * Vincula o visitante ao usuário logado no app. O JWT é assinado pelo SEU backend com o
     * segredo do canal (nunca no app) e vale no máximo 1 hora. Gere um novo a cada chamada.
     */
    suspend fun identify(jwt: String): AtaliaContact = requireCore().identify(jwt)

    @JvmStatic
    fun identify(jwt: String, callback: AtaliaCallback<AtaliaContact>) = launchWithCallback(callback) { identify(jwt) }

    /** Encerra a sessão do usuário identificado. Chame no logout do app. */
    suspend fun logout() {
        core?.logout()
    }

    /** Versão para Java e para quem não usa corrotinas; `callback` pode ser null. */
    @JvmStatic
    fun logout(callback: AtaliaCallback<Unit>?) = launchWithCallback(callback) { logout() }

    /** Consulta as não lidas na Atalia e atualiza [unreadCount]. */
    suspend fun refreshUnread(): Int = requireCore().refreshUnread()

    @JvmStatic
    fun refreshUnread(callback: AtaliaCallback<Int>) = launchWithCallback(callback) { refreshUnread() }

    @JvmStatic
    fun addListener(listener: AtaliaListener) {
        state.listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: AtaliaListener) {
        state.listeners.remove(listener)
    }

    internal fun coreOrNull(): AtaliaCore? = core

    private fun requireCore(): AtaliaCore = core ?: throw AtaliaException(AtaliaException.NOT_CONFIGURED)

    private fun start(context: Context, conversationId: Long?) {
        requireCore()
        val intent = Intent(context, AtaliaChatActivity::class.java)
        conversationId?.let { intent.putExtra(AtaliaChatActivity.EXTRA_CONVERSATION_ID, it) }
        // singleTop: com o chat no topo, a conversa nova chega por onNewIntent.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun <T> launchWithCallback(callback: AtaliaCallback<T>?, block: suspend () -> T) {
        scope.launch {
            try {
                val value = block()
                callback?.onSuccess(value)
            } catch (error: AtaliaException) {
                callback?.onError(error)
            } catch (error: Exception) {
                callback?.onError(AtaliaException(error.message ?: "NETWORK_ERROR"))
            }
        }
    }

    /** App voltou: o badge pode ter mudado. Com o chat aberto quem atualiza é o próprio widget. */
    private fun observeAppForeground() {
        if (lifecycleObserved) return
        lifecycleObserved = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val current = core ?: return
                if (current.activeChat != null) return
                scope.launch { runCatching { current.refreshUnread() } }
            }
        })
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
