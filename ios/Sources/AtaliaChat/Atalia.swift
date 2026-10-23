import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Ponto de entrada do SDK do chat da Atalia.
///
/// ```swift
/// Atalia.configure(channelToken: "wct_...")
/// Atalia.present()
/// ```
@MainActor
public enum Atalia {
    public nonisolated static let sdkVersion = "0.1.0"

    /// Chame uma vez, no início do app (ex.: `application(_:didFinishLaunchingWithOptions:)`).
    /// O token do canal fica em Configurações › Canais › Webchat, no painel da Atalia.
    public static func configure(channelToken: String, options: AtaliaOptions = AtaliaOptions()) {
        AtaliaCore.shared.configure(channelToken: channelToken, options: options)
    }

    /// Estado observável (não lidas, identificado) para SwiftUI.
    public static var state: AtaliaState { AtaliaCore.shared.state }

    public static var unreadCount: Int { AtaliaCore.shared.state.unreadCount }

    public static var isIdentified: Bool { AtaliaCore.shared.state.isIdentified }

    /// Vincula o visitante ao usuário logado no app. O JWT é assinado pelo SEU backend com o
    /// segredo do canal (nunca no app) e vale no máximo 1 hora. Gere um novo a cada chamada.
    @discardableResult
    public static func identify(jwt: String) async throws -> AtaliaContact {
        try await AtaliaCore.shared.identify(jwt: jwt)
    }

    /// Encerra a sessão do usuário identificado. Chame no logout do app.
    public static func logout() async {
        await AtaliaCore.shared.logout()
    }

    /// Consulta as não lidas na Atalia e atualiza `state.unreadCount`.
    @discardableResult
    public static func refreshUnread() async throws -> Int {
        try await AtaliaCore.shared.refreshUnread()
    }

    @discardableResult
    public static func addListener(_ listener: @escaping (AtaliaEvent) -> Void) -> AtaliaListenerToken {
        AtaliaCore.shared.addListener(listener)
    }

    public static func removeListener(_ token: AtaliaListenerToken) {
        AtaliaCore.shared.removeListener(token)
    }

    #if canImport(UIKit)
    /// Decide como abrir links que o visitante toca no chat. Devolva `true` se o app tratou;
    /// `false` (ou nil) abre pelo sistema (Safari, discador, WhatsApp).
    public static var urlHandler: ((URL) -> Bool)?

    /// Abre o chat por cima da tela atual. Com `conversationId`, abre direto nessa conversa
    /// (toque numa notificação). Se o chat já estiver aberto, só troca de conversa.
    public static func present(from presenter: UIViewController? = nil, conversationId: Int? = nil, animated: Bool = true) {
        guard let config = AtaliaCore.shared.configuration else {
            assertionFailure("Atalia.configure(channelToken:) não foi chamado")
            return
        }
        if let active = AtaliaCore.shared.activeChat as? AtaliaChatViewController {
            if let conversationId { active.openConversation(conversationId) }
            return
        }
        guard let host = presenter ?? topViewController() else { return }
        let chat = AtaliaChatViewController(conversationId: conversationId)
        chat.modalPresentationStyle = config.options.presentationStyle == .pageSheet ? .pageSheet : .fullScreen
        chat.onClose = { [weak chat] in chat?.dismiss(animated: true) }
        host.present(chat, animated: animated)
    }

    /// Fecha o chat aberto por `present(from:)`.
    public static func dismiss(animated: Bool = true) {
        (AtaliaCore.shared.activeChat as? AtaliaChatViewController)?.close(animated: animated)
    }

    private static func topViewController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
        var top = window?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
    #endif
}
