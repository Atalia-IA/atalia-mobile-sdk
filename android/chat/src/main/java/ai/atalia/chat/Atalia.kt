package ai.atalia.chat

import ai.atalia.chat.internal.AtaliaCore
import ai.atalia.chat.internal.AtaliaNotifications
import ai.atalia.chat.internal.AtaliaStateHolder
import ai.atalia.chat.internal.ChannelBranding
import ai.atalia.chat.internal.HttpApiClient
import ai.atalia.chat.internal.KeystoreSessionStorage
import ai.atalia.chat.internal.PushPayload
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
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
import org.json.JSONObject
import java.util.Locale

/**
 * Ponto de entrada do SDK do chat da Atalia.
 *
 * ```kotlin
 * Atalia.configure(context, "wct_...")
 * Atalia.open(activity)
 * ```
 */
object Atalia {
    const val SDK_VERSION = "0.2.0"

    private val state = AtaliaStateHolder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var core: AtaliaCore? = null
    private var lifecycleObserved = false

    @Volatile
    private var pendingPushToken: String? = null

    /** Mensagens do atendente, da IA ou do sistema ainda não lidas pelo visitante. */
    @JvmStatic
    val unreadCount: StateFlow<Int> = state.unread.asStateFlow()

    /** A sessão atual pertence a um usuário identificado por [identify]. */
    @JvmStatic
    val isIdentified: StateFlow<Boolean> = state.identified.asStateFlow()

    /** A tela do chat está aberta. */
    @JvmStatic
    val isChatOpen: StateFlow<Boolean> = state.chatOpen.asStateFlow()

    @JvmStatic
    val events: SharedFlow<AtaliaEvent> = state.events.asSharedFlow()

    /** Aparência do canal no painel, para o botão flutuante. */
    internal val brandingFlow: StateFlow<ChannelBranding?> = state.branding.asStateFlow()

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
            deviceInfo = { deviceInfo(appContext) },
            launchInBackground = { block -> scope.launch { block() } },
            clearNotifications = { AtaliaNotifications.cancelAll(appContext) },
        )
        core = created
        AtaliaNotifications.ensureChannel(appContext)
        scope.launch {
            created.loadBranding()
            pendingPushToken?.let { token ->
                pendingPushToken = null
                runCatching { created.setPushToken(token) }
            }
            runCatching { created.registerPushTokenIfNeeded() }
        }
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

    // Push

    /**
     * Token do Firebase Cloud Messaging do aparelho. Chame no `onNewToken` do seu
     * `FirebaseMessagingService` e ao abrir o app (`FirebaseMessaging.getInstance().token`).
     * O SDK registra o aparelho na Atalia e o mantém registrado quando o usuário entra ou sai.
     */
    @JvmStatic
    fun setPushToken(token: String) {
        val current = core
        if (current == null) {
            // onNewToken pode chegar antes do configure: aplica quando ele vier.
            pendingPushToken = token
            return
        }
        scope.launch { runCatching { current.setPushToken(token) } }
    }

    /** Tira o aparelho da Atalia (o usuário desligou as notificações do atendimento no app). */
    @JvmStatic
    fun clearPushToken() {
        pendingPushToken = null
        val current = core ?: return
        scope.launch { runCatching { current.clearPushToken() } }
    }

    /** A notificação veio da Atalia? Use no `onMessageReceived` antes de tratar as suas. */
    @JvmStatic
    fun isAtaliaNotification(data: Map<String, String>?): Boolean = PushPayload.parse(data) != null

    /**
     * Chame no `onMessageReceived` (app aberto: o Firebase não mostra nada sozinho). Atualiza as não
     * lidas e mostra a notificação, a não ser que o chat já esteja na tela.
     * @return false quando a notificação não é da Atalia (trate como as suas).
     */
    @JvmStatic
    fun handleNotification(context: Context, data: Map<String, String>?, title: String?, body: String?): Boolean {
        val payload = PushPayload.parse(data) ?: return false
        val current = core
        current?.let { scope.launch { runCatching { it.refreshUnread() } } }
        if (current?.shouldShowNotification() == false) return true
        val icon = current?.options?.notificationIcon ?: context.applicationInfo.icon
        AtaliaNotifications.show(context.applicationContext, payload, title, body, icon)
        return true
    }

    /**
     * Toque na notificação mostrada pelo sistema (app em segundo plano): o Firebase abre a activity
     * de entrada do app com os dados da notificação nos extras. Chame no `onCreate` e no
     * `onNewIntent` dessa activity.
     * @return true quando era da Atalia e o chat foi aberto.
     */
    @JvmStatic
    fun handleNotificationTap(activity: Activity, intent: Intent?): Boolean {
        if (intent == null) return false
        val extras = intent.extras ?: return false
        @Suppress("DEPRECATION")
        val data = extras.keySet().associateWith { key -> extras.get(key)?.toString() }
        val payload = PushPayload.parse(data) ?: return false
        if (core == null) return false
        // Não reabrir o chat quando a activity for recriada (rotação, volta do fundo).
        intent.removeExtra("atalia")
        if (payload.conversationId != null) openConversation(activity, payload.conversationId) else open(activity)
        return true
    }

    // Botão flutuante

    /**
     * Mostra o botão flutuante que abre o chat por cima desta tela (View ou Compose). A aparência
     * vem do painel; posição e comportamento, de [options]. Chamar de novo só troca as opções.
     */
    @JvmStatic
    @JvmOverloads
    fun showLauncher(activity: Activity, options: AtaliaLauncherOptions = AtaliaLauncherOptions()): AtaliaLauncherView {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.findViewWithTag<AtaliaLauncherView>(LAUNCHER_TAG)?.let { existing ->
            existing.options = options
            return existing
        }
        val launcher = AtaliaLauncherView(activity, options = options)
        launcher.tag = LAUNCHER_TAG
        launcher.positionOnScreen()
        content.addView(launcher)
        return launcher
    }

    /** Tira o botão flutuante desta tela. */
    @JvmStatic
    fun hideLauncher(activity: Activity) {
        activity.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag<AtaliaLauncherView>(LAUNCHER_TAG)?.remove()
    }

    private const val LAUNCHER_TAG = "ai.atalia.chat.launcher"

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

    private fun deviceInfo(context: Context): JSONObject {
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return JSONObject()
            .put("platform", "android")
            .put("app_id", context.packageName)
            .put("sdk_version", SDK_VERSION)
            .put("locale", Locale.getDefault().toLanguageTag())
            .apply { appVersion?.let { put("app_version", it) } }
    }
}
