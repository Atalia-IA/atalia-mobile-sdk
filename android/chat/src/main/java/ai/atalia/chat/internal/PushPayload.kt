package ai.atalia.chat.internal

/**
 * `data` da notificação da Atalia (protocol/README.md, "Notificação push"). Igual no Firebase e
 * no webhook: a chave `atalia = "1"` é o que separa da notificação de outras origens do app.
 */
internal data class PushPayload(
    val channelToken: String?,
    val conversationId: Long?,
    val messageId: Long?,
    val isTest: Boolean,
) {
    companion object {
        fun parse(data: Map<String, String?>?): PushPayload? {
            if (data == null || data["atalia"] != "1") return null
            return PushPayload(
                channelToken = data["channel_token"]?.takeIf { it.isNotBlank() },
                conversationId = data["conversation_id"]?.toLongOrNull()?.takeIf { it > 0 },
                messageId = data["message_id"]?.toLongOrNull()?.takeIf { it > 0 },
                isTest = data["type"] == "test",
            )
        }
    }
}
