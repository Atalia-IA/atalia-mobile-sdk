import Foundation
#if canImport(UIKit)
import UIKit
import UserNotifications
#endif

/// O número no ícone do app e as notificações da Atalia na Central de Notificações.
/// Os testes trocam por um falso: fora de um app, `UNUserNotificationCenter` não existe.
@MainActor
protocol NotificationSurface: AnyObject {
    func setBadge(_ count: Int)
    func removeDeliveredAtaliaNotifications()
}

#if canImport(UIKit)
@MainActor
final class SystemNotificationSurface: NotificationSurface {
    func setBadge(_ count: Int) {
        if #available(iOS 16.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(count, withCompletionHandler: nil)
        } else {
            UIApplication.shared.applicationIconBadgeNumber = count
        }
    }

    /// Só as da Atalia: as notificações do próprio app ficam.
    func removeDeliveredAtaliaNotifications() {
        Task {
            let center = UNUserNotificationCenter.current()
            let ids = await center.deliveredNotifications()
                .filter { PushPayload($0.request.content.userInfo) != nil }
                .map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }
}
#endif
