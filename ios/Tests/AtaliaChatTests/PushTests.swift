import XCTest
@testable import AtaliaChat

/// Push no SDK: leitura do payload (fixture compartilhada) e registro do aparelho na sessão.
@MainActor
final class PushTests: XCTestCase {
    private let base = "/api/webchat/wct_test"
    private var core: AtaliaCore!
    private var store: MemorySessionStore!

    override func setUp() async throws {
        StubURLProtocol.reset()
        StubURLProtocol.stubs["GET \(base)"] = .init(status: 200, json: [:])
        StubURLProtocol.stubs["GET \(base)/unread"] = .init(status: 200, json: ["total": 0, "conversations": []])
        StubURLProtocol.stubs["POST \(base)/sessions"] = .init(status: 201, json: ["isNew": true])
        StubURLProtocol.stubs["POST \(base)/push/devices"] = .init(status: 200, json: ["ok": true])
        StubURLProtocol.stubs["DELETE \(base)/push/devices"] = .init(status: 204, json: nil)
        store = MemorySessionStore()
    }

    /// Cria o núcleo e espera as tarefas do `configure` (marca, não lidas, registro pendente).
    private func configure() async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        let api = APIClient(baseURL: URL(string: "https://chat.atalia.ai")!, channelToken: "wct_test", session: URLSession(configuration: configuration))
        if core == nil { core = AtaliaCore() }
        core.configure(
            channelToken: "wct_test",
            options: AtaliaOptions(),
            api: api,
            store: store,
            deviceInfo: { ["platform": "ios", "app_id": "com.loja"] }
        )
        for _ in 0..<20 { await Task.yield() }
        try? await Task.sleep(nanoseconds: 50_000_000)
        await core.pushTask?.value
    }

    private func requests(_ method: String, _ path: String) -> [URLRequest] {
        StubURLProtocol.requests.filter { $0.httpMethod == method && $0.url?.path == "\(base)\(path)" }
    }

    private func registrations() -> [(session: String?, body: [String: Any])] {
        requests("POST", "/push/devices").map { request in
            let body = request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
            return (request.value(forHTTPHeaderField: "x-session-token"), body)
        }
    }

    func testPayloadMatchesFixture() throws {
        let fixture = try Fixtures.json("push-payload.json")
        for item in try XCTUnwrap(fixture["atalia"] as? [[String: Any]]) {
            let name = item["name"] as? String ?? ""
            let payload = try XCTUnwrap(PushPayload(item["data"] as? [AnyHashable: Any]), name)
            let expect = try XCTUnwrap(item["expect"] as? [String: Any])
            XCTAssertEqual(payload.conversationId, expect["conversationId"] as? Int, name)
            XCTAssertEqual(payload.messageId, expect["messageId"] as? Int, name)
            XCTAssertEqual(payload.channelToken, expect["channelToken"] as? String, name)
        }
        for item in try XCTUnwrap(fixture["notAtalia"] as? [[String: Any]]) {
            XCTAssertNil(PushPayload(item["data"] as? [AnyHashable: Any]), item["name"] as? String ?? "")
            XCTAssertFalse(Atalia.isAtaliaNotification(item["data"] as? [AnyHashable: Any] ?? [:]))
        }
    }

    func testNumericValuesFromWebhookBackendAreAccepted() {
        let payload = PushPayload(["atalia": 1, "conversation_id": 812, "message_id": "9001"])
        XCTAssertEqual(payload?.conversationId, 812)
        XCTAssertEqual(payload?.messageId, 9001)
    }

    func testSetPushTokenCreatesSessionAndRegistersOnce() async throws {
        await configure()
        await core.setPushToken(StoredPushToken(token: " fcm-1 ", provider: "fcm", environment: nil))
        await core.registerPushTokenIfNeeded()

        let session = try XCTUnwrap(store.load()?.token)
        XCTAssertEqual(requests("POST", "/sessions").count, 1)
        let registered = registrations()
        XCTAssertEqual(registered.count, 1, "mesma sessão e mesmo token não repetem o POST")
        XCTAssertEqual(registered.first?.session, session)
        XCTAssertEqual(registered.first?.body["token"] as? String, "fcm-1")
        XCTAssertEqual(registered.first?.body["provider"] as? String, "fcm")
        XCTAssertEqual(registered.first?.body["app_id"] as? String, "com.loja")
        XCTAssertNil(registered.first?.body["environment"])
    }

    func testAPNsTokenCarriesEnvironment() async throws {
        await configure()
        await core.setPushToken(StoredPushToken(token: "abcd", provider: "apns", environment: "sandbox"))
        let body = try XCTUnwrap(registrations().first?.body)
        XCTAssertEqual(body["provider"] as? String, "apns")
        XCTAssertEqual(body["environment"] as? String, "sandbox")
    }

    func testTokenBeforeConfigureIsAppliedOnConfigure() async throws {
        core = AtaliaCore()
        await core.setPushToken(StoredPushToken(token: "fcm-early", provider: "fcm", environment: nil))
        XCTAssertTrue(registrations().isEmpty)

        await configure()

        XCTAssertEqual(store.loadPushToken()?.token, "fcm-early")
        XCTAssertEqual(registrations().first?.body["token"] as? String, "fcm-early")
    }

    func testExpiredSessionIsReplacedAndRegistrationRetried() async throws {
        store.save(StoredSession(token: "old-session", identified: true, externalId: "u1"))
        await configure()
        // A primeira tentativa recebe 401; a sessão nova registra.
        StubURLProtocol.queued["POST \(base)/push/devices"] = [.init(status: 401, json: ["code": "SESSION_EXPIRED"])]
        await core.setPushToken(StoredPushToken(token: "fcm-1", provider: "fcm", environment: nil))

        let sessions = registrations().map(\.session)
        XCTAssertEqual(sessions.first, "old-session")
        let fresh = try XCTUnwrap(store.load())
        XCTAssertNotEqual(fresh.token, "old-session")
        XCTAssertFalse(fresh.identified)
        XCTAssertEqual(sessions.last, fresh.token)
        XCTAssertFalse(core.state.isIdentified)
    }

    func testIdentifyAndLogoutRegisterTheDeviceInTheNewSession() async throws {
        store.save(StoredSession(token: "anon-1", identified: false, externalId: nil))
        StubURLProtocol.stubs["POST \(base)/identify"] = .init(status: 200, json: [
            "sessionToken": "identified-1",
            "contact": ["id": 5, "externalId": "u5"],
        ])
        StubURLProtocol.stubs["POST \(base)/logout"] = .init(status: 204, json: nil)
        await configure()
        await core.setPushToken(StoredPushToken(token: "fcm-1", provider: "fcm", environment: nil))

        _ = try await core.identify(jwt: "jwt")
        XCTAssertEqual(registrations().last?.session, "identified-1")

        await core.logout()
        let anonymous = try XCTUnwrap(store.load()?.token)
        XCTAssertNotEqual(anonymous, "identified-1")
        XCTAssertEqual(registrations().last?.session, anonymous)
        XCTAssertEqual(store.loadPushToken()?.token, "fcm-1", "o logout não apaga o token do aparelho")
    }

    func testSessionChangedFromWidgetReRegisters() async throws {
        store.save(StoredSession(token: "anon-1", identified: false, externalId: nil))
        await configure()
        await core.setPushToken(StoredPushToken(token: "fcm-1", provider: "fcm", environment: nil))

        core.handle(BridgeMessage(body: #"{"v":1,"type":"session_changed","payload":{"sessionToken":"widget-2","identified":true,"externalId":"u2"}}"#)!)
        await core.pushTask?.value

        XCTAssertEqual(registrations().map(\.session), ["anon-1", "widget-2"])
    }

    func testSameSessionDoesNotScheduleAnotherRegistration() async throws {
        store.save(StoredSession(token: "anon-1", identified: false, externalId: nil))
        await configure()
        await core.setPushToken(StoredPushToken(token: "fcm-1", provider: "fcm", environment: nil))
        let before = core.pushTask

        core.handle(BridgeMessage(body: #"{"v":1,"type":"session_changed","payload":{"sessionToken":"anon-1","identified":false}}"#)!)

        XCTAssertEqual(core.pushTask, before)
        XCTAssertEqual(registrations().count, 1)
    }

    func testClearPushTokenUnregisters() async throws {
        store.save(StoredSession(token: "anon-1", identified: false, externalId: nil))
        await configure()
        await core.setPushToken(StoredPushToken(token: "fcm-1", provider: "fcm", environment: nil))

        await core.clearPushToken()

        let request = try XCTUnwrap(requests("DELETE", "/push/devices").first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "x-session-token"), "anon-1")
        let body = try XCTUnwrap(request.httpBody.flatMap { try JSONSerialization.jsonObject(with: $0) as? [String: Any] })
        XCTAssertEqual(body["token"] as? String, "fcm-1")
        XCTAssertNil(store.loadPushToken())
    }

    func testNoTokenNoRegistration() async {
        await configure()
        await core.registerPushTokenIfNeeded()
        XCTAssertTrue(requests("POST", "/sessions").isEmpty, "sem token o SDK não cria sessão à toa")
        XCTAssertTrue(registrations().isEmpty)
    }

    func testNotificationIsSuppressedWhileChatIsOnScreen() async {
        await configure()
        XCTAssertTrue(core.shouldShowNotification())
        let chat = SilentChat()
        core.activeChat = chat
        XCTAssertFalse(core.shouldShowNotification())
    }
}

@MainActor
private final class SilentChat: ChatBridgeTarget {
    func sendCommand(_ type: String, payload: [String: Any]?) {}
}
