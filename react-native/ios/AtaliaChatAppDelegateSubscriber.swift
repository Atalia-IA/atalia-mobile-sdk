import ExpoModulesCore
import UIKit

/// Configura a Atalia ao iniciar o app, antes do JavaScript: o toque numa notificação que abre o
/// app já encontra o SDK pronto. O plugin do Expo grava o token no Info.plist (`AtaliaChannelToken`);
/// sem ele, nada acontece aqui e o app chama `configure` pelo JavaScript.
public class AtaliaChatAppDelegateSubscriber: ExpoAppDelegateSubscriber {
    public func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let info = Bundle.main.infoDictionary ?? [:]
        guard let token = (info["AtaliaChannelToken"] as? String)?.trimmingCharacters(in: .whitespaces), !token.isEmpty else {
            return true
        }
        var options = AtaliaOptions()
        if let base = info["AtaliaBaseURL"] as? String, let url = URL(string: base) {
            options.baseURL = url
        }
        options.customDomain = info["AtaliaCustomDomain"] as? Bool ?? false
        Atalia.configure(channelToken: token, options: options)
        return true
    }
}
