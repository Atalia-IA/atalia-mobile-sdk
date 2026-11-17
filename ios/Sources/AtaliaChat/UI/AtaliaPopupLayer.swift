#if canImport(UIKit)
import Combine
import UIKit
import WebKit

/// Camada dos balões (protocol/README.md): a segunda página do widget (`?sdk=popup`) num WebView
/// transparente por cima das telas do app, acima do botão flutuante. Ela só desenha os balões do
/// widget fechado (aviso das regras por página, mensagem nova e boas-vindas); o toque pede ao SDK
/// para abrir a tela do chat.
///
/// Fica na hierarquia desde o começo, com a largura final e invisível (a página precisa rodar e
/// medir o balão), e só aparece e recebe toque quando a página avisa `popup_changed` visível.
/// Desta página o SDK trata só `popup_changed`, `open_chat`, `open_url` e `ready`: sessão, não
/// lidas e eventos públicos são assunto da tela do chat.
@MainActor
final class AtaliaPopupLayer: UIView {
    /// Largura do balão no site, já com a margem da sombra.
    private static let maxWidth: CGFloat = 314
    /// Folga mínima somada dos dois lados quando a tela é estreita.
    private static let screenMargin: CGFloat = 16
    /// Altura enquanto a página ainda não mediu o balão.
    private static let provisionalHeight: CGFloat = 320
    /// Margem transparente que a página deixa em volta do balão (cabe a sombra).
    private static let pageInset: CGFloat = 12
    /// Com a margem da página, dá os 16 pt entre o balão e o botão, como no site.
    private static let launcherGap: CGFloat = 4
    private static let launcherSize: CGFloat = 56
    private static let fadeDuration: TimeInterval = 0.18

    private let options: AtaliaLauncherOptions
    private var webView: WKWebView?
    private var isReady = false
    private var pendingCommands: [(type: String, payload: [String: Any]?)] = []
    /// Estado em que a página está (ela nasce em primeiro plano).
    private var pageState = "foreground"
    /// Canal, origem e sessão com que a página carregou: só recarrega se algo mudou.
    private var loadedFor: LoadKey?
    private var loadGeneration = 0

    /// O que a página avisou por `popup_changed`.
    private var pageVisible = false
    private var pageHeight: CGFloat?
    private var isShown = false

    private var appInBackground = UIApplication.shared.applicationState == .background
    private var chatOpen = AtaliaCore.shared.state.isChatOpen
    /// Entre o pedido de abrir o chat e a tela dele aparecer.
    private var chatOpening = false
    private var openingTimeout: Task<Void, Never>?

    /// Partes visíveis do botão flutuante, nas coordenadas de quem contém esta camada.
    private var launcherFrames: [CGRect] = []
    /// Última posição conhecida do botão: distância do topo dele ao fim de quem contém, e o lado.
    private var launcherTopFromBottom: CGFloat?
    private var launcherOnLeft: Bool?

    private struct LoadKey: Equatable {
        var channelToken: String
        var baseURL: URL
        var customDomain: Bool
        var session: String?
    }

    private var lifecycleObservers: [NSObjectProtocol] = []
    private var cancellables: Set<AnyCancellable> = []

    init(options: AtaliaLauncherOptions) {
        self.options = options
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        alpha = 0
        isUserInteractionEnabled = false
        // O balão não é lido pelo VoiceOver enquanto está escondido.
        accessibilityElementsHidden = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) não é suportado")
    }

    // MARK: Entrada e saída

    /// Entra em `container` (quem contém o botão flutuante), logo abaixo do botão: por cima das
    /// telas do app, sem cobrir o botão.
    func install(in container: UIView, below launcher: UIView?) {
        if let launcher, launcher.superview === container {
            container.insertSubview(self, belowSubview: launcher)
        } else {
            container.addSubview(self)
        }
        reposition()
        AtaliaCore.shared.register(self)
        observe()
        reload()
    }

    func tearDown() {
        loadGeneration += 1
        openingTimeout?.cancel()
        openingTimeout = nil
        lifecycleObservers.forEach(NotificationCenter.default.removeObserver)
        lifecycleObservers.removeAll()
        cancellables.removeAll()
        AtaliaCore.shared.unregister(self)
        removeWebView()
        removeFromSuperview()
    }

    /// O botão mudou de lugar (arrasto, borda, opções) ou sumiu (lista vazia).
    func setLauncherFrames(_ frames: [CGRect]) {
        guard frames != launcherFrames else { return }
        launcherFrames = frames
        reposition()
        updateVisibility()
    }

    /// Quem contém a camada mudou de tamanho ou de área segura (rotação).
    func containerDidLayout() {
        reposition()
    }

    // MARK: Página

    /// Carrega (ou recarrega) a página com a sessão guardada. Sem sessão ainda, cria a anônima
    /// antes: a página não avisa a sessão dela ao SDK, então as duas páginas nasceriam separadas.
    private func reload() {
        loadGeneration += 1
        let generation = loadGeneration
        guard AtaliaCore.shared.configuration != nil else { return }
        Task { [weak self] in
            _ = try? await AtaliaCore.shared.ensureSession()
            guard let self, self.loadGeneration == generation, self.superview != nil else { return }
            self.loadPage()
        }
    }

    private func loadPage() {
        guard let config = AtaliaCore.shared.configuration else { return }
        removeWebView()
        isReady = false
        pageState = "foreground"
        pageVisible = false
        pageHeight = nil
        updateVisibility(animated: false)

        let contentController = WKUserContentController()
        contentController.add(WeakScriptMessageHandler(target: self), name: "atalia")
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        // O mesmo armazenamento da tela do chat: as duas páginas dividem o localStorage.
        configuration.websiteDataStore = .default()

        let webView = WKWebView(frame: bounds, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsBackForwardNavigationGestures = false
        if #available(iOS 16.4, *) {
            webView.isInspectable = config.options.debug
        }
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(webView)
        self.webView = webView

        var fragment = SdkFragment()
        fragment.appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        fragment.session = AtaliaCore.shared.storedSessionToken
        fragment.page = AtaliaCore.shared.page
        loadedFor = currentLoadKey()
        let url = ChatURLBuilder.url(
            baseURL: config.options.baseURL,
            channelToken: config.channelToken,
            customDomain: config.options.customDomain,
            fragment: fragment,
            popup: true
        )
        AtaliaLog.info("balões: carregando a camada dos balões")
        webView.load(URLRequest(url: url))
    }

    private func currentLoadKey() -> LoadKey? {
        guard let config = AtaliaCore.shared.configuration else { return nil }
        return LoadKey(
            channelToken: config.channelToken,
            baseURL: config.options.baseURL,
            customDomain: config.options.customDomain,
            session: AtaliaCore.shared.storedSessionToken
        )
    }

    private func removeWebView() {
        guard let webView else { return }
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "atalia")
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.stopLoading()
        webView.removeFromSuperview()
        self.webView = nil
    }

    // MARK: Bridge

    private func flushPendingCommands() {
        let commands = pendingCommands
        pendingCommands.removeAll()
        commands.forEach { sendCommand($0.type, payload: $0.payload) }
        syncPageState()
    }

    private func openChat(_ request: OpenChatRequest) {
        // O balão sai antes de a tela do chat subir.
        pageVisible = false
        updateVisibility(animated: false)
        Atalia.present(request)
    }

    // MARK: Ciclo de vida

    private func observe() {
        let center = NotificationCenter.default
        // As mesmas notificações da tela do chat.
        lifecycleObservers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.appInBackground = true
                self?.syncPageState()
            }
        })
        lifecycleObservers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.appInBackground = false
                self?.syncPageState()
            }
        })
        // `@Published` avisa antes de gravar: o valor novo é o que chega aqui.
        AtaliaCore.shared.state.$isChatOpen
            .removeDuplicates()
            .sink { [weak self] open in
                guard let self else { return }
                self.chatOpen = open
                if open { self.endOpening() }
                self.syncPageState()
            }
            .store(in: &cancellables)
    }

    private func endOpening() {
        chatOpening = false
        openingTimeout?.cancel()
        openingTimeout = nil
    }

    /// Com o chat aberto (ou abrindo) ou o app fora da tela, a página fica em segundo plano: fecha a
    /// conexão e tira o balão. Na volta ela recarrega a sessão e pega o que mudou.
    private var wantsBackground: Bool {
        appInBackground || chatOpen || chatOpening
    }

    private func syncPageState() {
        updateVisibility(animated: !wantsBackground)
        let state = wantsBackground ? "background" : "foreground"
        guard isReady, let webView, state != pageState else { return }
        pageState = state
        webView.evaluateJavaScript(ChatWeb.commandScript("app_state", payload: ["state": state]), completionHandler: nil)
    }

    // MARK: Posição e visibilidade

    /// Acima do botão flutuante, do lado dele. Sem o botão na tela, na última posição conhecida ou
    /// na que as opções dão.
    private func reposition() {
        guard let container = superview else { return }
        let size = container.bounds.size
        guard size.width > 0, size.height > 0 else { return }
        let safe = container.safeAreaInsets

        if let button = launcherFrames.reduce(nil as CGRect?, { $0?.union($1) ?? $1 }) {
            launcherTopFromBottom = size.height - button.minY
            // O lado é o da borda mais próxima do botão (vale para o arrasto e para o lado do painel).
            launcherOnLeft = button.minX < size.width - button.maxX
        }
        let topFromBottom = launcherTopFromBottom ?? safe.bottom + options.bottomPadding + Self.launcherSize
        let onLeft = launcherOnLeft ?? defaultOnLeft()

        let width = max(min(Self.maxWidth, size.width - Self.screenMargin), 0)
        let bottom = size.height - topFromBottom - Self.launcherGap
        let available = max(bottom - safe.top, 1)
        let height = min(pageHeight ?? Self.provisionalHeight, available)
        // A margem da página já afasta o balão: a borda do WebView fica 12 pt mais para fora.
        let edge = max(options.horizontalPadding - Self.pageInset, 0)
        var x = onLeft ? safe.left + edge : size.width - safe.right - edge - width
        x = min(max(x, 0), max(size.width - width, 0))

        let target = CGRect(x: x, y: bottom - height, width: width, height: height)
        if frame != target { frame = target }
    }

    private func defaultOnLeft() -> Bool {
        let leading: Bool
        switch options.side {
        case .leading?: leading = true
        case .trailing?: leading = false
        case nil: leading = AtaliaCore.shared.state.branding?.buttonOnLeading == true
        }
        return (effectiveUserInterfaceLayoutDirection == .rightToLeft) != leading
    }

    /// Aparece só com o balão pronto na página, o botão na tela e a página em primeiro plano.
    private func updateVisibility(animated: Bool = true) {
        let show = pageVisible && !launcherFrames.isEmpty && !wantsBackground
        guard show != isShown else { return }
        isShown = show
        isUserInteractionEnabled = show
        accessibilityElementsHidden = !show
        let change = { self.alpha = show ? 1 : 0 }
        if animated {
            UIView.animate(withDuration: Self.fadeDuration, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: change)
        } else {
            layer.removeAllAnimations()
            change()
        }
    }
}

extension AtaliaPopupLayer: BridgeMessageReceiver {
    func receive(_ message: WKScriptMessage) {
        guard message.webView === webView, let bridge = ChatWeb.bridgeMessage(from: message) else { return }
        switch bridge.type {
        case "ready":
            isReady = true
            flushPendingCommands()
        case "popup_changed":
            guard let change = bridge.popupChange else { return }
            pageVisible = change.visible
            if change.visible, let height = change.height, height > 0 {
                pageHeight = CGFloat(height)
                reposition()
            }
            updateVisibility()
        case "open_chat":
            if let request = bridge.openChat { openChat(request) }
        case "open_url":
            if let raw = bridge.string("url"), let url = URL(string: raw) { ChatWeb.openExternal(url) }
        default:
            // Sessão, não lidas, erros e o resto são da tela do chat: daqui não chegam ao SDK
            // nem viram evento público.
            break
        }
    }
}

extension AtaliaPopupLayer: PopupLayerTarget {
    func sendCommand(_ type: String, payload: [String: Any]?) {
        guard isReady, let webView else {
            // Só o último `set_page` importa, e o carregamento novo já leva a tela no fragmento.
            pendingCommands.removeAll { $0.type == type }
            pendingCommands.append((type, payload))
            return
        }
        webView.evaluateJavaScript(ChatWeb.commandScript(type, payload: payload), completionHandler: nil)
    }

    func sessionDidChange() {
        // Já carregada com a sessão guardada (ou esperando ela ser criada): nada a refazer.
        if webView != nil, loadedFor != nil, loadedFor == currentLoadKey() { return }
        guard superview != nil else { return }
        reload()
    }

    func chatWillOpen() {
        chatOpening = true
        syncPageState()
        // A tela do chat não apareceu (apresentação recusada pelo app): os balões voltam.
        openingTimeout?.cancel()
        openingTimeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled, let self else { return }
            self.chatOpening = false
            self.syncPageState()
        }
    }
}

extension AtaliaPopupLayer: WKNavigationDelegate {
    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        if url.scheme == "about" || (ChatWeb.isTrusted(url) && navigationAction.targetFrame != nil) {
            decisionHandler(.allow)
            return
        }
        // Qualquer outro destino sai do WebView, como na tela do chat.
        ChatWeb.openExternal(url)
        decisionHandler(.cancel)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard (error as NSError).code != NSURLErrorCancelled else { return }
        // Sem rede: fica sem balão. A próxima troca de sessão ou a volta do botão tenta de novo.
        AtaliaLog.warn("balões: a camada dos balões não carregou (sem rede ou baseURL errada)")
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // O iOS matou o processo da página (memória): recarrega escondida.
        reload()
    }
}

extension AtaliaPopupLayer: WKUIDelegate {
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if let url = navigationAction.request.url { ChatWeb.openExternal(url) }
        return nil
    }
}

/// Liga a camada dos balões ao botão flutuante do SwiftUI (`.ataliaLauncher()`): uma view invisível,
/// do tamanho da camada do botão, que põe os balões na janela enquanto está nela. As posições do
/// botão chegam nas coordenadas dela.
@MainActor
final class PopupAnchorView: UIView {
    private let options: AtaliaLauncherOptions
    private var popup: AtaliaPopupLayer?
    var launcherFrames: [CGRect] = [] {
        didSet { if launcherFrames != oldValue { pushFrames() } }
    }

    init(options: AtaliaLauncherOptions) {
        self.options = options
        super.init(frame: .zero)
        backgroundColor = .clear
        isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) não é suportado")
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        popup?.tearDown()
        popup = nil
        guard let window else { return }
        let popup = AtaliaPopupLayer(options: options)
        popup.install(in: window, below: nil)
        self.popup = popup
        pushFrames()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        pushFrames()
        popup?.containerDidLayout()
    }

    private func pushFrames() {
        guard let popup, let window else { return }
        popup.setLauncherFrames(launcherFrames.map { convert($0, to: window) })
    }
}
#endif
