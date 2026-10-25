import Foundation

/// Configuração do SDK, passada em `Atalia.configure(channelToken:options:)`.
public struct AtaliaOptions {
    /// Endereço de produção do chat da Atalia.
    public static let defaultBaseURL = URL(string: "https://chat.atalia.ai")!

    /// Como a tela do chat é apresentada por `Atalia.present(from:)`.
    public enum PresentationStyle {
        case fullScreen
        case pageSheet
    }

    /// Origem do chat. Troque só para homologação ou para o domínio próprio do canal.
    public var baseURL: URL
    /// `true` quando `baseURL` é o domínio próprio vinculado ao canal (o chat fica em `/`).
    public var customDomain: Bool
    /// Mostra o botão "Fechar" no chat. Desligue quando o chat for uma aba do app.
    public var closeButton: Bool
    public var presentationStyle: PresentationStyle
    /// Permite inspecionar o WebView pelo Safari (iOS 16.4+). Nunca em produção.
    public var debug: Bool

    public init(
        baseURL: URL = AtaliaOptions.defaultBaseURL,
        customDomain: Bool = false,
        closeButton: Bool = true,
        presentationStyle: PresentationStyle = .fullScreen,
        debug: Bool = false
    ) {
        self.baseURL = baseURL
        self.customDomain = customDomain
        self.closeButton = closeButton
        self.presentationStyle = presentationStyle
        self.debug = debug
    }
}

/// Ambiente do token do APNs em `Atalia.setAPNsToken(_:environment:)`: `sandbox` nos builds
/// instalados pelo Xcode, `production` no TestFlight e na App Store.
public enum AtaliaPushEnvironment: String, Sendable {
    case production
    case sandbox
}
