import Foundation

/// Mensagem web → nativo já decodificada. Contrato em protocol/README.md.
struct BridgeMessage {
    let version: Int
    let type: String
    let payload: [String: Any]

    /// `body` é o que o `WKScriptMessage` entrega: a string JSON que o widget manda.
    /// Qualquer coisa fora do formato devolve nil (o protocolo só cresce; nunca é erro).
    init?(body: Any) {
        let object: Any?
        if let text = body as? String {
            object = text.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) }
        } else {
            object = body
        }
        guard let dict = object as? [String: Any],
              let type = dict["type"] as? String,
              !type.isEmpty
        else { return nil }
        self.version = dict["v"] as? Int ?? 1
        self.type = type
        self.payload = dict["payload"] as? [String: Any] ?? [:]
    }

    func string(_ key: String) -> String? {
        payload[key] as? String
    }

    func int(_ key: String) -> Int? {
        // JSONSerialization devolve NSNumber; Bool também vira NSNumber, então descarta.
        guard let number = payload[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        return number.intValue
    }

    func bool(_ key: String) -> Bool? {
        payload[key] as? Bool
    }

    /// `popup_changed` da camada dos balões: o balão apareceu (com o tamanho que o WebView precisa
    /// ter, em pontos) ou sumiu. Nil para outro tipo ou sem `visible`.
    var popupChange: PopupChange? {
        guard type == "popup_changed", let visible = bool("visible") else { return nil }
        return PopupChange(visible: visible, kind: string("kind"), width: int("width"), height: int("height"))
    }

    /// `open_chat` da camada dos balões: o visitante tocou no balão. Nil para outro tipo.
    var openChat: OpenChatRequest? {
        guard type == "open_chat" else { return nil }
        return OpenChatRequest(conversationId: int("conversationId"), noticeRuleId: int("noticeRuleId"), noticeButton: int("noticeButton"))
    }

    /// Evento público correspondente, ou nil para mensagens internas (sessão, links,
    /// downloads, fechar) e para tipos desconhecidos.
    var publicEvent: AtaliaEvent? {
        switch type {
        case "ready":
            return .ready
        case "unread_changed":
            return int("count").map { .unreadChanged(count: $0) }
        case "message_received":
            guard let conversationId = int("conversationId"), let messageId = int("messageId") else { return nil }
            return .messageReceived(conversationId: conversationId, messageId: messageId)
        case "conversation_started":
            return int("conversationId").map { .conversationStarted(conversationId: $0) }
        case "conversation_closed":
            return int("conversationId").map { .conversationClosed(conversationId: $0) }
        case "identified":
            guard let externalId = string("externalId"), let contactId = int("contactId") else { return nil }
            return .identified(externalId: externalId, contactId: contactId)
        case "logout":
            return .loggedOut
        case "error":
            guard let code = string("code") else { return nil }
            return .error(code: code, command: string("command"))
        default:
            return nil
        }
    }
}

struct PopupChange: Equatable {
    var visible: Bool
    /// `notice`, `message` ou `teaser`.
    var kind: String?
    var width: Int?
    var height: Int?
}

/// Onde o chat abre depois do toque no balão: na conversa, no aviso ou do jeito padrão (tudo nil).
struct OpenChatRequest: Equatable {
    var conversationId: Int?
    var noticeRuleId: Int?
    var noticeButton: Int?

    var notice: ChatNotice? {
        noticeRuleId.map { ChatNotice(ruleId: $0, button: noticeButton) }
    }
}
