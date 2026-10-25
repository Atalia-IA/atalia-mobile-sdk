#if canImport(UIKit)
import SwiftUI
import UIKit

/// Botão flutuante que abre o chat. A aparência (cor, texto, nome do atendimento e lado) vem do
/// painel da Atalia, a mesma do botão do site; posição e comportamento são do app. Nada aparece
/// sem o app pedir.
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
        /// Fica sempre aberto.
        case never
    }

    /// Lado da tela. Nil = o lado do painel.
    public var side: Side?
    /// Distância da lateral, além da área segura.
    public var horizontalPadding: CGFloat
    /// Distância do fim da área segura. Aumente para o botão ficar acima de uma barra de abas.
    public var bottomPadding: CGFloat
    /// Título do botão aberto. Nil = o do painel ("Fale conosco").
    public var text: String?
    /// Linha de baixo do botão aberto. Nil = o nome do atendimento no painel; vazio = sem subtítulo.
    public var subtitle: String?
    /// Cor do círculo e da aba. Nil = a cor do botão no painel.
    public var color: UIColor?
    public var collapse: Collapse
    /// Segundos aberto antes de recolher. Volta a abrir quando chega mensagem nova.
    public var collapseAfter: TimeInterval
    /// O usuário pode arrastar o botão; ao soltar, ele gruda na lateral mais próxima.
    public var draggable: Bool
    /// Puxar o botão até a borda o esconde numa aba; tocar ou puxar a aba traz de volta.
    public var dockable: Bool
    /// Carrega o chat escondido enquanto o botão está na tela: o toque abre o chat pronto.
    public var preloadChat: Bool
    /// O que o toque faz. Nil = `Atalia.present()`.
    public var onTap: (() -> Void)?

    public init(
        side: Side? = nil,
        horizontalPadding: CGFloat = 16,
        bottomPadding: CGFloat = 16,
        text: String? = nil,
        subtitle: String? = nil,
        color: UIColor? = nil,
        collapse: Collapse = .circle,
        collapseAfter: TimeInterval = 5,
        draggable: Bool = false,
        dockable: Bool = true,
        preloadChat: Bool = true,
        onTap: (() -> Void)? = nil
    ) {
        self.side = side
        self.horizontalPadding = horizontalPadding
        self.bottomPadding = bottomPadding
        self.text = text
        self.subtitle = subtitle
        self.color = color
        self.collapse = collapse
        self.collapseAfter = collapseAfter
        self.draggable = draggable
        self.dockable = dockable
        self.preloadChat = preloadChat
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
    private var buttonFrames: [CGRect] = []

    public init(options: AtaliaLauncherOptions = AtaliaLauncherOptions()) {
        super.init(frame: .zero)
        backgroundColor = .clear
        let host = UIHostingController(rootView: AtaliaLauncherLayer(options: options) { [weak self] frames in
            self?.buttonFrames = frames
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

    /// Só as partes visíveis do botão (círculo, pílula aberta ou aba) recebem toque.
    public override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard buttonFrames.contains(where: { $0.insetBy(dx: -4, dy: -4).contains(point) }) else { return nil }
        return super.hitTest(point, with: event)
    }
}

/// Camada do botão, do tamanho de quem a recebe. Ignora a área segura e a soma à mão: assim as
/// coordenadas do botão (`onButtonFrames`, usada pela versão UIKit) são as da própria view.
///
/// Aberto, é uma pílula com o título e o nome do atendimento saindo de trás do círculo colorido.
/// Com o botão parado, puxar faz um efeito elástico; puxar até a borda o esconde numa aba, e tocar
/// (ou puxar) a aba o traz de volta.
struct AtaliaLauncherLayer: View {
    let options: AtaliaLauncherOptions
    var onButtonFrames: (([CGRect]) -> Void)?

    @ObservedObject private var state = AtaliaCore.shared.state
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Pílula aberta (senão, só o círculo) e o texto dela, que entra logo depois.
    @State private var pillShown = false
    @State private var textShown = false
    /// Escondido numa aba na borda.
    @State private var docked = false
    @State private var collapseToken = 0
    @State private var lastUnread: Int?
    @State private var prewarmRegistered = false

    // Gestos e animações do círculo e da aba.
    @State private var pressing = false
    @State private var moved = false
    @State private var drag: CGSize = .zero
    @State private var circleScale: CGFloat = 1
    @State private var circleOut: CGFloat = 0
    @State private var iconRotation: Double = 0
    @State private var tabOffset: CGFloat = 0
    @State private var tabDrag: CGFloat = 0
    @State private var tabPressed = false

    // Arrasto livre (`draggable`).
    @State private var draggedSide: AtaliaLauncherOptions.Side?
    @State private var lift: CGFloat = 0

    init(options: AtaliaLauncherOptions, onButtonFrames: (([CGRect]) -> Void)? = nil) {
        self.options = options
        self.onButtonFrames = onButtonFrames
    }

    private static let space = "atalia-launcher"
    private static let size: CGFloat = 56
    private static let haloSize: CGFloat = 74
    private static let tabWidth: CGFloat = 48
    /// Parte da aba escondida além da borda.
    private static let tabHidden: CGFloat = 16
    private static let rubberMax: CGFloat = 26
    private static let dockDistance: CGFloat = 32
    private static let dockVelocity: CGFloat = 600
    private static let undockDistance: CGFloat = 16

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: side == .leading ? .bottomLeading : .bottomTrailing) {
                Color.clear.allowsHitTesting(false)
                if let branding = state.branding, !state.isChatOpen {
                    launcher(branding, geo: geo)
                        .padding(side == .leading ? .leading : .trailing, horizontalInset(geo))
                        .padding(.bottom, options.bottomPadding + geo.safeAreaInsets.bottom + lift)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .coordinateSpace(name: Self.space)
            .onPreferenceChange(LauncherFramesKey.self) { onButtonFrames?($0) }
            .onDisappear { onButtonFrames?([]) }
        }
        .ignoresSafeArea()
        .onAppear {
            lastUnread = state.unreadCount
            if options.preloadChat, !prewarmRegistered {
                prewarmRegistered = true
                ChatPrewarm.launcherAppeared()
            }
            if state.branding != nil, !state.isChatOpen { appear() }
        }
        .onDisappear {
            if prewarmRegistered {
                prewarmRegistered = false
                ChatPrewarm.launcherDisappeared()
            }
        }
        // A aparência do canal chegou (primeira abertura) ou o chat fechou: o botão entra de novo.
        .onChange(of: state.branding != nil) { loaded in
            if loaded, !state.isChatOpen { appear() }
        }
        .onChange(of: state.isChatOpen) { open in
            if !open, state.branding != nil { appear() }
        }
        .onChange(of: state.unreadCount) { count in
            // Mensagem nova: o botão "pula" e abre de novo, como um aviso.
            if let lastUnread, count > lastUnread {
                if docked { undock() } else { expand() }
                pop()
            }
            lastUnread = count
        }
        .task(id: collapseToken) {
            guard pillShown, !docked, options.collapse != .never else { return }
            try? await Task.sleep(nanoseconds: UInt64(max(options.collapseAfter, 0) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            collapse()
        }
    }

    // MARK: Aparência

    private var side: AtaliaLauncherOptions.Side {
        draggedSide ?? options.side ?? (state.branding?.buttonOnLeading == true ? .leading : .trailing)
    }

    /// +1 empurra para a borda do lado do botão; -1, para dentro da tela.
    private var outward: CGFloat { side == .leading ? -1 : 1 }

    private var edgeAlignment: Alignment { side == .leading ? .leading : .trailing }

    /// A aba encosta na borda; o botão respeita a área segura e o espaço pedido.
    private func horizontalInset(_ geo: GeometryProxy) -> CGFloat {
        let safe = side == .leading ? geo.safeAreaInsets.leading : geo.safeAreaInsets.trailing
        return docked ? safe : safe + options.horizontalPadding
    }

    @ViewBuilder
    private func launcher(_ branding: ChannelBranding, geo: GeometryProxy) -> some View {
        let color = Color(options.color ?? UIColor(ataliaHex: branding.buttonColor) ?? .ataliaDefaultPrimary)
        let title = options.text ?? branding.buttonText
        if docked {
            tab(color: color, title: title)
        } else {
            button(color: color, title: title, subtitle: options.subtitle ?? branding.botName ?? "", geo: geo)
        }
    }

    private func button(color: Color, title: String, subtitle: String, geo: GeometryProxy) -> some View {
        ZStack(alignment: edgeAlignment) {
            // Pílula por trás do círculo: a ponta dela some embaixo dele.
            pill(title: title, subtitle: subtitle)
                .scaleEffect(x: pillShown ? 1 : 0.3, y: 1, anchor: edgeAlignment == .leading ? .leading : .trailing)
                .opacity(pillShown ? 1 : 0)
                .reportFrame(pillShown)
                .padding(side == .leading ? .leading : .trailing, Self.size / 2)
                .allowsHitTesting(pillShown)
            circle(color: color)
                .reportFrame(true)
        }
        .offset(drag)
        .contentShape(Rectangle())
        .gesture(buttonGesture(geo))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText(title))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { tap() }
    }

    private func pill(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(Color(Self.titleColor))
            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.system(size: 12.5))
                    .foregroundColor(Color(Self.subtitleColor))
            }
        }
        .lineLimit(1)
        .frame(maxWidth: 240, alignment: .leading)
        .fixedSize(horizontal: true, vertical: false)
        .opacity(textShown ? 1 : 0)
        .offset(x: textShown ? 0 : 10 * outward)
        // Espaço da ponta que fica embaixo do círculo.
        .padding(side == .leading ? .leading : .trailing, Self.size / 2 + 14)
        .padding(side == .leading ? .trailing : .leading, 16)
        .frame(height: Self.size)
        .background(Capsule().fill(Color(Self.pillColor)).shadow(color: .black.opacity(0.14), radius: 12, y: 4))
    }

    private func circle(color: Color) -> some View {
        Circle()
            .fill(color)
            .shadow(color: .black.opacity(0.22), radius: 10, y: 4)
            .frame(width: Self.size, height: Self.size)
            .overlay(
                Image(systemName: "bubble.left.fill")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(.white)
                    .rotationEffect(.degrees(iconRotation))
            )
            // Halo do toque: aparece em volta do círculo enquanto o dedo está nele.
            .background(
                Circle()
                    .fill(color.opacity(0.24))
                    .frame(width: Self.haloSize, height: Self.haloSize)
                    .scaleEffect(pressing ? 1 : 0.7)
                    .opacity(pressing ? 1 : 0)
            )
            .scaleEffect((pressing ? 0.92 : 1) * circleScale)
            .overlay(badge.offset(x: 4, y: -4), alignment: .topTrailing)
            .offset(x: circleOut)
    }

    /// Aba da borda: a parte de fora fica escondida além da tela, com a seta para dentro.
    private func tab(color: Color, title: String) -> some View {
        RoundedRectangle(cornerRadius: 16)
            .fill(color)
            .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
            .frame(width: Self.tabWidth, height: Self.size)
            .overlay(
                Image(systemName: side == .trailing ? "chevron.left" : "chevron.right")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
                    .padding(side == .trailing ? .trailing : .leading, Self.tabHidden)
            )
            .overlay(dot.offset(x: -2 * outward, y: 2), alignment: side == .trailing ? .topLeading : .topTrailing)
            .scaleEffect(tabPressed ? 1.08 : 1)
            .contentShape(Rectangle())
            .gesture(tabGesture)
            .offset(x: Self.tabHidden * outward + tabOffset + tabDrag)
            .reportFrame(true)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText(title, docked: true))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { undock() }
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
                .background(Capsule().fill(Color(Self.badgeColor)))
                .overlay(Capsule().stroke(Color.white, lineWidth: 1.5))
        }
    }

    @ViewBuilder
    private var dot: some View {
        if state.unreadCount > 0 {
            Circle().fill(Color(Self.badgeColor)).frame(width: 10, height: 10).overlay(Circle().stroke(Color.white, lineWidth: 1.5))
        }
    }

    private func accessibilityText(_ text: String, docked: Bool = false) -> String {
        let unread = state.unreadCount
        let base = docked ? "Mostrar \(text.lowercased())" : text
        guard unread > 0 else { return base }
        return "\(base), \(unread) \(unread == 1 ? "mensagem não lida" : "mensagens não lidas")"
    }

    private static let pillColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(ataliaHex: "#1F2937")! : .white }
    private static let titleColor = UIColor { $0.userInterfaceStyle == .dark ? .white : UIColor(ataliaHex: "#111827")! }
    private static let subtitleColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(ataliaHex: "#9CA3AF")! : UIColor(ataliaHex: "#6B7280")! }
    private static let badgeColor = UIColor(ataliaHex: "#E5484D")!

    // MARK: Estados

    private func spring(_ response: Double, _ damping: Double) -> Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: response, dampingFraction: damping)
    }

    /// Entrada na tela (e na volta do chat): o círculo cresce e a pílula sai de trás dele.
    private func appear() {
        guard !docked else { return }
        if !reduceMotion {
            instantly { circleScale = 0.2 }
            withAnimation(spring(0.34, 0.6)) { circleScale = 1 }
        }
        pillShown = false
        textShown = false
        after(0.14) { expand() }
    }

    private func expand() {
        guard !docked else { return }
        if !pillShown {
            withAnimation(spring(0.34, 0.72)) { pillShown = true }
            after(0.09) { withAnimation(.easeOut(duration: 0.24)) { textShown = true } }
        }
        collapseToken += 1
    }

    private func collapse() {
        switch options.collapse {
        case .never:
            return
        case .edge:
            dock()
        case .circle:
            guard pillShown else { return }
            withAnimation(.easeIn(duration: 0.18)) {
                pillShown = false
                textShown = false
            }
        }
    }

    /// Esconde na borda: o círculo sai pela lateral e a aba entra no lugar.
    private func dock() {
        guard !docked else { return }
        haptic(.light)
        withAnimation(.easeIn(duration: 0.12)) {
            pillShown = false
            textShown = false
        }
        withAnimation(.easeIn(duration: 0.2)) {
            circleOut = (Self.size + 12 + options.horizontalPadding) * outward
        }
        after(0.2) {
            instantly {
                docked = true
                circleOut = 0
                tabOffset = Self.tabWidth * outward
            }
            withAnimation(spring(0.3, 0.62)) { tabOffset = 0 }
        }
    }

    /// Volta da borda: a aba sai, o círculo entra girando o ícone e o botão abre.
    private func undock() {
        guard docked else { return }
        haptic(.light)
        instantly {
            docked = false
            tabDrag = 0
            if !reduceMotion {
                circleScale = 0.5
                iconRotation = -120 * Double(outward)
            }
        }
        withAnimation(spring(0.32, 0.55)) { circleScale = 1 }
        withAnimation(reduceMotion ? nil : .timingCurve(0.2, 0.9, 0.1, 1, duration: 0.38)) { iconRotation = 0 }
        after(0.16) { expand() }
    }

    /// Aviso de mensagem nova: o círculo cresce e volta.
    private func pop() {
        guard !reduceMotion else { return }
        withAnimation(.easeOut(duration: 0.12)) { circleScale = 1.14 }
        after(0.12) { withAnimation(.spring(response: 0.26, dampingFraction: 0.4)) { circleScale = 1 } }
    }

    private func tap() {
        haptic(.soft)
        if let onTap = options.onTap {
            onTap()
        } else {
            Atalia.present()
        }
    }

    // MARK: Gestos

    private func buttonGesture(_ geo: GeometryProxy) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space))
            .onChanged { value in
                if !pressing { withAnimation(.easeOut(duration: 0.15)) { pressing = true } }
                let translation = value.translation
                if !moved, abs(translation.width) > 6 || abs(translation.height) > 6 { moved = true }
                guard moved else { return }
                // Parado, segue o dedo com resistência, como um elástico; com `draggable`, solto.
                drag = options.draggable
                    ? translation
                    : CGSize(width: rubber(translation.width), height: rubber(translation.height))
            }
            .onEnded { value in
                withAnimation(spring(0.3, 0.5)) { pressing = false }
                let wasMoved = moved
                moved = false
                guard wasMoved else {
                    tap()
                    return
                }
                let dx = value.translation.width
                let towardEdge = dx * outward > 0
                if options.dockable, towardEdge, abs(dx) > Self.dockDistance || outwardVelocity(value) > Self.dockVelocity {
                    withAnimation(.easeIn(duration: 0.12)) { drag = .zero }
                    dock()
                } else if options.draggable {
                    settle(value, geo)
                } else {
                    withAnimation(spring(0.38, 0.45)) { drag = .zero }
                }
            }
    }

    /// Aba: tocar ou puxar para dentro traz o botão de volta.
    private var tabGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space))
            .onChanged { value in
                if !tabPressed { withAnimation(.easeOut(duration: 0.09)) { tabPressed = true } }
                let inward = -value.translation.width * outward
                tabDrag = inward > 0 ? -rubber(inward) * outward : 0
            }
            .onEnded { value in
                withAnimation(.easeOut(duration: 0.12)) { tabPressed = false }
                let wasMoved = abs(value.translation.width) > 6 || abs(value.translation.height) > 6
                let inward = -value.translation.width * outward
                if !wasMoved || inward > Self.undockDistance {
                    undock()
                } else {
                    withAnimation(spring(0.38, 0.45)) { tabDrag = 0 }
                }
            }
    }

    /// Deslocamento com resistência: cresce cada vez menos até `rubberMax`.
    private func rubber(_ distance: CGFloat) -> CGFloat {
        let sign: CGFloat = distance < 0 ? -1 : 1
        return sign * Self.rubberMax * (1 - exp(-abs(distance) / (Self.rubberMax * 2.2)))
    }

    /// Velocidade do dedo em direção à borda, em pontos por segundo.
    private func outwardVelocity(_ value: DragGesture.Value) -> CGFloat {
        if #available(iOS 17, *) { return value.velocity.width * outward }
        // Antes do iOS 17: a previsão do fim do gesto cresce com a velocidade.
        return (value.predictedEndTranslation.width - value.translation.width) * 4 * outward
    }

    /// Solta o arrasto livre: gruda na lateral mais próxima e guarda a altura.
    private func settle(_ value: DragGesture.Value, _ geo: GeometryProxy) {
        let maxLift = max(0, geo.size.height - geo.safeAreaInsets.top - geo.safeAreaInsets.bottom - options.bottomPadding - Self.size - 16)
        withAnimation(spring(0.35, 0.85)) {
            draggedSide = value.location.x < geo.size.width / 2 ? .leading : .trailing
            lift = min(max(lift - value.translation.height, 0), maxLift)
            drag = .zero
        }
    }

    private func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    private func instantly(_ change: () -> Void) {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction, change)
    }

    private func after(_ seconds: Double, _ work: @escaping @MainActor () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { work() }
    }
}

/// Partes visíveis do botão, para a versão UIKit deixar passar os toques no resto da tela.
private struct LauncherFramesKey: PreferenceKey {
    static let defaultValue: [CGRect] = []
    static func reduce(value: inout [CGRect], nextValue: () -> [CGRect]) {
        value.append(contentsOf: nextValue())
    }
}

private extension View {
    func reportFrame(_ visible: Bool) -> some View {
        background(GeometryReader { proxy in
            Color.clear.preference(key: LauncherFramesKey.self, value: visible ? [proxy.frame(in: .named("atalia-launcher"))] : [])
        })
    }
}
#endif
