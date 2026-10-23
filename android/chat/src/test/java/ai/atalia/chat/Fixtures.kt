package ai.atalia.chat

import org.json.JSONObject
import java.io.File

/** Fixtures compartilhadas pelos quatro SDKs (protocol/fixtures na raiz do repositório). */
object Fixtures {
    fun json(name: String): JSONObject {
        val dir = System.getProperty("atalia.fixtures") ?: "../../protocol/fixtures"
        return JSONObject(File(dir, name).readText())
    }
}
