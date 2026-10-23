import Foundation
import Security

/// Sessão do visitante. O token é a credencial da conversa: fica no Keychain, nunca em UserDefaults.
struct StoredSession: Codable, Equatable {
    var token: String
    var identified: Bool
    var externalId: String?
}

protocol SessionStoring: AnyObject {
    func load() -> StoredSession?
    func save(_ session: StoredSession)
    func clear()
}

/// Um item por canal e origem: o mesmo app pode falar com mais de um canal (ou com homologação).
final class KeychainSessionStore: SessionStoring {
    private let service = "ai.atalia.chat.session"
    private let account: String

    init(baseURL: URL, channelToken: String) {
        account = "\(baseURL.host ?? "atalia")|\(channelToken)"
    }

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    func load() -> StoredSession? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(StoredSession.self, from: data)
    }

    func save(_ session: StoredSession) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            // Só neste aparelho e depois do primeiro desbloqueio: o push pode acordar o app
            // com a tela bloqueada e o SDK precisa ler a sessão para atualizar o badge.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            var insert = query
            insert.merge(attributes) { _, new in new }
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    func clear() {
        SecItemDelete(query as CFDictionary)
    }
}

/// Usado nos testes.
final class MemorySessionStore: SessionStoring {
    private var session: StoredSession?

    init(_ session: StoredSession? = nil) {
        self.session = session
    }

    func load() -> StoredSession? { session }
    func save(_ session: StoredSession) { self.session = session }
    func clear() { session = nil }
}
