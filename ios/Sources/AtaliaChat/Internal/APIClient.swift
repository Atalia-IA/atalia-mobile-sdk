import Foundation

struct APIResponse {
    let status: Int
    let json: [String: Any]?

    var isSuccess: Bool { (200..<300).contains(status) }
    /// Código de erro da Atalia (`{ "code": "SESSION_EXPIRED" }`).
    var code: String? { json?["code"] as? String }
}

/// API pública do visitante do webchat (`/api/webchat/:token/...`), a mesma que o widget usa.
final class APIClient {
    let baseURL: URL
    let channelToken: String
    private let session: URLSession

    init(baseURL: URL, channelToken: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.channelToken = channelToken
        self.session = session
    }

    func channelConfig() async throws -> APIResponse {
        try await send("GET", "")
    }

    /// Cria (ou confirma) a sessão. 401 `SESSION_EXPIRED` = sessão identificada que expirou.
    func createSession(_ sessionToken: String) async throws -> APIResponse {
        try await send("POST", "/sessions", body: ["sessionToken": sessionToken])
    }

    /// Devolve `{ sessionToken, expiresAt, contact: { id, externalId, name } }`. O token muda.
    func identify(sessionToken: String, jwt: String) async throws -> APIResponse {
        try await send("POST", "/identify", sessionToken: sessionToken, body: ["identity": jwt])
    }

    func logout(sessionToken: String) async throws -> APIResponse {
        try await send("POST", "/logout", sessionToken: sessionToken)
    }

    /// `{ total, conversations: [{ id, unread_count }] }`.
    func unread(sessionToken: String) async throws -> APIResponse {
        try await send("GET", "/unread", sessionToken: sessionToken)
    }

    /// Registra o aparelho na sessão (upsert pelo token). Corpo: `{ token, platform, provider, ... }`.
    func registerPushDevice(sessionToken: String, device: [String: Any]) async throws -> APIResponse {
        try await send("POST", "/push/devices", sessionToken: sessionToken, body: device)
    }

    func unregisterPushDevice(sessionToken: String, pushToken: String) async throws -> APIResponse {
        try await send("DELETE", "/push/devices", sessionToken: sessionToken, body: ["token": pushToken])
    }

    func endpoint(_ path: String) -> URL {
        var base = baseURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        return URL(string: "\(base)/api/webchat/\(ChatURLBuilder.pathSegment(channelToken))\(path)")!
    }

    private func send(_ method: String, _ path: String, sessionToken: String? = nil, body: [String: Any]? = nil) async throws -> APIResponse {
        var request = URLRequest(url: endpoint(path), timeoutInterval: 15)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let sessionToken {
            request.setValue(sessionToken, forHTTPHeaderField: "x-session-token")
        }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = data.isEmpty ? nil : (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        return APIResponse(status: status, json: json)
    }
}
