import Foundation

/// Aparência do canal configurada no painel da Atalia (a mesma do botão do site). Fica salva no
/// aparelho: a tela do chat e o botão flutuante já nascem certos, antes de a consulta voltar.
struct ChannelBranding: Codable, Equatable {
    var primaryColor: String?
    var launcherColor: String?
    var launcherText: String?
    /// `bottom-left` ou `bottom-right`.
    var launcherPosition: String?

    init(primaryColor: String? = nil, launcherColor: String? = nil, launcherText: String? = nil, launcherPosition: String? = nil) {
        self.primaryColor = primaryColor
        self.launcherColor = launcherColor
        self.launcherText = launcherText
        self.launcherPosition = launcherPosition
    }

    /// `branding` de `GET /api/webchat/:token`.
    init(json: [String: Any]) {
        func text(_ key: String) -> String? {
            (json[key] as? String).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        }
        self.init(
            primaryColor: text("primary_color"),
            launcherColor: text("launcher_color"),
            launcherText: text("launcher_text"),
            launcherPosition: text("launcher_position")
        )
    }

    /// Cor do botão: a dele, senão a do canal (mesma regra do site).
    var buttonColor: String? { launcherColor ?? primaryColor }
    var buttonText: String { launcherText ?? "Fale conosco" }
    var buttonOnLeading: Bool { launcherPosition == "bottom-left" }
}
