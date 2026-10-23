package ai.atalia.chat

import ai.atalia.chat.internal.BridgeMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeMessageTest {
    private val fixture = Fixtures.json("bridge-events.json")

    @Test
    fun publicEventsMatchFixture() {
        val cases = fixture.getJSONArray("publicEvents")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val raw = item.getJSONObject("raw").toString()
            val message = assertNotNull(BridgeMessage.parse(raw), "não decodificou $raw")
            assertEquals("evento errado para $raw", expectedEvent(item.getJSONObject("event")), message.toPublicEvent())
        }
    }

    @Test
    fun internalMessagesAreNotPublicEvents() {
        val cases = fixture.getJSONArray("internalMessages")
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val message = assertNotNull(BridgeMessage.parse(item.getJSONObject("raw").toString()), "não decodificou")
            assertNull(message.toPublicEvent())
            val expect = item.optJSONObject("expect") ?: continue
            for (key in expect.keys()) {
                when (val value = expect.get(key)) {
                    is Boolean -> assertEquals(key, value, message.bool(key))
                    is String -> assertEquals(key, value, message.string(key))
                    else -> throw AssertionError("tipo inesperado em $key")
                }
            }
        }
    }

    @Test
    fun unknownAndMalformedMessagesAreIgnored() {
        val cases: JSONArray = fixture.getJSONArray("ignored")
        for (i in 0 until cases.length()) {
            val raw = when (val value = cases.get(i)) {
                is JSONObject -> value.toString()
                else -> value.toString()
            }
            assertNull(BridgeMessage.parse(raw)?.toPublicEvent())
        }
    }

    private fun expectedEvent(json: JSONObject): AtaliaEvent = when (val name = json.getString("name")) {
        "ready" -> AtaliaEvent.Ready
        "unreadChanged" -> AtaliaEvent.UnreadChanged(json.getInt("count"))
        "messageReceived" -> AtaliaEvent.MessageReceived(json.getLong("conversationId"), json.getLong("messageId"))
        "conversationStarted" -> AtaliaEvent.ConversationStarted(json.getLong("conversationId"))
        "conversationClosed" -> AtaliaEvent.ConversationClosed(json.getLong("conversationId"))
        "identified" -> AtaliaEvent.Identified(json.getString("externalId"), json.getLong("contactId"))
        "loggedOut" -> AtaliaEvent.LoggedOut
        "error" -> AtaliaEvent.Error(json.getString("code"), json.opt("command") as? String)
        else -> throw AssertionError("evento desconhecido na fixture: $name")
    }

    private fun <T> assertNotNull(value: T?, message: String): T {
        assertNotNull(message, value)
        return value!!
    }
}
