#if canImport(UIKit)
import SwiftUI
import UIKit

/// O chat como view do SwiftUI. Para uma aba do app, use com `AtaliaOptions(closeButton: false)`.
public struct AtaliaChatView: UIViewControllerRepresentable {
    private let conversationId: Int?
    private let onClose: (() -> Void)?

    public init(conversationId: Int? = nil, onClose: (() -> Void)? = nil) {
        self.conversationId = conversationId
        self.onClose = onClose
    }

    public func makeUIViewController(context: Context) -> AtaliaChatViewController {
        let controller = AtaliaChatViewController(conversationId: conversationId)
        controller.onClose = onClose
        return controller
    }

    public func updateUIViewController(_ controller: AtaliaChatViewController, context: Context) {
        controller.onClose = onClose
    }
}

public extension View {
    /// Abre o chat em tela cheia enquanto `isPresented` for `true`.
    ///
    /// ```swift
    /// Button("Falar com a loja") { showChat = true }
    ///     .ataliaChat(isPresented: $showChat)
    /// ```
    func ataliaChat(isPresented: Binding<Bool>, conversationId: Int? = nil) -> some View {
        fullScreenCover(isPresented: isPresented) {
            AtaliaChatView(conversationId: conversationId) { isPresented.wrappedValue = false }
                .ignoresSafeArea(.container, edges: .all)
        }
    }
}
#endif
