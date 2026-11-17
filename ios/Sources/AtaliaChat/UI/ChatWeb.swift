#if canImport(UIKit)
import UIKit
import WebKit

/// Quem recebe as mensagens web → nativo de um WebView da Atalia (tela do chat ou camada dos balões).
@MainActor
protocol BridgeMessageReceiver: AnyObject {
    func receive(_ message: WKScriptMessage)
}

/// O `WKUserContentController` retém o handler; o proxy fraco evita o ciclo com quem recebe.
final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: BridgeMessageReceiver?

    init(target: BridgeMessageReceiver) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        MainActor.assumeIsolated {
            target?.receive(message)
        }
    }
}

/// O que a tela do chat e a camada dos balões fazem igual: confiar só na origem do chat, abrir
/// links fora do WebView e montar os comandos nativo → web.
@MainActor
enum ChatWeb {
    /// Mensagem do bridge, só do frame principal e da origem do chat. Nil para qualquer outra.
    static func bridgeMessage(from message: WKScriptMessage) -> BridgeMessage? {
        guard message.frameInfo.isMainFrame, isTrusted(message.frameInfo.securityOrigin) else { return nil }
        return BridgeMessage(body: message.body)
    }

    static func isTrusted(_ origin: WKSecurityOrigin) -> Bool {
        guard let base = AtaliaCore.shared.configuration?.options.baseURL else { return false }
        return origin.protocol == base.scheme && origin.host == base.host && origin.port == (base.port ?? 0)
    }

    static func isTrusted(_ url: URL) -> Bool {
        guard let base = AtaliaCore.shared.configuration?.options.baseURL else { return false }
        return url.scheme == base.scheme && url.host == base.host && url.port == base.port
    }

    /// O app decide primeiro (`Atalia.urlHandler`); senão, o sistema (Safari, discador, WhatsApp).
    static func openExternal(_ url: URL) {
        if let handler = Atalia.urlHandler, handler(url) { return }
        UIApplication.shared.open(url)
    }

    static func commandScript(_ type: String, payload: [String: Any]?) -> String {
        var command: [String: Any] = ["type": type]
        if let payload { command["payload"] = payload }
        let json = (try? JSONSerialization.data(withJSONObject: command)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return "window.AtaliaChat && window.AtaliaChat._native && window.AtaliaChat._native(\(json));"
    }
}
#endif
