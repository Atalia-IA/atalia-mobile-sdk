import Foundation
import os

/// Registro do SDK (subsystem `ai.atalia.chat`), ligado por `AtaliaOptions.debug`. Aparece no
/// console do Xcode e no app Console do Mac. Serve para o time do app ver o caminho do push e do
/// chat no aparelho. Só eventos, ids e códigos: nunca texto de mensagem, JWT ou token inteiro.
enum AtaliaLog {
    static let subsystem = "ai.atalia.chat"
    static var enabled = false
    private static let logger = Logger(subsystem: subsystem, category: "AtaliaChat")

    static func info(_ message: String) {
        guard enabled else { return }
        logger.notice("\(message, privacy: .public)")
    }

    static func warn(_ message: String) {
        guard enabled else { return }
        logger.error("\(message, privacy: .public)")
    }

    /// Começo e fim do token, o bastante para casar com a lista de aparelhos do painel.
    static func mask(_ token: String) -> String {
        token.count <= 12 ? "…" : "\(token.prefix(6))…\(token.suffix(4))"
    }
}
