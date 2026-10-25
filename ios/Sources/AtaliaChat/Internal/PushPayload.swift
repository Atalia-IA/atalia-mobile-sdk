import Foundation

/// `data` da notificação da Atalia (protocol/README.md, "Notificação push"). Igual no Firebase e
/// no webhook: a chave `atalia = "1"` é o que separa da notificação de outras origens do app.
struct PushPayload: Equatable {
    let channelToken: String?
    let conversationId: Int?
    let messageId: Int?
    let isTest: Bool

    init?(_ data: [AnyHashable: Any]?) {
        guard let data, Self.string(data["atalia"]) == "1" else { return nil }
        channelToken = Self.string(data["channel_token"]).flatMap { $0.isEmpty ? nil : $0 }
        conversationId = Self.positiveInt(data["conversation_id"])
        messageId = Self.positiveInt(data["message_id"])
        isTest = Self.string(data["type"]) == "test"
    }

    /// O Firebase entrega os valores como texto, mas o backend do app (modo webhook) pode mandar número.
    private static func string(_ value: Any?) -> String? {
        switch value {
        case let text as String: return text
        case let number as NSNumber: return number.stringValue
        default: return nil
        }
    }

    private static func positiveInt(_ value: Any?) -> Int? {
        guard let text = string(value), let number = Int(text), number > 0 else { return nil }
        return number
    }
}
