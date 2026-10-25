package ai.atalia.chat

import ai.atalia.chat.internal.ApiResponse
import ai.atalia.chat.internal.AtaliaApi
import ai.atalia.chat.internal.AtaliaCore
import ai.atalia.chat.internal.AtaliaStateHolder
import ai.atalia.chat.internal.BridgeMessage
import ai.atalia.chat.internal.MemorySessionStorage
import ai.atalia.chat.internal.PushPayload
import ai.atalia.chat.internal.StoredSession
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Push no SDK: leitura do payload (fixture compartilhada) e registro do aparelho na sessão. */
class PushTest {
    private class FakeApi : AtaliaApi {
        val calls = mutableListOf<String>()
        val registered = mutableListOf<Pair<String, JSONObject>>()
        var registerStatus = 200
        private var sessionCounter = 0

        override suspend fun channelConfig() = ApiResponse(200, JSONObject())

        override suspend fun createSession(sessionToken: String): ApiResponse {
            sessionCounter++
            calls += "sessions"
            return ApiResponse(201, JSONObject())
        }

        override suspend fun identify(sessionToken: String, jwt: String) = ApiResponse(
            200,
            JSONObject().put("sessionToken", "identified-1").put("contact", JSONObject().put("id", 5).put("externalId", "u5")),
        )

        override suspend fun logout(sessionToken: String) = ApiResponse(204, null)

        override suspend fun unread(sessionToken: String) = ApiResponse(200, JSONObject().put("total", 0))

        override suspend fun registerPushDevice(sessionToken: String, device: JSONObject): ApiResponse {
            calls += "register:$sessionToken"
            registered += sessionToken to device
            val status = registerStatus
            registerStatus = 200
            return ApiResponse(status, if (status == 401) JSONObject().put("code", "SESSION_EXPIRED") else JSONObject().put("ok", true))
        }

        override suspend fun unregisterPushDevice(sessionToken: String, pushToken: String): ApiResponse {
            calls += "unregister:$sessionToken:$pushToken"
            return ApiResponse(204, null)
        }
    }

    private val api = FakeApi()
    private val store = MemorySessionStorage()
    private val background = mutableListOf<suspend () -> Unit>()
    private var cleared = 0
    private val core = AtaliaCore(
        channelToken = "wct_test",
        options = AtaliaOptions(),
        api = api,
        store = store,
        state = AtaliaStateHolder(),
        deviceInfo = { JSONObject().put("platform", "android").put("app_id", "com.loja") },
        launchInBackground = { background += it },
        clearNotifications = { cleared++ },
    )

    @Test
    fun payloadMatchesFixture() {
        val fixture = Fixtures.json("push-payload.json")
        val atalia = fixture.getJSONArray("atalia")
        for (i in 0 until atalia.length()) {
            val item = atalia.getJSONObject(i)
            val payload = PushPayload.parse(toMap(item.getJSONObject("data")))!!
            val expect = item.getJSONObject("expect")
            assertEquals(item.getString("name"), expect.opt("conversationId").takeIf { it != JSONObject.NULL }?.let { (it as Number).toLong() }, payload.conversationId)
            assertEquals(item.getString("name"), expect.getString("channelToken"), payload.channelToken)
        }
        val others = fixture.getJSONArray("notAtalia")
        for (i in 0 until others.length()) {
            assertNull(PushPayload.parse(toMap(others.getJSONObject(i).getJSONObject("data"))))
        }
    }

    @Test
    fun setPushTokenCreatesSessionAndRegistersOnce() = runTest {
        core.setPushToken("fcm-1")
        core.registerPushTokenIfNeeded()

        val session = store.load()!!.token
        assertEquals(listOf("sessions", "register:$session"), api.calls)
        val body = api.registered.single().second
        assertEquals("fcm-1", body.getString("token"))
        assertEquals("fcm", body.getString("provider"))
        assertEquals("com.loja", body.getString("app_id"))
    }

    @Test
    fun expiredSessionIsReplacedAndRegistrationRetried() = runTest {
        store.save(StoredSession("old-session", identified = true, externalId = "u1"))
        api.registerStatus = 401
        core.setPushToken("fcm-1")

        assertEquals("register:old-session", api.calls.first())
        val newSession = store.load()!!.token
        assertTrue(newSession != "old-session")
        assertEquals("register:$newSession", api.calls.last())
    }

    @Test
    fun identifyAndLogoutRegisterTheDeviceInTheNewSession() = runTest {
        store.save(StoredSession("anon-1", identified = false, externalId = null))
        core.setPushToken("fcm-1")

        core.identify("jwt")
        assertEquals("register:identified-1", api.calls.last())

        core.logout()
        val anonymous = store.load()!!.token
        assertTrue(anonymous != "identified-1")
        assertEquals("register:$anonymous", api.calls.last())
    }

    @Test
    fun sessionChangedFromWidgetReRegistersInBackground() = runTest {
        store.save(StoredSession("anon-1", identified = false, externalId = null))
        core.setPushToken("fcm-1")
        core.handle(BridgeMessage.parse("""{"v":1,"type":"session_changed","payload":{"sessionToken":"widget-2","identified":true,"externalId":"u2"}}""")!!)

        assertEquals(1, background.size)
        background.single().invoke()
        assertEquals("register:widget-2", api.calls.last())
    }

    @Test
    fun sameSessionDoesNotScheduleAnotherRegistration() = runTest {
        store.save(StoredSession("anon-1", identified = false, externalId = null))
        core.setPushToken("fcm-1")
        core.handle(BridgeMessage.parse("""{"v":1,"type":"session_changed","payload":{"sessionToken":"anon-1","identified":false}}""")!!)
        assertTrue(background.isEmpty())
    }

    @Test
    fun clearPushTokenUnregisters() = runTest {
        store.save(StoredSession("anon-1", identified = false, externalId = null))
        core.setPushToken("fcm-1")
        core.clearPushToken()
        assertEquals("unregister:anon-1:fcm-1", api.calls.last())
        assertNull(store.loadPushToken())
    }

    @Test
    fun noTokenNoRegistration() = runTest {
        core.registerPushTokenIfNeeded()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun readNotificationsLeaveTheTray() = runTest {
        store.save(StoredSession("anon-1", identified = false, externalId = null))
        core.setPushToken("fcm-1")
        core.handle(BridgeMessage.parse("""{"v":1,"type":"unread_changed","payload":{"count":2}}""")!!)
        assertEquals("ainda há não lidas", 0, cleared)
        core.handle(BridgeMessage.parse("""{"v":1,"type":"unread_changed","payload":{"count":0}}""")!!)
        assertEquals(1, cleared)
        core.refreshUnread()
        assertEquals("a consulta também limpa, mesmo sem mudança", 2, cleared)
    }

    @Test
    fun noPushNoTrayCleanup() = runTest {
        store.save(StoredSession("anon-1", identified = false, externalId = null))
        core.refreshUnread()
        assertEquals(0, cleared)
    }

    @Test
    fun notificationIsSuppressedWhileChatIsOnScreen() {
        assertTrue(core.shouldShowNotification())
        val chat = object : ai.atalia.chat.internal.ChatBridgeTarget {
            override fun sendCommand(type: String, payload: JSONObject?) {}
        }
        core.activeChat = chat
        assertFalse(core.shouldShowNotification())
    }

    private fun toMap(json: JSONObject): Map<String, String?> = json.keys().asSequence().associateWith { json.optString(it) }
}
