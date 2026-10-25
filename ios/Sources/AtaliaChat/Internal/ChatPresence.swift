import Foundation

/// "O chat está na tela?", legível de qualquer thread. Os delegates de notificação do iOS podem
/// rodar fora da main thread, e a resposta deles não pode esperar o `AtaliaCore` (main actor).
/// A referência é fraca: tela do chat que sumiu sem avisar conta como fechada.
final class ChatPresence: @unchecked Sendable {
    static let shared = ChatPresence()

    private let lock = NSLock()
    private weak var chat: AnyObject?

    func set(_ chat: AnyObject?) {
        lock.lock()
        self.chat = chat
        lock.unlock()
    }

    var isVisible: Bool {
        lock.lock()
        defer { lock.unlock() }
        return chat != nil
    }
}
