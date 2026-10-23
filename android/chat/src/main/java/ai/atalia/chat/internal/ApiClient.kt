package ai.atalia.chat.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class ApiResponse(val status: Int, val json: JSONObject?) {
    val isSuccess: Boolean get() = status in 200..299

    /** Código de erro da Atalia (`{ "code": "SESSION_EXPIRED" }`). */
    val code: String? get() = json?.opt("code") as? String
}

/** API pública do visitante do webchat (`/api/webchat/:token/...`), a mesma que o widget usa. */
internal interface AtaliaApi {
    suspend fun channelConfig(): ApiResponse

    /** Cria (ou confirma) a sessão. 401 `SESSION_EXPIRED` = sessão identificada que expirou. */
    suspend fun createSession(sessionToken: String): ApiResponse

    /** Devolve `{ sessionToken, expiresAt, contact: { id, externalId, name } }`. O token muda. */
    suspend fun identify(sessionToken: String, jwt: String): ApiResponse

    suspend fun logout(sessionToken: String): ApiResponse

    /** `{ total, conversations: [{ id, unread_count }] }`. */
    suspend fun unread(sessionToken: String): ApiResponse
}

internal class HttpApiClient(
    baseUrl: String,
    channelToken: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AtaliaApi {
    private val root = "${baseUrl.trimEnd('/')}/api/webchat/${ChatUrl.pathSegment(channelToken)}"

    override suspend fun channelConfig() = send("GET", "")

    override suspend fun createSession(sessionToken: String) =
        send("POST", "/sessions", body = JSONObject().put("sessionToken", sessionToken))

    override suspend fun identify(sessionToken: String, jwt: String) =
        send("POST", "/identify", sessionToken, JSONObject().put("identity", jwt))

    override suspend fun logout(sessionToken: String) = send("POST", "/logout", sessionToken)

    override suspend fun unread(sessionToken: String) = send("GET", "/unread", sessionToken)

    private suspend fun send(method: String, path: String, sessionToken: String? = null, body: JSONObject? = null): ApiResponse =
        withContext(io) {
            val connection = URL(root + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                sessionToken?.let { connection.setRequestProperty("x-session-token", it) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                ApiResponse(status, text.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() })
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
