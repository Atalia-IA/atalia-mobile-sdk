import Foundation
import Security

/// Sessão do visitante. O token é a credencial da conversa: fica no Keychain, nunca em UserDefaults.
struct StoredSession: Codable, Equatable {
    var token: String
    var identified: Bool
    var externalId: String?
}

/// Token do push do aparelho e por onde ele é entregue: `fcm` (a Atalia envia pelo Firebase) ou
/// `apns` (o backend do app envia, modo webhook). `environment` só vale para o APNs.
struct StoredPushToken: Codable, Equatable {
    var token: String
    var provider: String
    var environment: String?
}

protocol SessionStoring: AnyObject {
    func load() -> StoredSession?
    func save(_ session: StoredSession)
    func clear()
    /// O token do push sobrevive ao logout (`clear`): o aparelho continua o mesmo.
    func loadPushToken() -> StoredPushToken?
    func savePushToken(_ token: StoredPushToken)
    func clearPushToken()
    /// Cor do canal da última consulta, para o topo do chat não piscar na cor padrão.
    func loadPrimaryColor() -> String?
    func savePrimaryColor(_ color: String)
}

/// Um item por canal e origem: o mesmo app pode falar com mais de um canal (ou com homologação).
final class KeychainSessionStore: SessionStoring {
    private let service = "ai.atalia.chat.session"
    private let account: String

    init(baseURL: URL, channelToken: String) {
        account = "\(baseURL.host ?? "atalia")|\(channelToken)"
    }

    func load() -> StoredSession? { read(account) }
    func save(_ session: StoredSession) { write(session, to: account) }
    func clear() { delete(account) }

    func loadPushToken() -> StoredPushToken? { read("\(account)|push") }
    func savePushToken(_ token: StoredPushToken) { write(token, to: "\(account)|push") }
    func clearPushToken() { delete("\(account)|push") }

    func loadPrimaryColor() -> String? { read("\(account)|color") }
    func savePrimaryColor(_ color: String) { write(color, to: "\(account)|color") }

    private func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func read<Value: Decodable>(_ account: String) -> Value? {
        var request = query(account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(Value.self, from: data)
    }

    private func write<Value: Encodable>(_ value: Value, to account: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // Só neste aparelho e depois do primeiro desbloqueio: o push pode acordar o app
            // com a tela bloqueada e o SDK precisa ler a sessão para atualizar o badge.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            var insert = query(account)
            insert.merge(attributes) { _, new in new }
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    private func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
}

/// Usado nos testes.
final class MemorySessionStore: SessionStoring {
    private var session: StoredSession?
    private var pushToken: StoredPushToken?
    private var primaryColor: String?

    init(_ session: StoredSession? = nil) {
        self.session = session
    }

    func load() -> StoredSession? { session }
    func save(_ session: StoredSession) { self.session = session }
    func clear() { session = nil }

    func loadPushToken() -> StoredPushToken? { pushToken }
    func savePushToken(_ token: StoredPushToken) { pushToken = token }
    func clearPushToken() { pushToken = nil }

    func loadPrimaryColor() -> String? { primaryColor }
    func savePrimaryColor(_ color: String) { primaryColor = color }
}
