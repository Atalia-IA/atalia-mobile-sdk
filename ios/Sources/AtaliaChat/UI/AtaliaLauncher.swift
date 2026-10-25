#if canImport(UIKit)
import SwiftUI
import UIKit

/// Botão flutuante que abre o chat. A aparência (cor, texto e lado) vem do painel da Atalia, a mesma
/// do botão do site; posição e comportamento são do app. Nada aparece sem o app pedir.
public struct AtaliaLauncherOptions {
    public enum Side {
        case leading
        case trailing
    }

    /// Forma do botão depois de recolher.
    public enum Collapse {
        /// Só o círculo com o ícone.
        case circle
        /// Uma aba encostada na lateral; o toque mostra o botão de novo.
        case edge
        /// Fica sempre com o texto.
        case never
    }

    /// Lado da tela. Nil = o lado do painel.
    public var side: Side?
    /// Distância da lateral, além da área segura.
    public var horizontalPadding: CGFloat
    /// Distância do fim da área segura. Aumente para o botão ficar acima de uma barra de abas.
    public var bottomPadding: CGFloat
    /// Texto do botão aberto. Nil = o do painel ("Fale conosco").
    public var text: String?
    /// Nil = a cor do botão no painel.
    public var color: UIColor?
    public var collapse: Collapse
    /// Segundos com o texto antes de recolher. Volta a abrir quando chega mensagem nova.
    public var collapseAfter: TimeInterval
    /// O usuário pode arrastar o botão; ao soltar, ele gruda na lateral mais próxima.
    public var draggable: Bool
    /// O que o toque faz. Nil = `Atalia.present()`.
    public var onTap: (() -> Void)?

    public init(
        side: Side? = nil,
        horizontalPadding: CGFloat = 16,
        bottomPadding: CGFloat = 16,
        text: String? = nil,
        color: UIColor? = nil,
        collapse: Collapse = .circle,
        collapseAfter: TimeInterval = 5,
        draggable: Bool = false,
        onTap: (() -> Void)? = nil
    ) {
        self.side = side
        self.horizontalPadding = horizontalPadding
        self.bottomPadding = bottomPadding
        self.text = text
        self.color = color
        self.collapse = collapse
        self.collapseAfter = collapseAfter
        self.draggable = draggable
        self.onTap = onTap
    }
}

public extension View {
    /// Botão flutuante que abre o chat, por cima desta view.
    ///
    /// ```swift
    /// TabView { ... }
    ///     .ataliaLauncher(AtaliaLauncherOptions(bottomPadding: 72))
    /// ```
    func ataliaLauncher(_ options: AtaliaLauncherOptions = AtaliaLauncherOptions()) -> some View {
        overlay(AtaliaLauncherLayer(options: options))
    }
}

/// Botão flutuante para telas UIKit. A view ocupa a área toda de quem a recebe, mas só o botão
/// recebe toque: o resto passa para o app.
///
/// ```swift
/// AtaliaLauncherView.attach(to: view, options: AtaliaLauncherOptions(bottomPadding: 72))
/// ```
@MainActor
public final class AtaliaLauncherView: UIView {
    private var host: UIHostingController<AtaliaLauncherLayer>?
    private var buttonFrame: CGRect = .zero

    public init(options: AtaliaLauncherOptions = AtaliaLauncherOptions()) {
        super.init(frame: .zero)
        backgroundColor = .clear
        let host = UIHostingController(rootView: AtaliaLauncherLayer(options: options) { [weak self] frame in
            self?.buttonFrame = frame
        })
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: trailingAnchor),
            host.view.topAnchor.constraint(equalTo: topAnchor),
            host.view.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        self.host = host
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) não é suportado")
    }

    /// Põe o botão por cima de `view`, ocupando a área toda dela. Para todas as telas do app, use a
    /// janela (`view.window`). Para tirar, `removeFromSuperview()`.
    @discardableResult
    public static func attach(to view: UIView, options: AtaliaLauncherOptions = AtaliaLauncherOptions()) -> AtaliaLauncherView {
        let launcher = AtaliaLauncherView(options: options)
        launcher.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(launcher)
        NSLayoutConstraint.activate([
            launcher.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            launcher.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            launcher.topAnchor.constraint(equalTo: view.topAnchor),
            launcher.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        return launcher
    }

    public override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard buttonFrame.insetBy(dx: -4, dy: -4).contains(point) else { return nil }
        return super.hitTest(point, with: event)
    }
}

/// Camada do botão, do tamanho de quem a recebe. Ignora a área segura e a soma à mão: assim as
/// coordenadas do botão (`onButtonFrame`, usada pela versão UIKit) são as da própria view.
struct AtaliaLauncherLayer: View {
    let options: AtaliaLauncherOptions
    var onButtonFrame: ((CGRect) -> Void)?

    @ObservedObject private var state = AtaliaCore.shared.state
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var expanded = true
    @State private var expandToken = 0
    @State private var lastUnread: Int?
    @State private var draggedSide: AtaliaLauncherOptions.Side?
    @State private var lift: CGFloat = 0
    @State private var dragOffset: CGSize = .zero

    init(options: AtaliaLauncherOptions, onButtonFrame: ((CGRect) -> Void)? = nil) {
        self.options = options
        self.onButtonFrame = onButtonFrame
    }

    private enum Shape {
        case pill, circle, edge
    }

    private static let space = "atalia-launcher"
    private static let height: CGFloat = 56

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: side == .leading ? .bottomLeading : .bottomTrailing) {
                Color.clear.allowsHitTesting(false)
                if let branding = state.branding, !state.isChatOpen {
                    button(branding)
                        .background(GeometryReader { proxy in
                            Color.clear.preference(key: LauncherFrameKey.self, value: proxy.frame(in: .named(Self.space)))
                        })
                        .highPriorityGesture(drag(in: geo), including: options.draggable ? .all : .subviews)
                        .padding(side == .leading ? .leading : .trailing, horizontalInset(geo))
                        .padding(.bottom, options.bottomPadding + geo.safeAreaInsets.bottom + lift)
                        .offset(dragOffset)
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .coordinateSpace(name: Self.space)
            .onPreferenceChange(LauncherFrameKey.self) { onButtonFrame?($0) }
            .onDisappear { onButtonFrame?(.zero) }
        }
        .ignoresSafeArea()
        .animation(motion, value: state.isChatOpen)
        .task(id: expandToken) {
            guard expanded, options.collapse != .never else { return }
            try? await Task.sleep(nanoseconds: UInt64(max(options.collapseAfter, 0) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            withAnimation(motion) { expanded = false }
        }
        .onAppear { lastUnread = state.unreadCount }
        .onChange(of: state.unreadCount) { count in
            // Mensagem nova: o botão abre de novo com o texto, como um aviso.
            if let lastUnread, count > lastUnread { expand() }
            lastUnread = count
        }
    }

    private var side: AtaliaLauncherOptions.Side {
        draggedSide ?? options.side ?? (state.branding?.buttonOnLeading == true ? .leading : .trailing)
    }

    private var shape: Shape {
        if expanded { return .pill }
        switch options.collapse {
        case .circle: return .circle
        case .edge: return .edge
        case .never: return .pill
        }
    }

    private var motion: Animation? {
        reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85)
    }

    /// A aba encosta na borda; os outros formatos respeitam a área segura e o espaço pedido.
    private func horizontalInset(_ geo: GeometryProxy) -> CGFloat {
        let safe = side == .leading ? geo.safeAreaInsets.leading : geo.safeAreaInsets.trailing
        return shape == .edge ? 0 : safe + options.horizontalPadding
    }

    private func button(_ branding: ChannelBranding) -> some View {
        let color = Color(options.color ?? UIColor(ataliaHex: branding.buttonColor) ?? .ataliaDefaultPrimary)
        let text = options.text ?? branding.buttonText
        return Button(action: tap) {
            content(text: text, color: color)
        }
        .buttonStyle(LauncherPressStyle())
        .accessibilityLabel(accessibilityText(text))
    }

    @ViewBuilder
    private func content(text: String, color: Color) -> some View {
        switch shape {
        case .pill:
            HStack(spacing: 8) {
                Image(systemName: "bubble.left.fill").font(.system(size: 20, weight: .semibold))
                Text(text).font(.system(size: 16, weight: .semibold)).lineLimit(1)
            }
            .foregroundColor(.white)
            .padding(.leading, 18)
            .padding(.trailing, 22)
            .frame(height: Self.height)
            .background(Capsule().fill(color))
            .overlay(badge.offset(x: 4, y: -4), alignment: .topTrailing)
            .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
        case .circle:
            Image(systemName: "bubble.left.fill")
                .font(.system(size: 24, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: Self.height, height: Self.height)
                .background(Circle().fill(color))
                .overlay(badge.offset(x: 4, y: -4), alignment: .topTrailing)
                .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
        case .edge:
            // A metade de fora fica escondida além da borda: sobra uma aba com a ponta arredondada.
            Image(systemName: side == .trailing ? "chevron.left" : "chevron.right")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.white)
                .padding(side == .trailing ? .trailing : .leading, 16)
                .frame(width: 48, height: Self.height)
                .background(RoundedRectangle(cornerRadius: 16).fill(color))
                .overlay(dot.offset(x: side == .trailing ? 2 : -2, y: 2), alignment: side == .trailing ? .topLeading : .topTrailing)
                .offset(x: side == .trailing ? 16 : -16)
                .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
        }
    }

    @ViewBuilder
    private var badge: some View {
        if state.unreadCount > 0 {
            Text(state.unreadCount > 99 ? "99+" : "\(state.unreadCount)")
                .font(.system(size: 12, weight: .bold))
                .monospacedDigit()
                .foregroundColor(.white)
                .padding(.horizontal, 5)
                .frame(minWidth: 20, minHeight: 20)
                .background(Capsule().fill(Color.red))
                .overlay(Capsule().stroke(Color.white, lineWidth: 1.5))
        }
    }

    @ViewBuilder
    private var dot: some View {
        if state.unreadCount > 0 {
            Circle().fill(Color.red).frame(width: 10, height: 10).overlay(Circle().stroke(Color.white, lineWidth: 1.5))
        }
    }

    private func accessibilityText(_ text: String) -> String {
        let unread = state.unreadCount
        let base = shape == .edge ? "Mostrar \(text.lowercased())" : text
        guard unread > 0 else { return base }
        return "\(base), \(unread) \(unread == 1 ? "mensagem não lida" : "mensagens não lidas")"
    }

    private func tap() {
        if shape == .edge {
            expand()
            return
        }
        if let onTap = options.onTap {
            onTap()
        } else {
            Atalia.present()
        }
    }

    private func expand() {
        withAnimation(motion) { expanded = true }
        expandToken += 1
    }

    /// Arrasta solto; ao soltar, gruda na lateral mais próxima e guarda a altura.
    private func drag(in geo: GeometryProxy) -> some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .named(Self.space))
            .onChanged { value in dragOffset = value.translation }
            .onEnded { value in
                let maxLift = max(0, geo.size.height - geo.safeAreaInsets.top - geo.safeAreaInsets.bottom - options.bottomPadding - Self.height - 16)
                withAnimation(motion) {
                    draggedSide = value.location.x < geo.size.width / 2 ? .leading : .trailing
                    lift = min(max(lift - value.translation.height, 0), maxLift)
                    dragOffset = .zero
                }
            }
    }
}

private struct LauncherFrameKey: PreferenceKey {
    static let defaultValue: CGRect = .zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if next != .zero { value = next }
    }
}

private struct LauncherPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.94 : 1)
    }
}
#endif
