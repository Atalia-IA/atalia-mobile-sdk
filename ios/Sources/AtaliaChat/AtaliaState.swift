import Combine
import Foundation

/// Estado observável do SDK, pronto para SwiftUI:
///
/// ```swift
/// @ObservedObject var atalia = Atalia.state
/// Text("\(atalia.unreadCount)")
/// ```
@MainActor
public final class AtaliaState: ObservableObject {
    /// Mensagens do atendente, da IA ou do sistema ainda não lidas pelo visitante.
    @Published public internal(set) var unreadCount = 0
    /// A sessão atual pertence a um usuário identificado por `Atalia.identify(jwt:)`.
    @Published public internal(set) var isIdentified = false
    /// A tela do chat está aberta.
    @Published public internal(set) var isChatOpen = false
    /// Aparência do canal no painel. Nil até o `Atalia.configure`.
    @Published var branding: ChannelBranding?

    nonisolated init() {}
}
