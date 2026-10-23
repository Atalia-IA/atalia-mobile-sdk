import Foundation

/// Lê as fixtures compartilhadas pelos quatro SDKs (protocol/fixtures na raiz do repositório).
enum Fixtures {
    static func json(_ name: String) throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // AtaliaChatTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // ios
            .deletingLastPathComponent() // raiz
            .appendingPathComponent("protocol/fixtures/\(name)")
        let data = try Data(contentsOf: url)
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    /// Volta a mensagem para string JSON, como o widget manda.
    static func raw(_ object: Any) -> Any {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8)
        else { return object }
        return text
    }
}
