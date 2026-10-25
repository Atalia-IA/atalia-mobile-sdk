import ExpoModulesCore
import UIKit

/// Opções de `configure`, como chegam do JavaScript.
struct ConfigureRecord: Record {
    @Field var baseUrl: String?
    @Field var customDomain: Bool?
    @Field var closeButton: Bool?
    @Field var presentationStyle: String?
    @Field var debug: Bool?
    @Field var appBadge: Bool?
}

/// Contato identificado, como vai para o JavaScript.
struct ContactRecord: Record {
    @Field var externalId: String = ""
    @Field var contactId: Int = 0
    @Field var name: String?
}

/// Opções do botão flutuante, como chegam do JavaScript.
struct LauncherRecord: Record {
    @Field var side: String?
    @Field var horizontalMargin: Double?
    @Field var bottomMargin: Double?
    @Field var text: String?
    @Field var subtitle: String?
    @Field var color: String?
    @Field var collapse: String?
    @Field var collapseAfter: Double?
    @Field var draggable: Bool?
    @Field var dockable: Bool?
    @Field var preloadChat: Bool?
}

/// Ponte entre o JavaScript e o SDK iOS (o mesmo do Swift Package Manager, compilado neste pod).
/// O SDK vive no main actor; as funções saltam para ele.
public class AtaliaChatModule: Module {
    private var listener: AtaliaListenerToken?
    private var launcher: AtaliaLauncherView?

    public func definition() -> ModuleDefinition {
        Name("AtaliaChat")

        Events("onEvent")

        OnStartObserving("onEvent") {
            Task { @MainActor in
                guard self.listener == nil else { return }
                self.listener = Atalia.addListener { [weak self] event in
                    self?.sendEvent("onEvent", Self.eventBody(event))
                }
            }
        }

        OnStopObserving("onEvent") {
            Task { @MainActor in
                if let listener = self.listener { Atalia.removeListener(listener) }
                self.listener = nil
            }
        }

        Function("configure") { (channelToken: String, record: ConfigureRecord) in
            var options = AtaliaOptions()
            if let base = record.baseUrl, let url = URL(string: base) { options.baseURL = url }
            if let customDomain = record.customDomain { options.customDomain = customDomain }
            if let closeButton = record.closeButton { options.closeButton = closeButton }
            if record.presentationStyle == "fullScreen" { options.presentationStyle = .fullScreen }
            if let debug = record.debug { options.debug = debug }
            if let appBadge = record.appBadge { options.appBadge = appBadge }
            Task { @MainActor in Atalia.configure(channelToken: channelToken, options: options) }
        }

        Function("open") {
            Task { @MainActor in Atalia.present() }
        }

        Function("openConversation") { (conversationId: Int) in
            Task { @MainActor in Atalia.present(conversationId: conversationId) }
        }

        Function("close") {
            Task { @MainActor in Atalia.dismiss() }
        }

        AsyncFunction("identify") { (jwt: String) async throws -> ContactRecord in
            do {
                let contact = try await Atalia.identify(jwt: jwt)
                var record = ContactRecord()
                record.externalId = contact.externalId
                record.contactId = contact.contactId
                record.name = contact.name
                return record
            } catch let error as AtaliaError {
                throw Exception(name: "AtaliaError", description: error.code, code: error.code)
            }
        }

        AsyncFunction("logout") { () async in
            await Atalia.logout()
        }

        AsyncFunction("refreshUnread") { () async throws -> Int in
            do {
                return try await Atalia.refreshUnread()
            } catch let error as AtaliaError {
                throw Exception(name: "AtaliaError", description: error.code, code: error.code)
            }
        }

        AsyncFunction("getState") { () async -> [String: Any] in
            await MainActor.run {
                ["unreadCount": Atalia.unreadCount, "isIdentified": Atalia.isIdentified, "isChatOpen": Atalia.state.isChatOpen]
            }
        }

        // Push

        Function("setPushToken") { (token: String) in
            Atalia.setPushToken(token)
        }

        Function("setAPNsToken") { (hex: String, environment: String) in
            guard let data = Self.data(fromHex: hex) else { return }
            Atalia.setAPNsToken(data, environment: environment == "sandbox" ? .sandbox : .production)
        }

        Function("clearPushToken") {
            Atalia.clearPushToken()
        }

        /// iOS não mostra notificação pelo SDK com o app aberto (quem mostra é o sistema, pelo
        /// handler da biblioteca de push). Aqui só atualiza as não lidas e diz se é da Atalia.
        AsyncFunction("handleNotification") { (data: [String: String], _: String?, _: String?) async -> Bool in
            guard Atalia.isAtaliaNotification(data) else { return false }
            _ = try? await Atalia.refreshUnread()
            return true
        }

        Function("handleNotificationTap") { (data: [String: String]) -> Bool in
            Atalia.handleNotificationTap(data)
        }

        // Botão flutuante: vai para a janela, por cima de todas as telas do app.

        Function("showLauncher") { (record: LauncherRecord) in
            let options = Self.launcherOptions(record)
            Task { @MainActor in
                self.launcher?.removeFromSuperview()
                guard let window = Self.keyWindow() else { return }
                self.launcher = AtaliaLauncherView.attach(to: window, options: options)
            }
        }

        Function("hideLauncher") {
            Task { @MainActor in
                self.launcher?.removeFromSuperview()
                self.launcher = nil
            }
        }
    }

    private static func launcherOptions(_ record: LauncherRecord) -> AtaliaLauncherOptions {
        var options = AtaliaLauncherOptions()
        switch record.side {
        case "start": options.side = .leading
        case "end": options.side = .trailing
        default: break
        }
        if let margin = record.horizontalMargin { options.horizontalPadding = CGFloat(margin) }
        if let margin = record.bottomMargin { options.bottomPadding = CGFloat(margin) }
        options.text = record.text
        options.subtitle = record.subtitle
        options.color = UIColor(ataliaHex: record.color)
        switch record.collapse {
        case "edge": options.collapse = .edge
        case "never": options.collapse = .never
        default: options.collapse = .circle
        }
        if let after = record.collapseAfter { options.collapseAfter = after / 1000 }
        options.draggable = record.draggable ?? false
        if let dockable = record.dockable { options.dockable = dockable }
        if let preloadChat = record.preloadChat { options.preloadChat = preloadChat }
        return options
    }

    @MainActor
    private static func keyWindow() -> UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
    }

    private static func data(fromHex hex: String) -> Data? {
        let clean = hex.filter { !$0.isWhitespace && $0 != "<" && $0 != ">" }
        guard clean.count % 2 == 0, !clean.isEmpty else { return nil }
        var data = Data(capacity: clean.count / 2)
        var index = clean.startIndex
        while index < clean.endIndex {
            let next = clean.index(index, offsetBy: 2)
            guard let byte = UInt8(clean[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }

    private static func eventBody(_ event: AtaliaEvent) -> [String: Any?] {
        switch event {
        case .ready: return ["type": "ready"]
        case .opened: return ["type": "opened"]
        case .closed: return ["type": "closed"]
        case let .identified(externalId, contactId): return ["type": "identified", "externalId": externalId, "contactId": contactId]
        case .loggedOut: return ["type": "loggedOut"]
        case let .conversationStarted(id): return ["type": "conversationStarted", "conversationId": id]
        case let .conversationClosed(id): return ["type": "conversationClosed", "conversationId": id]
        case let .messageReceived(conversationId, messageId):
            return ["type": "messageReceived", "conversationId": conversationId, "messageId": messageId]
        case let .unreadChanged(count): return ["type": "unreadChanged", "count": count]
        case let .error(code, command): return ["type": "error", "code": code, "command": command]
        }
    }
}
