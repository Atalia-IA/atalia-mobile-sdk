#if canImport(UIKit)
import UIKit

/// Chat pré-carregado enquanto há botão flutuante na tela: a folha abre com o chat pronto, sem
/// esperar a página. Um só por app. A página nasce em segundo plano (sem conexão, sem presença,
/// sem marcar lidas), então a Atalia segue mandando push e nada é lido escondido.
@MainActor
enum ChatPrewarm {
    /// Espera o app assentar antes de carregar, para não disputar a abertura dele.
    private static let prepareDelay: UInt64 = 1_500_000_000
    /// Uma troca de tela tira um botão e põe outro: só libera se nenhum voltar nesse tempo.
    private static let releaseDelay: UInt64 = 3_000_000_000

    private static var launchers = 0
    private static var chat: AtaliaChatViewController?
    private static var scheduled: Task<Void, Never>?

    /// Um botão flutuante com pré-carga apareceu.
    static func launcherAppeared() {
        launchers += 1
        schedule(after: prepareDelay) { prepare() }
    }

    static func launcherDisappeared() {
        launchers = max(launchers - 1, 0)
        guard launchers == 0 else { return }
        schedule(after: releaseDelay) { if launchers == 0 { release() } }
    }

    /// A abertura pega o pré-carregado, se ainda serve (mesmo canal, mesmas opções, mesma sessão).
    static func take() -> AtaliaChatViewController? {
        guard let prepared = chat else { return nil }
        chat = nil
        guard prepared.isReusable else {
            prepared.discard()
            return nil
        }
        return prepared
    }

    /// O chat fechou: com botão flutuante na tela, volta a esperar escondido e a próxima abertura é
    /// imediata. Devolve `false` quando a tela deve descartar o chat.
    static func recycle(_ controller: AtaliaChatViewController) -> Bool {
        guard launchers > 0, chat == nil, controller.isReusable else { return false }
        controller.deactivate()
        chat = controller
        return true
    }

    private static func schedule(after delay: UInt64, _ work: @escaping @MainActor () -> Void) {
        scheduled?.cancel()
        scheduled = Task { @MainActor in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            work()
        }
    }

    private static func prepare() {
        guard launchers > 0, AtaliaCore.shared.configuration != nil, AtaliaCore.shared.activeChat == nil else { return }
        if let chat, chat.isReusable { return }
        chat?.discard()
        let fresh = AtaliaChatViewController(conversationId: nil, preloaded: true)
        fresh.recyclable = true
        fresh.loadViewIfNeeded()
        chat = fresh
    }

    private static func release() {
        chat?.discard()
        chat = nil
    }
}
#endif
