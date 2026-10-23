package ai.atalia.chat

import ai.atalia.chat.internal.ApiResponse
import ai.atalia.chat.internal.AtaliaApi
import ai.atalia.chat.internal.AtaliaCore
import ai.atalia.chat.internal.AtaliaStateHolder
import ai.atalia.chat.internal.BridgeMessage
import ai.atalia.chat.internal.ChatBridgeTarget
import ai.atalia.chat.internal.MemorySessionStorage
import ai.atalia.chat.internal.StoredSession
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AtaliaCoreTest {
    private class FakeApi : AtaliaApi {
        val calls = mutableListOf<String>()
        var sessions = ApiResponse(201, JSONObject().put("isNew", true))
        var identifyResponse = ApiResponse(404, null)
        var unreadResponse = ApiResponse(200, JSONObject().put("total", 0))

        override suspend fun channelConfig() = ApiResponse(200, JSONObject().put("branding", JSONObject().put("primary_color", "#112233")))

        override suspend fun createSession(sessionToken: String): ApiResponse {
            calls += "sessions:$sessionToken"
            delay(10) // simultâneas têm que esperar a mesma criação
            return sessions
        }

        override suspend fun identify(sessionToken: String, jwt: String): ApiResponse {
            calls += "identify:$sessionToken:$jwt"
            return identifyResponse
        }

        override suspend fun logout(sessionToken: String): ApiResponse {
            calls += "logout:$sessionToken"
            return ApiResponse(204, null)
        }

        override suspend fun unread(sessionToken: String): ApiResponse {
            calls += "unread:$sessionToken"
            return unreadResponse
        }
    }

    private class FakeChat(private val reply: (String, JSONObject?) -> String?) : ChatBridgeTarget {
        lateinit var core: AtaliaCore
        val commands = mutableListOf<String>()

        override fun sendCommand(type: String, payload: JSONObject?) {
            commands += type
            reply(type, payload)?.let { core.handle(BridgeMessage.parse(it)!!) }
        }
    }

    private val api = FakeApi()
    private val store = MemorySessionStorage()
    private val state = AtaliaStateHolder()
    private val events = mutableListOf<AtaliaEvent>()
    private val core = AtaliaCore("wct_test", AtaliaOptions(), api, store, state).also {
        state.listeners += AtaliaListener { event -> events += event }
    }

    @Test
    fun ensureSessionCreatesOnceAndStores() = runTest {
        val first = async { core.ensureSession() }
        val second = async { core.ensureSession() }
        val token = first.await()
        assertEquals(token, second.await())
        assertEquals(listOf("sessions:$token"), api.calls)
        assertEquals(StoredSession(token, identified = false, externalId = null), store.load())
    }

    @Test
    fun ensureSessionReusesStoredToken() = runTest {
        store.save(StoredSession("existing-token-1", true, "u1"))
        assertEquals("existing-token-1", core.ensureSession())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun identifyWithoutChatStoresNewSession() = runTest {
        store.save(StoredSession("anon-token-1", false, null))
        api.identifyResponse = ApiResponse(
            200,
            JSONObject()
                .put("sessionToken", "identified-token-2")
                .put("contact", JSONObject().put("id", 55).put("externalId", "user-42").put("name", "Ana")),
        )
        api.unreadResponse = ApiResponse(200, JSONObject().put("total", 2))

        val contact = core.identify("jwt.de.teste")

        assertEquals(AtaliaContact("user-42", 55, "Ana"), contact)
        assertEquals(StoredSession("identified-token-2", true, "user-42"), store.load())
        assertTrue(state.identified.value)
        assertEquals(2, state.unread.value)
        assertTrue(events.contains(AtaliaEvent.Identified("user-42", 55)))
        assertTrue(api.calls.contains("identify:anon-token-1:jwt.de.teste"))
    }

    @Test
    fun identifyPropagatesServerCode() = runTest {
        store.save(StoredSession("anon-token-1", false, null))
        api.identifyResponse = ApiResponse(401, JSONObject().put("code", "EXPIRED"))
        try {
            core.identify("jwt.vencido")
            fail("deveria falhar")
        } catch (error: AtaliaException) {
            assertEquals("EXPIRED", error.code)
        }
        assertEquals("anon-token-1", store.load()?.token)
    }

    @Test
    fun refreshUnreadEmitsOnlyOnChange() = runTest {
        store.save(StoredSession("token-1234", false, null))
        api.unreadResponse = ApiResponse(200, JSONObject().put("total", 3))
        assertEquals(3, core.refreshUnread())
        core.refreshUnread()
        assertEquals(3, state.unread.value)
        assertEquals(1, events.count { it == AtaliaEvent.UnreadChanged(3) })
    }

    @Test
    fun logoutWithoutChatClearsSession() = runTest {
        store.save(StoredSession("identified-token-2", true, "user-42"))
        state.identified.value = true
        core.logout()
        assertNull(store.load())
        assertFalse(state.identified.value)
        assertTrue(events.contains(AtaliaEvent.LoggedOut))
        assertTrue(api.calls.contains("logout:identified-token-2"))
    }

    @Test
    fun sessionChangedFromWidgetIsStored() {
        core.handle(BridgeMessage.parse("""{"v":1,"type":"session_changed","payload":{"sessionToken":"from-widget-1","identified":true,"externalId":"user-9"}}""")!!)
        assertEquals(StoredSession("from-widget-1", true, "user-9"), store.load())
        assertTrue(state.identified.value)
        assertTrue("session_changed não é evento público", events.isEmpty())
    }

    @Test
    fun identifyWithOpenChatGoesThroughWidget() = runTest {
        val chat = FakeChat { type, _ ->
            if (type == "identify") """{"v":1,"type":"identified","payload":{"externalId":"user-7","contactId":70}}""" else null
        }
        chat.core = core
        core.activeChat = chat

        val contact = core.identify("jwt.de.teste")

        assertEquals("user-7", contact.externalId)
        assertEquals(listOf("identify"), chat.commands)
        assertTrue("com o chat aberto quem chama a API é o widget", api.calls.none { it.startsWith("identify") })
    }

    @Test
    fun identifyErrorFromWidgetIsThrown() = runTest {
        val chat = FakeChat { _, _ -> """{"v":1,"type":"error","payload":{"command":"identify","code":"INVALID_SIGNATURE"}}""" }
        chat.core = core
        core.activeChat = chat
        try {
            core.identify("jwt.assinado.errado")
            fail("deveria falhar")
        } catch (error: AtaliaException) {
            assertEquals("INVALID_SIGNATURE", error.code)
        }
    }

    @Test
    fun identifyTimesOutWhenWidgetIsSilent() = runTest {
        val chat = FakeChat { _, _ -> null }
        chat.core = core
        core.activeChat = chat
        try {
            core.identify("jwt.de.teste")
            fail("deveria falhar")
        } catch (error: AtaliaException) {
            assertEquals(AtaliaException.TIMEOUT, error.code)
        }
    }

    @Test
    fun brandingColorIsCached() = runTest {
        core.loadBranding()
        assertEquals("#112233", core.primaryColor)
        assertEquals("#112233", store.loadPrimaryColor())
    }
}
