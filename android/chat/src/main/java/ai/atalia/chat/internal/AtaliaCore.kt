package ai.atalia.chat.internal

import ai.atalia.chat.AtaliaContact
import ai.atalia.chat.AtaliaEvent
import ai.atalia.chat.AtaliaException
import ai.atalia.chat.AtaliaListener
import ai.atalia.chat.AtaliaOptions
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/** Quem recebe comandos nativo → web: a tela do chat aberta no momento. */
internal interface ChatBridgeTarget {
    fun sendCommand(type: String, payload: JSONObject?)
}

/** Fluxos que sobrevivem a um novo `configure` (o app pode observar antes de configurar). */
internal class AtaliaStateHolder {
    val unread = MutableStateFlow(0)
    val identified = MutableStateFlow(false)
    val events = MutableSharedFlow<AtaliaEvent>(extraBufferCapacity = 64)
    val listeners = CopyOnWriteArraySet<AtaliaListener>()
}

/**
 * Regras do SDK sem nada de Android, para rodar nos testes de JVM. A fachada pública é `Atalia`.
 *
 * @param dispatchToMain entrega os listeners na thread principal (nos testes, direto).
 */
internal class AtaliaCore(
    val channelToken: String,
    val options: AtaliaOptions,
    private val api: AtaliaApi,
    private val store: SessionStorage,
    private val state: AtaliaStateHolder,
    private val dispatchToMain: (() -> Unit) -> Unit = { it() },
    /** Dados do aparelho para o registro do push (app, versão, idioma). */
    private val deviceInfo: () -> JSONObject = { JSONObject().put("platform", "android") },
    /** Roda trabalho de fundo (re-registro do push depois de troca de sessão vinda do widget). */
    private val launchInBackground: (suspend () -> Unit) -> Unit = {},
    /** Tira da bandeja as notificações da Atalia. */
    private val clearNotifications: () -> Unit = {},
) {
    private val sessionMutex = Mutex()
    private val pushMutex = Mutex()
    private var activeChatRef: WeakReference<ChatBridgeTarget>? = null

    /** Sessão e token já registrados juntos: evita reenviar o registro a cada evento. */
    @Volatile
    private var registeredPush: Pair<String, String>? = null

    var activeChat: ChatBridgeTarget?
        get() = activeChatRef?.get()
        set(value) {
            activeChatRef = value?.let { WeakReference(it) }
        }

    @Volatile
    var primaryColor: String? = store.loadPrimaryColor()
        private set

    init {
        state.identified.value = store.load()?.identified ?: false
    }

    val storedSessionToken: String? get() = store.load()?.token

    // Sessão

    /** Token da sessão atual, criando uma anônima na primeira vez. Chamadas simultâneas esperam a mesma criação. */
    suspend fun ensureSession(): String = sessionMutex.withLock {
        store.load()?.token ?: run {
            val token = UUID.randomUUID().toString()
            val response = api.createSession(token)
            if (!response.isSuccess) throw AtaliaException(response.code ?: "SESSION_FAILED")
            store.save(StoredSession(token, identified = false, externalId = null))
            token
        }
    }

    // Identidade

    suspend fun identify(jwt: String): AtaliaContact {
        if (jwt.isBlank()) throw AtaliaException("INVALID_TOKEN")

        // Chat aberto: quem identifica é o widget (recarrega a conversa no lugar e avisa a
        // sessão nova por session_changed). A resposta chega como evento.
        activeChat?.let { chat ->
            val event = sendAndAwait(IDENTIFY_TIMEOUT_MS, { chat.sendCommand("identify", JSONObject().put("jwt", jwt)) }) {
                it is AtaliaEvent.Identified || (it is AtaliaEvent.Error && it.command == "identify")
            }
            return when (event) {
                is AtaliaEvent.Identified -> AtaliaContact(event.externalId, event.contactId, null)
                is AtaliaEvent.Error -> throw AtaliaException(event.code)
                else -> throw AtaliaException("INVALID_TOKEN")
            }
        }

        val token = ensureSession()
        val response = api.identify(token, jwt)
        val json = response.json
        val contact = json?.optJSONObject("contact")
        val newToken = json?.opt("sessionToken") as? String
        val externalId = contact?.opt("externalId") as? String
        val contactId = (contact?.opt("id") as? Number)?.toLong()
        if (!response.isSuccess || newToken == null || externalId == null || contactId == null) {
            throw AtaliaException(response.code ?: "INVALID_TOKEN")
        }
        store.save(StoredSession(newToken, identified = true, externalId = externalId))
        state.identified.value = true
        emit(AtaliaEvent.Identified(externalId, contactId))
        runCatching { refreshUnread() }
        runCatching { registerPushTokenIfNeeded() }
        return AtaliaContact(externalId, contactId, contact.opt("name") as? String)
    }

    suspend fun logout() {
        activeChat?.let { chat ->
            runCatching {
                sendAndAwait(LOGOUT_TIMEOUT_MS, { chat.sendCommand("logout", null) }) { it is AtaliaEvent.LoggedOut }
            }
            return
        }
        store.load()?.token?.let { token -> runCatching { api.logout(token) } }
        // A próxima abertura cria uma sessão anônima nova: o histórico do usuário anterior
        // não aparece para quem usar o aparelho depois.
        store.clear()
        registeredPush = null
        state.identified.value = false
        setUnread(0)
        syncNotifications(0)
        emit(AtaliaEvent.LoggedOut)
        // O logout apagou os aparelhos da sessão no servidor: o aparelho volta como visitante anônimo.
        runCatching { registerPushTokenIfNeeded() }
    }

    // Push

    suspend fun setPushToken(token: String) {
        if (token.isBlank()) return
        store.savePushToken(token.trim())
        registerPushTokenIfNeeded()
    }

    /** O app desligou as notificações do usuário: o aparelho sai da Atalia. */
    suspend fun clearPushToken() {
        val token = store.loadPushToken() ?: return
        store.clearPushToken()
        registeredPush = null
        store.load()?.token?.let { session -> runCatching { api.unregisterPushDevice(session, token) } }
    }

    /**
     * Registra o token do push na sessão atual (cria uma anônima se ainda não houver). Sessão que
     * o servidor diz ter vencido é trocada por uma nova e o registro é repetido uma vez.
     */
    suspend fun registerPushTokenIfNeeded() = pushMutex.withLock {
        val token = store.loadPushToken() ?: return@withLock
        var session = ensureSession()
        if (registeredPush == session to token) return@withLock

        var response = api.registerPushDevice(session, deviceInfo().put("token", token).put("provider", "fcm"))
        if (response.status == 401) {
            store.clear()
            state.identified.value = false
            session = ensureSession()
            response = api.registerPushDevice(session, deviceInfo().put("token", token).put("provider", "fcm"))
        }
        if (response.isSuccess) registeredPush = session to token
    }

    /** Com o chat na tela, a mensagem já aparece nele: notificação seria repetida. */
    fun shouldShowNotification(): Boolean = activeChat == null

    // Não lidas

    suspend fun refreshUnread(): Int {
        val token = store.load()?.token ?: run {
            setUnread(0)
            syncNotifications(0)
            return 0
        }
        val response = api.unread(token)
        if (!response.isSuccess) throw AtaliaException(response.code ?: "UNREAD_FAILED")
        val total = (response.json?.opt("total") as? Number)?.toInt() ?: 0
        setUnread(total)
        syncNotifications(total)
        return total
    }

    /**
     * Tudo lido: as notificações da Atalia saem da bandeja, e com elas a bolinha do ícone do app.
     * Só com push ligado.
     */
    private fun syncNotifications(unread: Int) {
        if (unread == 0 && store.loadPushToken() != null) clearNotifications()
    }

    private fun setUnread(count: Int) {
        if (state.unread.value == count) return
        state.unread.value = count
        emit(AtaliaEvent.UnreadChanged(count))
    }

    // Canal

    suspend fun loadBranding() {
        val response = runCatching { api.channelConfig() }.getOrNull() ?: return
        val color = response.json?.optJSONObject("branding")?.opt("primary_color") as? String ?: return
        if (response.isSuccess) {
            primaryColor = color
            store.savePrimaryColor(color)
        }
    }

    // Bridge

    /** Mensagem do widget, já validada pela tela do chat (frame principal e origem da Atalia). */
    fun handle(message: BridgeMessage) {
        when (message.type) {
            "session_changed" -> message.string("sessionToken")?.let { token ->
                val identified = message.bool("identified") ?: false
                store.save(StoredSession(token, identified, message.string("externalId")))
                state.identified.value = identified
                // Sessão nova vinda do widget (identify/logout pelo chat): o aparelho acompanha.
                if (store.loadPushToken() != null && registeredPush?.first != token) {
                    launchInBackground { runCatching { registerPushTokenIfNeeded() } }
                }
            }
            "unread_changed" -> message.long("count")?.let {
                state.unread.value = it.toInt()
                syncNotifications(it.toInt())
            }
            "identified" -> state.identified.value = true
            "logout" -> state.identified.value = false
        }
        message.toPublicEvent()?.let(::emit)
    }

    // Eventos

    fun emit(event: AtaliaEvent) {
        state.events.tryEmit(event)
        if (state.listeners.isEmpty()) return
        dispatchToMain {
            state.listeners.forEach { it.onEvent(event) }
        }
    }

    /** Assina os eventos ANTES de mandar o comando: a resposta do widget pode chegar no mesmo ciclo. */
    private suspend fun sendAndAwait(timeoutMs: Long, send: () -> Unit, match: (AtaliaEvent) -> Boolean): AtaliaEvent = coroutineScope {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { state.events.first(match) }
        send()
        withTimeoutOrNull(timeoutMs) { waiting.await() } ?: run {
            waiting.cancel()
            throw AtaliaException(AtaliaException.TIMEOUT)
        }
    }

    private companion object {
        const val IDENTIFY_TIMEOUT_MS = 20_000L
        const val LOGOUT_TIMEOUT_MS = 15_000L
    }
}
