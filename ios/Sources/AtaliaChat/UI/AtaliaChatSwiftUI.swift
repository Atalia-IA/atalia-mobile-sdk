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
    /// Abre o chat enquanto `isPresented` for `true`: numa folha que sobe (padrão) ou em tela
    /// cheia, conforme `AtaliaOptions.presentationStyle`.
    ///
    /// ```swift
    /// Button("Falar com a loja") { showChat = true }
    ///     .ataliaChat(isPresented: $showChat)
    /// ```
    func ataliaChat(isPresented: Binding<Bool>, conversationId: Int? = nil) -> some View {
        modifier(AtaliaChatPresenter(isPresented: isPresented, conversationId: conversationId))
    }
}

private struct AtaliaChatPresenter: ViewModifier {
    @Binding var isPresented: Bool
    let conversationId: Int?

    func body(content: Content) -> some View {
        if AtaliaCore.shared.configuration?.options.presentationStyle == .fullScreen {
            content.fullScreenCover(isPresented: $isPresented) { chat }
        } else {
            content.sheet(isPresented: $isPresented) { chat.ataliaSheetGrabber() }
        }
    }

    private var chat: some View {
        AtaliaChatView(conversationId: conversationId) { isPresented = false }
            .ignoresSafeArea(.container, edges: .all)
    }
}

private extension View {
    @ViewBuilder
    func ataliaSheetGrabber() -> some View {
        if #available(iOS 16, *) {
            presentationDetents([.large]).presentationDragIndicator(.visible)
        } else {
            self
        }
    }
}
#endif
