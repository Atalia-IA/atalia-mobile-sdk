import Foundation

/// O JSON do `#sdk=` (protocol/README.md). Vai no fragmento para a sessão nunca
/// viajar na requisição nem cair em log de servidor.
struct SdkFragment: Equatable {
    var protocolVersion = BridgeProtocol.version
    var platform = "ios"
    var sdkVersion: String = Atalia.sdkVersion
    var appVersion: String?
    var session: String?
    var identity: String?
    var conversationId: Int?
    var closeButton = true
    /// Chat pré-carregado pelo botão flutuante: a página nasce em segundo plano (sem conexão, sem
    /// presença, sem marcar lidas) até o `app_state` de primeiro plano.
    var background = false
    /// Tela do app em que o visitante está (`Atalia.setPage`): o que as regras por página comparam.
    var page: String?
    /// Abre direto no aviso dessa regra (toque no balão do aviso).
    var notice: ChatNotice?

    var jsonObject: [String: Any] {
        var json = popupJSONObject
        json["closeButton"] = closeButton
        if let identity { json["identity"] = identity }
        if let conversationId { json["conversationId"] = conversationId }
        if background { json["background"] = true }
        if let notice { json["notice"] = notice.jsonObject }
        return json
    }

    /// Fragmento da camada dos balões: só o que ela lê. Nunca identidade, conversa, aviso nem
    /// segundo plano.
    var popupJSONObject: [String: Any] {
        var json: [String: Any] = [
            "protocol": protocolVersion,
            "platform": platform,
            "sdkVersion": sdkVersion,
        ]
        if let appVersion { json["appVersion"] = appVersion }
        if let session { json["session"] = session }
        if let page { json["page"] = page }
        return json
    }

    func encoded(popup: Bool = false) -> String {
        let json = popup ? popupJSONObject : jsonObject
        let data = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data("{}".utf8)
        return Base64URL.encode(data)
    }
}

/// Aviso das regras por página a abrir no chat: a regra e, quando o toque foi num botão de fluxo
/// do balão, a posição dele (o aviso já vira conversa).
struct ChatNotice: Equatable {
    var ruleId: Int
    var button: Int?

    var jsonObject: [String: Any] {
        var json: [String: Any] = ["ruleId": ruleId]
        if let button { json["button"] = button }
        return json
    }
}

enum BridgeProtocol {
    static let version = 1
}

enum ChatURLBuilder {
    /// `<base>/chat/<token>?sdk=1#sdk=...`, ou `<base>/?sdk=1#sdk=...` no domínio próprio.
    /// Com `popup`, a camada dos balões: `?sdk=popup` e o fragmento reduzido.
    static func url(baseURL: URL, channelToken: String, customDomain: Bool, fragment: SdkFragment, popup: Bool = false) -> URL {
        var base = baseURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        let path = customDomain ? "/" : "/chat/\(pathSegment(channelToken))"
        return URL(string: "\(base)\(path)?sdk=\(popup ? "popup" : "1")#sdk=\(fragment.encoded(popup: popup))")!
    }

    static func pathSegment(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? value
    }
}

enum Base64URL {
    static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ text: String) -> Data? {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        return Data(base64Encoded: base64)
    }
}
