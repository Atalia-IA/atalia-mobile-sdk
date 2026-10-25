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

    var jsonObject: [String: Any] {
        var json: [String: Any] = [
            "protocol": protocolVersion,
            "platform": platform,
            "sdkVersion": sdkVersion,
            "closeButton": closeButton,
        ]
        if let appVersion { json["appVersion"] = appVersion }
        if let session { json["session"] = session }
        if let identity { json["identity"] = identity }
        if let conversationId { json["conversationId"] = conversationId }
        if background { json["background"] = true }
        return json
    }

    func encoded() -> String {
        let data = (try? JSONSerialization.data(withJSONObject: jsonObject, options: [.sortedKeys])) ?? Data("{}".utf8)
        return Base64URL.encode(data)
    }
}

enum BridgeProtocol {
    static let version = 1
}

enum ChatURLBuilder {
    /// `<base>/chat/<token>?sdk=1#sdk=...`, ou `<base>/?sdk=1#sdk=...` no domínio próprio.
    static func url(baseURL: URL, channelToken: String, customDomain: Bool, fragment: SdkFragment) -> URL {
        var base = baseURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        let path = customDomain ? "/" : "/chat/\(pathSegment(channelToken))"
        return URL(string: "\(base)\(path)?sdk=1#sdk=\(fragment.encoded())")!
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
