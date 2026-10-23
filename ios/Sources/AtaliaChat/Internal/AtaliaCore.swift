import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Quem recebe comandos nativo → web: a tela do chat aberta no momento.
@MainActor
protocol ChatBridgeTarget: AnyObject {
    func sendCommand(_ type: String, payload: [String: Any]?)
}

/// Estado e regras do SDK. A fachada pública é `Atalia`; os testes criam instâncias próprias.
@MainActor
final class AtaliaCore {
    static let shared = AtaliaCore()

    struct Configuration {
        let channelToken: String
        let options: AtaliaOptions
        let api: APIClient
        let store: SessionStoring
    }

    private(set) var configuration: Configuration?
    let state = AtaliaState()
    weak var activeChat: ChatBridgeTarget?
    /// Cor primária do canal (cabeçalho do chat), para a barra de status combinar antes de a página carregar.
    private(set) var primaryColorHex: String?

    private var listeners: [AtaliaListenerToken: (AtaliaEvent) -> Void] = [:]
    private var waiters: [EventWaiter] = []
    private var sessionTask: Task<String, Error>?
    #if canImport(UIKit)
    private var foregroundObserver: NSObjectProtocol?
    #endif

    func configure(channelToken: String, options: AtaliaOptions, api: APIClient? = nil, store: SessionStoring? = nil) {
        let api = api ?? APIClient(baseURL: options.baseURL, channelToken: channelToken)
        let store = store ?? KeychainSessionStore(baseURL: options.baseURL, channelToken: channelToken)
        configuration = Configuration(channelToken: channelToken, options: options, api: api, store: store)
        state.isIdentified = store.load()?.identified ?? false
        observeForeground()
        Task { [weak self] in
            await self?.loadChannelBranding()
            _ = try? await self?.refreshUnread()
        }
    }

    func requireConfiguration() throws -> Configuration {
        guard let configuration else { throw AtaliaError.notConfigured }
        return configuration
    }

    // MARK: Sessão

    /// Token da sessão atual, criando uma anônima na primeira vez. Chamadas simultâneas
    /// compartilham a mesma criação.
    func ensureSession() async throws -> String {
        let config = try requireConfiguration()
        if let existing = config.store.load()?.token { return existing }
        if let sessionTask { return try await sessionTask.value }
        let task = Task { () throws -> String in
            let token = UUID().uuidString.lowercased()
            let response = try await config.api.createSession(token)
            guard response.isSuccess else { throw AtaliaError(code: response.code ?? "SESSION_FAILED") }
            config.store.save(StoredSession(token: token, identified: false, externalId: nil))
            return token
        }
        sessionTask = task
        defer { sessionTask = nil }
        return try await task.value
    }

    var storedSessionToken: String? {
        configuration?.store.load()?.token
    }

    // MARK: Identidade

    func identify(jwt: String) async throws -> AtaliaContact {
        let config = try requireConfiguration()
        guard !jwt.isEmpty else { throw AtaliaError(code: "INVALID_TOKEN") }

        // Chat aberto: quem identifica é o widget (ele recarrega a conversa no lugar e avisa a
        // sessão nova por session_changed). A resposta chega como evento.
        if let chat = activeChat {
            chat.sendCommand("identify", payload: ["jwt": jwt])
            let event = try await waitForEvent(timeout: 20) { event in
                switch event {
                case .identified: return true
                case let .error(_, command): return command == "identify"
                default: return false
                }
            }
            switch event {
            case let .identified(externalId, contactId):
                return AtaliaContact(externalId: externalId, contactId: contactId, name: nil)
            case let .error(code, _):
                throw AtaliaError(code: code)
            default:
                throw AtaliaError(code: "INVALID_TOKEN")
            }
        }

        let token = try await ensureSession()
        let response = try await config.api.identify(sessionToken: token, jwt: jwt)
        guard response.isSuccess,
              let json = response.json,
              let newToken = json["sessionToken"] as? String,
              let contact = json["contact"] as? [String: Any],
              let externalId = contact["externalId"] as? String,
              let contactId = contact["id"] as? Int
        else {
            throw AtaliaError(code: response.code ?? "INVALID_TOKEN")
        }
        config.store.save(StoredSession(token: newToken, identified: true, externalId: externalId))
        state.isIdentified = true
        emit(.identified(externalId: externalId, contactId: contactId))
        _ = try? await refreshUnread()
        return AtaliaContact(externalId: externalId, contactId: contactId, name: contact["name"] as? String)
    }

    func logout() async {
        guard let config = configuration else { return }
        if let chat = activeChat {
            chat.sendCommand("logout", payload: nil)
            _ = try? await waitForEvent(timeout: 15) { event in
                if case .loggedOut = event { return true }
                return false
            }
            return
        }
        if let token = config.store.load()?.token {
            _ = try? await config.api.logout(sessionToken: token)
        }
        // A próxima abertura cria uma sessão anônima nova: o histórico do usuário anterior
        // não aparece para quem usar o aparelho depois.
        config.store.clear()
        state.isIdentified = false
        setUnread(0)
        emit(.loggedOut)
    }

    // MARK: Não lidas

    @discardableResult
    func refreshUnread() async throws -> Int {
        let config = try requireConfiguration()
        guard let token = config.store.load()?.token else {
            setUnread(0)
            return 0
        }
        let response = try await config.api.unread(sessionToken: token)
        guard response.isSuccess else { throw AtaliaError(code: response.code ?? "UNREAD_FAILED") }
        let total = response.json?["total"] as? Int ?? 0
        setUnread(total)
        return total
    }

    private func setUnread(_ count: Int) {
        guard state.unreadCount != count else { return }
        state.unreadCount = count
        emit(.unreadChanged(count: count))
    }

    // MARK: Bridge

    /// Mensagem vinda do widget (já validada pela tela do chat: frame principal e origem da Atalia).
    func handle(_ message: BridgeMessage) {
        switch message.type {
        case "session_changed":
            if let token = message.string("sessionToken") {
                let identified = message.bool("identified") ?? false
                configuration?.store.save(StoredSession(token: token, identified: identified, externalId: message.string("externalId")))
                state.isIdentified = identified
            }
        case "unread_changed":
            if let count = message.int("count") { state.unreadCount = count }
        case "identified":
            state.isIdentified = true
        case "logout":
            state.isIdentified = false
        default:
            break
        }
        if let event = message.publicEvent {
            emit(event)
        }
    }

    // MARK: Eventos

    func addListener(_ listener: @escaping (AtaliaEvent) -> Void) -> AtaliaListenerToken {
        let token = AtaliaListenerToken()
        listeners[token] = listener
        return token
    }

    func removeListener(_ token: AtaliaListenerToken) {
        listeners[token] = nil
    }

    func emit(_ event: AtaliaEvent) {
        for listener in listeners.values {
            listener(event)
        }
        for waiter in waiters where waiter.matches(event) {
            resolve(waiter, with: .success(event))
        }
    }

    private final class EventWaiter {
        let matches: (AtaliaEvent) -> Bool
        var continuation: CheckedContinuation<AtaliaEvent, Error>?

        init(matches: @escaping (AtaliaEvent) -> Bool) {
            self.matches = matches
        }
    }

    private func waitForEvent(timeout: TimeInterval, where matches: @escaping (AtaliaEvent) -> Bool) async throws -> AtaliaEvent {
        let waiter = EventWaiter(matches: matches)
        return try await withCheckedThrowingContinuation { continuation in
            waiter.continuation = continuation
            waiters.append(waiter)
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                self?.resolve(waiter, with: .failure(AtaliaError.timeout))
            }
        }
    }

    private func resolve(_ waiter: EventWaiter, with result: Result<AtaliaEvent, Error>) {
        guard let continuation = waiter.continuation else { return }
        waiter.continuation = nil
        waiters.removeAll { $0 === waiter }
        continuation.resume(with: result)
    }

    // MARK: Canal

    private func loadChannelBranding() async {
        guard let config = configuration,
              let response = try? await config.api.channelConfig(),
              response.isSuccess,
              let branding = response.json?["branding"] as? [String: Any],
              let color = branding["primary_color"] as? String
        else { return }
        primaryColorHex = color
    }

    private func observeForeground() {
        #if canImport(UIKit)
        guard foregroundObserver == nil else { return }
        // App voltou: o badge pode ter mudado (mensagem chegou com o app fechado).
        // Com o chat aberto quem atualiza é o próprio widget.
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.activeChat == nil else { return }
                _ = try? await self.refreshUnread()
            }
        }
        #endif
    }
}
