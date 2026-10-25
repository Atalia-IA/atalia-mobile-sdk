import Foundation

/// Aparência do canal configurada no painel da Atalia (a mesma do botão do site). Fica salva no
/// aparelho: a tela do chat e o botão flutuante já nascem certos, antes de a consulta voltar.
struct ChannelBranding: Codable, Equatable {
    var primaryColor: String?
    var launcherColor: String?
    var launcherText: String?
    /// `bottom-left` ou `bottom-right`.
    var launcherPosition: String?
    /// Nome do atendimento (o mesmo do cabeçalho do chat): subtítulo do botão flutuante.
    var botName: String?

    init(
        primaryColor: String? = nil,
        launcherColor: String? = nil,
        launcherText: String? = nil,
        launcherPosition: String? = nil,
        botName: String? = nil
    ) {
        self.primaryColor = primaryColor
        self.launcherColor = launcherColor
        self.launcherText = launcherText
        self.launcherPosition = launcherPosition
        self.botName = botName
    }

    /// `branding` de `GET /api/webchat/:token`. Sem `bot_name`, o nome do atendimento é o do canal
    /// (`channelName`), a mesma regra do cabeçalho do chat.
    init(json: [String: Any], channelName: String? = nil) {
        func clean(_ value: Any?) -> String? {
            (value as? String).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        }
        self.init(
            primaryColor: clean(json["primary_color"]),
            launcherColor: clean(json["launcher_color"]),
            launcherText: clean(json["launcher_text"]),
            launcherPosition: clean(json["launcher_position"]),
            botName: clean(json["bot_name"]) ?? clean(channelName)
        )
    }

    /// Cor do botão: a dele, senão a do canal (mesma regra do site).
    var buttonColor: String? { launcherColor ?? primaryColor }
    var buttonText: String { launcherText ?? "Fale conosco" }
    var buttonOnLeading: Bool { launcherPosition == "bottom-left" }
}
