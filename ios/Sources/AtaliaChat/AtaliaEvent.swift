import Foundation

/// Eventos do chat entregues aos listeners de `Atalia.addListener(_:)`.
public enum AtaliaEvent: Equatable {
    /// O chat terminou de carregar.
    case ready
    /// A tela do chat apareceu.
    case opened
    /// A tela do chat foi fechada.
    case closed
    /// O visitante foi identificado (`identify`).
    case identified(externalId: String, contactId: Int)
    case loggedOut
    case conversationStarted(conversationId: Int)
    case conversationClosed(conversationId: Int)
    /// Chegou mensagem do atendente, da IA ou do sistema. Só ids: o texto nunca sai do chat.
    case messageReceived(conversationId: Int, messageId: Int)
    case unreadChanged(count: Int)
    /// Falha do chat (`INIT_FAILED`) ou de um comando (`command` = "identify", "open_conversation"...).
    case error(code: String, command: String?)
}

/// Contato da Atalia vinculado ao usuário identificado.
public struct AtaliaContact: Equatable {
    public let externalId: String
    public let contactId: Int
    public let name: String?
}

/// Erro das chamadas do SDK. `code` é o mesmo código devolvido pela Atalia
/// (ex.: `EXPIRED`, `INVALID_SIGNATURE`, `NO_SECRET_CONFIGURED`).
public struct AtaliaError: Error, Equatable, CustomStringConvertible {
    public let code: String

    public init(code: String) {
        self.code = code
    }

    public var description: String { "AtaliaError(\(code))" }

    /// `Atalia.configure` ainda não foi chamado.
    public static let notConfigured = AtaliaError(code: "NOT_CONFIGURED")
    /// O chat não respondeu a tempo a um comando.
    public static let timeout = AtaliaError(code: "TIMEOUT")
}

/// Devolvido por `Atalia.addListener(_:)`; passe para `Atalia.removeListener(_:)`.
public struct AtaliaListenerToken: Hashable {
    let id = UUID()
}
