package ai.atalia.chat.internal

import ai.atalia.chat.Atalia
import org.json.JSONObject
import java.net.URLEncoder

internal const val BRIDGE_PROTOCOL = 1

/**
 * O JSON do `#sdk=` (protocol/README.md). Vai no fragmento para a sessão nunca viajar
 * na requisição nem cair em log de servidor.
 */
internal data class SdkFragment(
    val protocol: Int = BRIDGE_PROTOCOL,
    val platform: String = "android",
    val sdkVersion: String = Atalia.SDK_VERSION,
    val appVersion: String? = null,
    val session: String? = null,
    val identity: String? = null,
    val conversationId: Long? = null,
    val closeButton: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", protocol)
        put("platform", platform)
        put("sdkVersion", sdkVersion)
        put("closeButton", closeButton)
        appVersion?.let { put("appVersion", it) }
        session?.let { put("session", it) }
        identity?.let { put("identity", it) }
        conversationId?.let { put("conversationId", it) }
    }

    fun encoded(): String = Base64Url.encode(toJson().toString().toByteArray(Charsets.UTF_8))
}

internal object ChatUrl {
    /** `<base>/chat/<token>?sdk=1#sdk=...`, ou `<base>/?sdk=1#sdk=...` no domínio próprio. */
    fun build(baseUrl: String, channelToken: String, customDomain: Boolean, fragment: SdkFragment): String {
        val base = baseUrl.trimEnd('/')
        val path = if (customDomain) "/" else "/chat/${pathSegment(channelToken)}"
        return "$base$path?sdk=1#sdk=${fragment.encoded()}"
    }

    fun origin(baseUrl: String): String {
        val match = Regex("^(https?://[^/?#]+)").find(baseUrl.trim())
        return match?.value ?: baseUrl
    }

    fun pathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** base64url sem padding. Implementação própria: `java.util.Base64` só existe a partir da API 26. */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) out.append(ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val value = ALPHABET.indexOf(ch)
            require(value >= 0) { "caractere inválido em base64url: $ch" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
