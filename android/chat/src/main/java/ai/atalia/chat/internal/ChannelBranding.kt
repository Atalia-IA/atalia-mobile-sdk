package ai.atalia.chat.internal

import org.json.JSONObject

/**
 * Aparência do canal configurada no painel da Atalia (a mesma do botão do site). Fica salva no
 * aparelho: a tela do chat e o botão flutuante já nascem certos, antes de a consulta voltar.
 */
internal data class ChannelBranding(
    val primaryColor: String? = null,
    val launcherColor: String? = null,
    val launcherText: String? = null,
    /** `bottom-left` ou `bottom-right`. */
    val launcherPosition: String? = null,
    /** Nome do atendimento (o mesmo do cabeçalho do chat): subtítulo do botão flutuante. */
    val botName: String? = null,
) {
    /** Cor do botão: a dele, senão a do canal (mesma regra do site). */
    val buttonColor: String? get() = launcherColor ?: primaryColor
    val buttonText: String get() = launcherText ?: "Fale conosco"
    val buttonOnStart: Boolean get() = launcherPosition == "bottom-left"

    fun toJson(): String = JSONObject()
        .put("primary_color", primaryColor)
        .put("launcher_color", launcherColor)
        .put("launcher_text", launcherText)
        .put("launcher_position", launcherPosition)
        .put("bot_name", botName)
        .toString()

    companion object {
        /**
         * `branding` de `GET /api/webchat/:token` (ou o que foi salvo por [toJson]). Sem `bot_name`, o
         * nome do atendimento é o do canal ([channelName]), a mesma regra do cabeçalho do chat.
         */
        fun fromJson(json: JSONObject, channelName: String? = null): ChannelBranding {
            fun text(key: String) = (json.opt(key) as? String)?.takeIf { it.isNotBlank() }
            return ChannelBranding(
                primaryColor = text("primary_color"),
                launcherColor = text("launcher_color"),
                launcherText = text("launcher_text"),
                launcherPosition = text("launcher_position"),
                botName = text("bot_name") ?: channelName?.takeIf { it.isNotBlank() },
            )
        }
    }
}
