package ai.atalia.chat

import ai.atalia.chat.internal.Base64Url
import ai.atalia.chat.internal.ChatUrl
import ai.atalia.chat.internal.SdkFragment
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUrlTest {
    @Test
    fun urlsMatchFixture() {
        val cases = Fixtures.json("sdk-fragment.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val name = item.getString("name")
            val expected = item.getJSONObject("fragment")
            val fragment = SdkFragment(
                protocol = expected.getInt("protocol"),
                platform = expected.getString("platform"),
                sdkVersion = expected.getString("sdkVersion"),
                appVersion = expected.opt("appVersion") as? String,
                session = expected.opt("session") as? String,
                conversationId = (expected.opt("conversationId") as? Number)?.toLong(),
                closeButton = expected.optBoolean("closeButton", true),
            )
            val url = ChatUrl.build(item.getString("baseUrl"), item.getString("channelToken"), item.getBoolean("customDomain"), fragment)
            val prefix = item.getString("expectedPrefix")
            assertTrue("$name: $url", url.startsWith(prefix))

            // O fragmento decodifica de volta para o mesmo JSON que o widget lê.
            val encoded = url.removePrefix(prefix)
            assertFalse("$name: base64url sem padding", encoded.contains("="))
            val decoded = JSONObject(String(Base64Url.decode(encoded), Charsets.UTF_8))
            assertEquals(name, expected.keys().asSequence().toSet(), decoded.keys().asSequence().toSet())
            for (key in expected.keys()) {
                assertEquals("$name.$key", expected.get(key).toString(), decoded.get(key).toString())
            }
        }
    }

    @Test
    fun base64UrlRoundTripsEveryLength() {
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + 250).toByte() }
            val encoded = Base64Url.encode(bytes)
            assertFalse(encoded.contains('+') || encoded.contains('/') || encoded.contains('='))
            assertArrayEquals(bytes, Base64Url.decode(encoded))
        }
    }

    @Test
    fun trailingSlashAndOrigin() {
        assertTrue(ChatUrl.build("https://chat.atalia.ai/", "wct_x", false, SdkFragment()).startsWith("https://chat.atalia.ai/chat/wct_x?sdk=1#sdk="))
        assertEquals("https://chat.atalia.ai", ChatUrl.origin("https://chat.atalia.ai/chat/wct_x?sdk=1#sdk=abc"))
        assertEquals("http://10.0.2.2:8787", ChatUrl.origin("http://10.0.2.2:8787/"))
    }
}
