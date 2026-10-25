package ai.atalia.chat

import android.content.Context
import android.net.Uri

/**
 * Configuração do SDK, passada em [Atalia.configure].
 *
 * @property baseUrl Origem do chat. Troque só para homologação ou para o domínio próprio do canal.
 * @property customDomain `true` quando [baseUrl] é o domínio próprio vinculado ao canal (o chat fica em `/`).
 * @property closeButton Mostra o botão "Fechar". Desligue quando o chat for uma aba do app.
 * @property debug Permite inspecionar o WebView pelo `chrome://inspect`. Nunca em produção.
 * @property urlHandler Decide como abrir links tocados no chat. Devolva `true` se o app tratou;
 *   `false` abre pelo sistema (navegador, discador, WhatsApp).
 * @property notificationIcon Ícone pequeno (monocromático) das notificações do chat. Sem ele, o
 *   balão de conversa da Atalia. O ícone do app não serve: colorido ou adaptativo, vira um quadrado
 *   branco ou é recusado pelo sistema.
 */
data class AtaliaOptions @JvmOverloads constructor(
    val baseUrl: String = DEFAULT_BASE_URL,
    val customDomain: Boolean = false,
    val closeButton: Boolean = true,
    val debug: Boolean = false,
    val urlHandler: ((Context, Uri) -> Boolean)? = null,
    @androidx.annotation.DrawableRes val notificationIcon: Int? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://chat.atalia.ai"
    }
}

/** Eventos do chat, entregues em [Atalia.events] e aos [AtaliaListener]. */
sealed class AtaliaEvent {
    /** O chat terminou de carregar. */
    data object Ready : AtaliaEvent()

    /** A tela do chat abriu. */
    data object Opened : AtaliaEvent()

    /** A tela do chat fechou. */
    data object Closed : AtaliaEvent()

    /** O visitante foi identificado ([Atalia.identify]). */
    data class Identified(val externalId: String, val contactId: Long) : AtaliaEvent()

    data object LoggedOut : AtaliaEvent()

    data class ConversationStarted(val conversationId: Long) : AtaliaEvent()

    data class ConversationClosed(val conversationId: Long) : AtaliaEvent()

    /** Chegou mensagem do atendente, da IA ou do sistema. Só ids: o texto nunca sai do chat. */
    data class MessageReceived(val conversationId: Long, val messageId: Long) : AtaliaEvent()

    data class UnreadChanged(val count: Int) : AtaliaEvent()

    /** Falha do chat (`INIT_FAILED`) ou de um comando (`command` = "identify", "open_conversation"...). */
    data class Error(val code: String, val command: String?) : AtaliaEvent()
}

/** Contato da Atalia vinculado ao usuário identificado. */
data class AtaliaContact(val externalId: String, val contactId: Long, val name: String?)

/**
 * Erro das chamadas do SDK. [code] é o mesmo código devolvido pela Atalia
 * (ex.: `EXPIRED`, `INVALID_SIGNATURE`, `NO_SECRET_CONFIGURED`).
 */
class AtaliaException(val code: String) : Exception(code) {
    companion object {
        /** [Atalia.configure] ainda não foi chamado. */
        const val NOT_CONFIGURED = "NOT_CONFIGURED"

        /** O chat não respondeu a tempo a um comando. */
        const val TIMEOUT = "TIMEOUT"
    }
}

fun interface AtaliaListener {
    fun onEvent(event: AtaliaEvent)
}

/** Resultado das chamadas assíncronas para quem chama de Java. Sempre na thread principal. */
interface AtaliaCallback<T> {
    fun onSuccess(value: T)

    fun onError(error: AtaliaException)
}
