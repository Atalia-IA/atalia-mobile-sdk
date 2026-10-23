package ai.atalia.chat.internal

import ai.atalia.chat.AtaliaEvent
import org.json.JSONException
import org.json.JSONObject

/** Mensagem web → nativo já decodificada. Contrato em protocol/README.md. */
internal class BridgeMessage private constructor(
    val version: Int,
    val type: String,
    private val payload: JSONObject,
) {
    fun string(key: String): String? = payload.opt(key) as? String

    /** Inteiros do JSON; booleanos e textos não contam. */
    fun long(key: String): Long? = (payload.opt(key) as? Number)?.toLong()

    fun bool(key: String): Boolean? = payload.opt(key) as? Boolean

    /** Evento público correspondente, ou null para mensagens internas e tipos desconhecidos. */
    fun toPublicEvent(): AtaliaEvent? = when (type) {
        "ready" -> AtaliaEvent.Ready
        "unread_changed" -> long("count")?.let { AtaliaEvent.UnreadChanged(it.toInt()) }
        "message_received" -> {
            val conversationId = long("conversationId")
            val messageId = long("messageId")
            if (conversationId != null && messageId != null) AtaliaEvent.MessageReceived(conversationId, messageId) else null
        }
        "conversation_started" -> long("conversationId")?.let { AtaliaEvent.ConversationStarted(it) }
        "conversation_closed" -> long("conversationId")?.let { AtaliaEvent.ConversationClosed(it) }
        "identified" -> {
            val externalId = string("externalId")
            val contactId = long("contactId")
            if (externalId != null && contactId != null) AtaliaEvent.Identified(externalId, contactId) else null
        }
        "logout" -> AtaliaEvent.LoggedOut
        "error" -> string("code")?.let { AtaliaEvent.Error(it, string("command")) }
        else -> null
    }

    companion object {
        /** Qualquer coisa fora do formato devolve null: o protocolo só cresce, nunca é erro. */
        fun parse(raw: String?): BridgeMessage? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val type = json.opt("type") as? String
                if (type.isNullOrEmpty()) return null
                BridgeMessage(json.optInt("v", 1), type, json.optJSONObject("payload") ?: JSONObject())
            } catch (_: JSONException) {
                null
            }
        }
    }
}
