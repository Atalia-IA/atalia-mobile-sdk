package ai.atalia.chat

import ai.atalia.chat.internal.Base64Url
import ai.atalia.chat.internal.ChatUrl
import ai.atalia.chat.internal.NoticeTarget
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
            val popup = item.optBoolean("popup", false)
            val notice = expected.optJSONObject("notice")
            val fragment = if (popup) {
                // A camada dos balões só leva diagnóstico, sessão e tela do app.
                ChatUrl.popupFragment(expected.opt("appVersion") as? String, expected.opt("session") as? String, expected.opt("page") as? String)
                    .copy(protocol = expected.getInt("protocol"), platform = expected.getString("platform"), sdkVersion = expected.getString("sdkVersion"))
            } else {
                SdkFragment(
                    protocol = expected.getInt("protocol"),
                    platform = expected.getString("platform"),
                    sdkVersion = expected.getString("sdkVersion"),
                    appVersion = expected.opt("appVersion") as? String,
                    session = expected.opt("session") as? String,
                    conversationId = (expected.opt("conversationId") as? Number)?.toLong(),
                    closeButton = expected.optBoolean("closeButton", true),
                    background = expected.optBoolean("background", false),
                    page = expected.opt("page") as? String,
                    notice = notice?.let { NoticeTarget(it.getLong("ruleId"), (it.opt("button") as? Number)?.toInt()) },
                )
            }
            val url = ChatUrl.build(item.getString("baseUrl"), item.getString("channelToken"), item.getBoolean("customDomain"), fragment, popup)
            val prefix = item.getString("expectedPrefix")
            assertTrue("$name: $url", url.startsWith(prefix))

            // O fragmento decodifica de volta para o mesmo JSON que o widget lê.
            val encoded = url.removePrefix(prefix)
            assertFalse("$name: base64url sem padding", encoded.contains("="))
            val decoded = JSONObject(String(Base64Url.decode(encoded), Charsets.UTF_8))
            assertEquals(name, expected.keys().asSequence().toSet(), decoded.keys().asSequence().toSet())
            for (key in expected.keys()) {
                val value = expected.get(key)
                if (value is JSONObject) {
                    assertTrue("$name.$key", value.similar(decoded.get(key)))
                } else {
                    assertEquals("$name.$key", value.toString(), decoded.get(key).toString())
                }
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
    fun popupUrlAndNoticeCommand() {
        val fragment = ChatUrl.popupFragment("1.0", "sdk-session-123456", "/cursos/10")
        assertTrue(ChatUrl.build("https://chat.atalia.ai/", "wct_x", false, fragment, popup = true).startsWith("https://chat.atalia.ai/chat/wct_x?sdk=popup#sdk="))
        assertEquals(setOf("protocol", "platform", "sdkVersion", "appVersion", "session", "page"), fragment.toJson().keys().asSequence().toSet())

        // No comando `open_notice` o botão vai sempre, inteiro ou null.
        assertTrue(NoticeTarget(7).toCommand().isNull("button"))
        assertEquals(0, NoticeTarget(7, 0).toCommand().getInt("button"))
        assertFalse(NoticeTarget(7).toJson().has("button"))
    }

    @Test
    fun pageIsTrimmedAndLimited() {
        assertEquals("/cursos/10", ChatUrl.normalizePage("  /cursos/10 "))
        assertEquals(null, ChatUrl.normalizePage("   "))
        assertEquals(null, ChatUrl.normalizePage(null))
        assertEquals(2048, ChatUrl.normalizePage("a".repeat(3000))?.length)
    }

    @Test
    fun trailingSlashAndOrigin() {
        assertTrue(ChatUrl.build("https://chat.atalia.ai/", "wct_x", false, SdkFragment()).startsWith("https://chat.atalia.ai/chat/wct_x?sdk=1#sdk="))
        assertEquals("https://chat.atalia.ai", ChatUrl.origin("https://chat.atalia.ai/chat/wct_x?sdk=1#sdk=abc"))
        assertEquals("http://10.0.2.2:8787", ChatUrl.origin("http://10.0.2.2:8787/"))
    }
}
