#if canImport(UIKit)
import UIKit
import WebKit

/// Tela do chat: o widget da Atalia num `WKWebView`, ligado ao app pelo bridge.
///
/// Use `Atalia.present(from:)` para abrir por cima da tela atual, ou embuta este controller
/// (numa aba, por exemplo) e trate `onClose`.
@MainActor
public final class AtaliaChatViewController: UIViewController {
    /// Chamado quando o visitante toca em "Fechar".
    public var onClose: (() -> Void)?

    private let initialConversationId: Int?
    private var webView: WKWebView?
    private let spinner = UIActivityIndicatorView(style: .large)
    private var errorView: UIView?
    private var isReady = false
    private var pendingCommands: [(type: String, payload: [String: Any]?)] = []
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var downloadTask: Task<Void, Never>?
    private var didEmitOpened = false

    public init(conversationId: Int? = nil) {
        initialConversationId = conversationId
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) não é suportado")
    }

    public override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    public override func viewDidLoad() {
        super.viewDidLoad()
        // Topo na cor do canal (atrás da barra de status), fundo do rodapé claro.
        view.backgroundColor = UIColor(ataliaHex: AtaliaCore.shared.primaryColorHex) ?? UIColor(red: 0.063, green: 0.212, blue: 0.949, alpha: 1)
        let bottomFill = UIView()
        bottomFill.backgroundColor = .white
        bottomFill.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomFill)
        NSLayoutConstraint.activate([
            bottomFill.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomFill.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomFill.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            bottomFill.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        spinner.color = .white
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])

        observeAppLifecycle()
        loadChat(conversationId: initialConversationId)
    }

    public override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        AtaliaCore.shared.activeChat = self
        if !didEmitOpened {
            didEmitOpened = true
            AtaliaCore.shared.emit(.opened)
        }
    }

    public override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // Câmera e seletor de arquivos também cobrem esta tela; só conta como fechado quando
        // ela (ou quem a contém) está saindo de vez.
        guard isLeavingForGood else { return }
        if AtaliaCore.shared.activeChat === self {
            AtaliaCore.shared.activeChat = nil
        }
        tearDown()
        AtaliaCore.shared.emit(.closed)
    }

    private var isLeavingForGood: Bool {
        var controller: UIViewController? = self
        while let current = controller {
            if current.isBeingDismissed || current.isMovingFromParent { return true }
            controller = current.parent
        }
        return false
    }

    /// Troca de conversa com o chat já aberto (toque numa notificação).
    public func openConversation(_ conversationId: Int) {
        sendCommand("open_conversation", payload: ["conversationId": conversationId])
    }

    func close(animated: Bool = true) {
        if let onClose {
            onClose()
        } else {
            dismiss(animated: animated)
        }
    }

    // MARK: WebView

    private func loadChat(conversationId: Int?) {
        guard let config = AtaliaCore.shared.configuration else { return }
        webView?.removeFromSuperview()
        isReady = false

        let contentController = WKUserContentController()
        contentController.add(WeakScriptMessageHandler(target: self), name: "atalia")
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = contentController
        configuration.allowsInlineMediaPlayback = true
        configuration.websiteDataStore = .default()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsBackForwardNavigationGestures = false
        if #available(iOS 16.4, *) {
            webView.isInspectable = config.options.debug
        }
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.insertSubview(webView, belowSubview: spinner)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])
        self.webView = webView

        var fragment = SdkFragment()
        fragment.appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        fragment.session = AtaliaCore.shared.storedSessionToken
        fragment.conversationId = conversationId
        fragment.closeButton = config.options.closeButton
        let url = ChatURLBuilder.url(
            baseURL: config.options.baseURL,
            channelToken: config.channelToken,
            customDomain: config.options.customDomain,
            fragment: fragment
        )
        hideError()
        spinner.startAnimating()
        webView.load(URLRequest(url: url))
    }

    private func tearDown() {
        downloadTask?.cancel()
        lifecycleObservers.forEach(NotificationCenter.default.removeObserver)
        lifecycleObservers.removeAll()
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "atalia")
        webView?.navigationDelegate = nil
        webView?.uiDelegate = nil
        webView?.stopLoading()
    }

    // MARK: Bridge

    fileprivate func receive(_ message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame,
              isTrusted(message.frameInfo.securityOrigin),
              let bridge = BridgeMessage(body: message.body)
        else { return }

        switch bridge.type {
        case "ready":
            isReady = true
            spinner.stopAnimating()
            flushPendingCommands()
        case "error":
            if bridge.string("command") == nil { spinner.stopAnimating() }
        case "close":
            close()
        case "open_url":
            if let raw = bridge.string("url"), let url = URL(string: raw) { openExternal(url) }
        case "download":
            if let raw = bridge.string("url"), let url = URL(string: raw) { download(url, filename: bridge.string("filename")) }
        default:
            break
        }
        AtaliaCore.shared.handle(bridge)
    }

    private func flushPendingCommands() {
        let commands = pendingCommands
        pendingCommands.removeAll()
        commands.forEach { sendCommand($0.type, payload: $0.payload) }
    }

    private func isTrusted(_ origin: WKSecurityOrigin) -> Bool {
        guard let base = AtaliaCore.shared.configuration?.options.baseURL else { return false }
        return origin.protocol == base.scheme && origin.host == base.host && origin.port == (base.port ?? 0)
    }

    private func isTrusted(_ url: URL) -> Bool {
        guard let base = AtaliaCore.shared.configuration?.options.baseURL else { return false }
        return url.scheme == base.scheme && url.host == base.host && url.port == base.port
    }

    // MARK: Ciclo de vida do app

    private func observeAppLifecycle() {
        let center = NotificationCenter.default
        lifecycleObservers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.notifyAppState("background") }
        })
        lifecycleObservers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.notifyAppState("foreground") }
        })
    }

    /// Em segundo plano o widget fecha o WebSocket e avisa a Atalia que o visitante saiu. Sem
    /// isso o iOS congela o WebView com o socket "aberto" e o push da próxima mensagem não sai.
    /// A tarefa de segundo plano dá tempo de o aviso sair antes da suspensão.
    private func notifyAppState(_ state: String) {
        guard isReady, let webView else { return }
        var backgroundTask = UIBackgroundTaskIdentifier.invalid
        if state == "background" {
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "atalia.app_state") {
                UIApplication.shared.endBackgroundTask(backgroundTask)
            }
        }
        webView.evaluateJavaScript(Self.commandScript("app_state", payload: ["state": state])) { _, _ in
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
            }
        }
    }

    // MARK: Links e arquivos

    private func openExternal(_ url: URL) {
        if let handler = Atalia.urlHandler, handler(url) { return }
        UIApplication.shared.open(url)
    }

    private func download(_ url: URL, filename: String?) {
        downloadTask?.cancel()
        downloadTask = Task { [weak self] in
            do {
                let (temporary, _) = try await URLSession.shared.download(from: url)
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent("atalia-downloads", isDirectory: true)
                try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let destination = directory.appendingPathComponent(Self.safeFilename(filename, fallback: url.lastPathComponent))
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: temporary, to: destination)
                self?.share(destination)
            } catch {
                if !Task.isCancelled { self?.openExternal(url) }
            }
        }
    }

    private func share(_ fileURL: URL) {
        let sheet = UIActivityViewController(activityItems: [fileURL], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
        present(sheet, animated: true)
    }

    static func safeFilename(_ name: String?, fallback: String) -> String {
        let candidate = (name?.isEmpty == false ? name! : fallback)
            .components(separatedBy: CharacterSet(charactersIn: "/\\:"))
            .joined(separator: "_")
        return candidate.isEmpty ? "arquivo" : candidate
    }

    // MARK: Erro de carregamento

    private func showError() {
        spinner.stopAnimating()
        guard errorView == nil else { return }
        let label = UILabel()
        label.text = "Não foi possível carregar o chat. Verifique sua conexão."
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .secondaryLabel
        label.font = .preferredFont(forTextStyle: .body)
        label.adjustsFontForContentSizeCategory = true

        let retry = UIButton(type: .system)
        retry.setTitle("Tentar de novo", for: .normal)
        retry.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        retry.addAction(UIAction { [weak self] _ in self?.loadChat(conversationId: self?.initialConversationId) }, for: .touchUpInside)

        let close = UIButton(type: .system)
        close.setTitle("Fechar", for: .normal)
        close.addAction(UIAction { [weak self] _ in self?.close() }, for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [label, retry, close])
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .center
        let container = UIView()
        container.backgroundColor = .systemBackground
        container.translatesAutoresizingMaskIntoConstraints = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(stack)
        view.addSubview(container)
        NSLayoutConstraint.activate([
            container.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            container.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            container.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            container.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            stack.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -32),
        ])
        errorView = container
    }

    private func hideError() {
        errorView?.removeFromSuperview()
        errorView = nil
    }

    static func commandScript(_ type: String, payload: [String: Any]?) -> String {
        var command: [String: Any] = ["type": type]
        if let payload { command["payload"] = payload }
        let json = (try? JSONSerialization.data(withJSONObject: command)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return "window.AtaliaChat && window.AtaliaChat._native && window.AtaliaChat._native(\(json));"
    }
}

extension AtaliaChatViewController: ChatBridgeTarget {
    func sendCommand(_ type: String, payload: [String: Any]?) {
        guard isReady, let webView else {
            // Estado do app antes de o chat carregar não vale nada: ele já nasce em primeiro plano.
            if type != "app_state" { pendingCommands.append((type, payload)) }
            return
        }
        webView.evaluateJavaScript(Self.commandScript(type, payload: payload), completionHandler: nil)
    }
}

extension AtaliaChatViewController: WKNavigationDelegate {
    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        if url.scheme == "about" || (isTrusted(url) && navigationAction.targetFrame != nil) {
            decisionHandler(.allow)
            return
        }
        // Qualquer outro destino sai do WebView: o chat nunca é trocado por outro site.
        openExternal(url)
        decisionHandler(.cancel)
    }

    /// A página carregou, mas o chat ainda busca a configuração e a conversa: o spinner fica até o
    /// `ready` do widget. O prazo só cobre um widget que nunca avise (o erro dele já tem tela própria).
    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self, !self.isReady else { return }
            self.spinner.stopAnimating()
        }
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard (error as NSError).code != NSURLErrorCancelled else { return }
        showError()
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard (error as NSError).code != NSURLErrorCancelled, !isReady else { return }
        showError()
    }

    public func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // O iOS matou o processo da página (memória). Recarrega na mesma conversa.
        loadChat(conversationId: initialConversationId)
    }
}

extension AtaliaChatViewController: WKUIDelegate {
    public func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if let url = navigationAction.request.url { openExternal(url) }
        return nil
    }

    /// Nota de voz. Sem `NSMicrophoneUsageDescription` no Info.plist o iOS encerraria o app ao
    /// pedir o microfone, então nega e o widget mostra o aviso dele.
    public func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping @MainActor (WKPermissionDecision) -> Void
    ) {
        let declared = Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") != nil
        decisionHandler(isTrusted(origin) && type == .microphone && declared ? .grant : .deny)
    }

    /// O widget usa `alert()` para erros de envio. Sem isto o WKWebView os engole em silêncio.
    public func webView(
        _ webView: WKWebView,
        runJavaScriptAlertPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping @MainActor () -> Void
    ) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    public func webView(
        _ webView: WKWebView,
        runJavaScriptConfirmPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo,
        completionHandler: @escaping @MainActor (Bool) -> Void
    ) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancelar", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }
}

/// O `WKUserContentController` retém o handler; o proxy fraco evita o ciclo com a tela.
private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: AtaliaChatViewController?

    init(target: AtaliaChatViewController) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        MainActor.assumeIsolated {
            target?.receive(message)
        }
    }
}

extension UIColor {
    /// `#RRGGBB` → UIColor. Nil para qualquer outro formato.
    convenience init?(ataliaHex hex: String?) {
        guard var value = hex?.trimmingCharacters(in: .whitespaces), value.hasPrefix("#"), value.count == 7 else { return nil }
        value.removeFirst()
        guard let rgb = UInt32(value, radix: 16) else { return nil }
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}
#endif
