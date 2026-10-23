import XCTest
@testable import AtaliaChat

/// Rede simulada: cada teste registra as respostas por caminho e lê o que foi pedido.
final class StubURLProtocol: URLProtocol {
    struct Stub {
        let status: Int
        let json: [String: Any]?
    }

    nonisolated(unsafe) static var stubs: [String: Stub] = [:]
    nonisolated(unsafe) static var requests: [URLRequest] = []

    static func reset() {
        stubs = [:]
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var recorded = request
        if recorded.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let read = stream.read(&buffer, maxLength: buffer.count)
                if read <= 0 { break }
                data.append(buffer, count: read)
            }
            stream.close()
            recorded.httpBody = data
        }
        Self.requests.append(recorded)

        let key = "\(request.httpMethod ?? "GET") \(request.url?.path ?? "")"
        let stub = Self.stubs[key] ?? Stub(status: 404, json: ["error": "sem stub para \(key)"])
        let response = HTTPURLResponse(url: request.url!, statusCode: stub.status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if let json = stub.json, let data = try? JSONSerialization.data(withJSONObject: json) {
            client?.urlProtocol(self, didLoad: data)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@MainActor
final class AtaliaCoreTests: XCTestCase {
    private let base = "/api/webchat/wct_test"
    private var core: AtaliaCore!
    private var store: MemorySessionStore!
    private var events: [AtaliaEvent] = []

    override func setUp() async throws {
        StubURLProtocol.reset()
        // A configuração dispara a busca da marca e das não lidas; deixa as duas respondidas.
        StubURLProtocol.stubs["GET \(base)"] = .init(status: 200, json: ["branding": ["primary_color": "#112233"]])
        StubURLProtocol.stubs["GET \(base)/unread"] = .init(status: 200, json: ["total": 0, "conversations": []])
        store = MemorySessionStore()
        events = []
        core = makeCore(store: store)
        _ = core.addListener { [weak self] event in self?.events.append(event) }
        await settle()
    }

    private func makeCore(store: SessionStoring) -> AtaliaCore {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        let api = APIClient(baseURL: URL(string: "https://chat.atalia.ai")!, channelToken: "wct_test", session: URLSession(configuration: configuration))
        let core = AtaliaCore()
        core.configure(channelToken: "wct_test", options: AtaliaOptions(), api: api, store: store)
        return core
    }

    /// Deixa terminar as tarefas que `configure` dispara.
    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
        try? await Task.sleep(nanoseconds: 50_000_000)
    }

    private func requests(_ method: String, _ path: String) -> [URLRequest] {
        StubURLProtocol.requests.filter { $0.httpMethod == method && $0.url?.path == "\(base)\(path)" }
    }

    func testEnsureSessionCreatesOnceAndStores() async throws {
        StubURLProtocol.stubs["POST \(base)/sessions"] = .init(status: 201, json: ["sessionId": 1, "customerId": NSNull(), "isNew": true])

        async let first = core.ensureSession()
        async let second = core.ensureSession()
        let (a, b) = try await (first, second)

        XCTAssertEqual(a, b, "chamadas simultâneas compartilham a mesma sessão")
        XCTAssertEqual(requests("POST", "/sessions").count, 1)
        XCTAssertEqual(store.load(), StoredSession(token: a, identified: false, externalId: nil))
        let body = try XCTUnwrap(requests("POST", "/sessions").first?.httpBody)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["sessionToken"] as? String, a)
    }

    func testEnsureSessionReusesStoredToken() async throws {
        store.save(StoredSession(token: "existing-token-1", identified: true, externalId: "u1"))
        let token = try await core.ensureSession()
        XCTAssertEqual(token, "existing-token-1")
        XCTAssertTrue(requests("POST", "/sessions").isEmpty)
    }

    func testIdentifyWithoutChatStoresNewSession() async throws {
        store.save(StoredSession(token: "anon-token-1", identified: false, externalId: nil))
        StubURLProtocol.stubs["POST \(base)/identify"] = .init(status: 200, json: [
            "sessionToken": "identified-token-2",
            "expiresAt": "2026-11-22T10:00:00.000Z",
            "contact": ["id": 55, "externalId": "user-42", "name": "Ana"],
            "conversations": [],
        ])
        StubURLProtocol.stubs["GET \(base)/unread"] = .init(status: 200, json: ["total": 2, "conversations": [["id": 101, "unread_count": 2]]])

        let contact = try await core.identify(jwt: "jwt.de.teste")

        XCTAssertEqual(contact, AtaliaContact(externalId: "user-42", contactId: 55, name: "Ana"))
        XCTAssertEqual(store.load(), StoredSession(token: "identified-token-2", identified: true, externalId: "user-42"))
        XCTAssertTrue(core.state.isIdentified)
        XCTAssertEqual(core.state.unreadCount, 2)
        XCTAssertTrue(events.contains(.identified(externalId: "user-42", contactId: 55)))
        let request = try XCTUnwrap(requests("POST", "/identify").first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "x-session-token"), "anon-token-1")
    }

    func testIdentifyPropagatesServerCode() async throws {
        store.save(StoredSession(token: "anon-token-1", identified: false, externalId: nil))
        StubURLProtocol.stubs["POST \(base)/identify"] = .init(status: 401, json: ["code": "EXPIRED"])

        do {
            _ = try await core.identify(jwt: "jwt.vencido")
            XCTFail("deveria falhar")
        } catch let error as AtaliaError {
            XCTAssertEqual(error.code, "EXPIRED")
        }
        XCTAssertEqual(store.load()?.token, "anon-token-1", "falha não troca a sessão")
    }

    func testRefreshUnreadUpdatesStateAndEmitsOnce() async throws {
        store.save(StoredSession(token: "token-1234", identified: false, externalId: nil))
        StubURLProtocol.stubs["GET \(base)/unread"] = .init(status: 200, json: ["total": 3, "conversations": []])

        let first = try await core.refreshUnread()
        _ = try await core.refreshUnread()

        XCTAssertEqual(first, 3)
        XCTAssertEqual(core.state.unreadCount, 3)
        XCTAssertEqual(events.filter { $0 == .unreadChanged(count: 3) }.count, 1, "mesmo valor não reemite")
        XCTAssertEqual(requests("GET", "/unread").last?.value(forHTTPHeaderField: "x-session-token"), "token-1234")
    }

    func testLogoutWithoutChatClearsSession() async throws {
        store.save(StoredSession(token: "identified-token-2", identified: true, externalId: "user-42"))
        StubURLProtocol.stubs["POST \(base)/logout"] = .init(status: 204, json: nil)

        await core.logout()

        XCTAssertNil(store.load())
        XCTAssertFalse(core.state.isIdentified)
        XCTAssertTrue(events.contains(.loggedOut))
        XCTAssertEqual(requests("POST", "/logout").first?.value(forHTTPHeaderField: "x-session-token"), "identified-token-2")
    }

    func testSessionChangedFromWidgetIsStored() throws {
        let message = try XCTUnwrap(BridgeMessage(body: #"{"v":1,"type":"session_changed","payload":{"sessionToken":"from-widget-1","identified":true,"externalId":"user-9"}}"#))
        core.handle(message)
        XCTAssertEqual(store.load(), StoredSession(token: "from-widget-1", identified: true, externalId: "user-9"))
        XCTAssertTrue(core.state.isIdentified)
        XCTAssertTrue(events.isEmpty, "session_changed não é evento público")
    }

    func testIdentifyWithOpenChatGoesThroughWidget() async throws {
        let chat = FakeChat()
        core.activeChat = chat
        chat.onCommand = { [weak core] type, _ in
            guard type == "identify" else { return }
            core?.handle(BridgeMessage(body: #"{"v":1,"type":"identified","payload":{"externalId":"user-7","contactId":70}}"#)!)
        }

        let contact = try await core.identify(jwt: "jwt.de.teste")

        XCTAssertEqual(contact.externalId, "user-7")
        XCTAssertEqual(chat.commands.map(\.type), ["identify"])
        XCTAssertEqual(chat.commands.first?.payload?["jwt"] as? String, "jwt.de.teste")
        XCTAssertTrue(requests("POST", "/identify").isEmpty, "com o chat aberto quem chama a API é o widget")
    }

    func testIdentifyErrorFromWidgetIsThrown() async throws {
        let chat = FakeChat()
        core.activeChat = chat
        chat.onCommand = { [weak core] _, _ in
            core?.handle(BridgeMessage(body: #"{"v":1,"type":"error","payload":{"command":"identify","code":"INVALID_SIGNATURE"}}"#)!)
        }

        do {
            _ = try await core.identify(jwt: "jwt.assinado.errado")
            XCTFail("deveria falhar")
        } catch let error as AtaliaError {
            XCTAssertEqual(error.code, "INVALID_SIGNATURE")
        }
    }

    func testNotConfigured() async {
        let core = AtaliaCore()
        do {
            _ = try await core.refreshUnread()
            XCTFail("deveria falhar")
        } catch let error as AtaliaError {
            XCTAssertEqual(error, .notConfigured)
        } catch {
            XCTFail("erro inesperado: \(error)")
        }
    }

    func testBrandingColorIsLoaded() {
        XCTAssertEqual(core.primaryColorHex, "#112233")
    }
}

@MainActor
private final class FakeChat: ChatBridgeTarget {
    var commands: [(type: String, payload: [String: Any]?)] = []
    var onCommand: ((String, [String: Any]?) -> Void)?

    func sendCommand(_ type: String, payload: [String: Any]?) {
        commands.append((type, payload))
        // O widget responde de forma assíncrona, depois do comando.
        let handler = onCommand
        Task { @MainActor in handler?(type, payload) }
    }
}
