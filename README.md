# Atalia Chat · SDK mobile

O chat de atendimento da [Atalia](https://atalia.ai) dentro do seu app Android ou iOS, com todos os recursos do webchat:

- mensagens com botões, listas, carrossel e cobrança Pix;
- anexos, câmera e nota de voz;
- avaliação do atendimento e horário de atendimento;
- histórico do usuário logado.

> **Pré-lançamento (0.1.0).** A API pode mudar antes da primeira versão publicada. React Native/Expo e Flutter entram nas próximas versões.

| Plataforma | Requisito | Pacote |
|---|---|---|
| iOS | iOS 15+ (testado com Xcode 26) | Swift Package Manager: `https://github.com/Atalia-IA/atalia-mobile-sdk` |
| Android | Android 7.0 (API 24)+ | `ai.atalia:chat` (Maven Central) |

## Como funciona

A tela do chat é o próprio webchat da Atalia num WebView, então todo recurso novo do chat chega ao seu app sem atualizar o SDK. O SDK nativo cuida do resto:

- **Sessão:** guarda a sessão do visitante no Keychain (iOS) ou cifrada pelo Android Keystore.
- **Identificação:** liga o visitante ao usuário logado no seu app.
- **Arquivos:** abre câmera, galeria e microfone, e baixa documentos com o nome certo.
- **Links:** abre fora do app.
- **Não lidas:** conta as mensagens não lidas sem abrir o chat.
- **Push:** registra o aparelho e abre a conversa certa no toque da notificação.

## iOS

**Instalar.** No Xcode, vá em File › Add Package Dependencies, informe `https://github.com/Atalia-IA/atalia-mobile-sdk` e adicione o produto `AtaliaChat`.

**Configurar.** Chame uma vez, ao iniciar o app. O token do canal fica no painel da Atalia, em Configurações › Canais › Webchat.

```swift title="AppDelegate.swift"
import AtaliaChat

Atalia.configure(channelToken: "<token-do-canal>")
```

**Abrir o chat:**

```swift title="UIKit"
Atalia.present(from: self)
```

```swift title="SwiftUI"
Button("Falar com a gente") { showChat = true }
    .ataliaChat(isPresented: $showChat)
```

**Badge de não lidas** (SwiftUI):

```swift
@ObservedObject var atalia = Atalia.state

Text("\(atalia.unreadCount)")
```

**Info.plist.** O chat pede câmera e microfone, então adicione as duas chaves:

- `NSCameraUsageDescription` (foto pelo chat);
- `NSMicrophoneUsageDescription` (nota de voz).

Sem a do microfone, o SDK nega o pedido e o chat mostra um aviso: o app não fecha. A da câmera é obrigatória: sem ela, o iOS fecha o app quando o visitante escolhe tirar uma foto no chat.

## Android

**Instalar:**

```kotlin title="build.gradle.kts"
dependencies {
    implementation("ai.atalia:chat:0.1.0")
}
```

**Configurar,** no `Application.onCreate`:

```kotlin title="MyApplication.kt"
Atalia.configure(this, "<token-do-canal>")
```

**Abrir o chat:**

```kotlin
Atalia.open(activity)
```

**Badge de não lidas:**

```kotlin
lifecycleScope.launch {
    Atalia.unreadCount.collect { badge.text = it.toString() }
}
```

**Manifest.** Para a nota de voz, declare as duas permissões abaixo. Sem elas, o microfone é negado e o chat avisa o visitante.

```xml title="AndroidManifest.xml"
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

A foto pela câmera funciona sem permissão nenhuma. Se o seu app já declara `CAMERA` por outro motivo, o SDK pede a permissão na hora de usar.

De Java, use as versões com callback: `Atalia.identify(jwt, callback)`, `Atalia.logout(callback)` e `Atalia.refreshUnread(callback)`.

## Identificar o usuário logado

Com o usuário identificado, ele vê o histórico em qualquer aparelho, e o atendente sabe quem ele é. O token é um JWT que **o seu backend** assina com o segredo do canal. O segredo nunca vai no app.

```swift title="iOS"
let contact = try await Atalia.identify(jwt: tokenDoSeuBackend)
// no logout do app:
await Atalia.logout()
```

```kotlin title="Android"
// dentro de uma corrotina (ex.: lifecycleScope.launch { ... })
val contact = Atalia.identify(tokenDoSeuBackend)
// no logout do app:
Atalia.logout()
```

Regras do JWT:

- vale no máximo 1 hora;
- gere um novo a cada chamada: token com `jti` é recusado se for usado de novo (`REPLAYED`).

Como gerar o token no seu backend e o que significa cada código de erro (`EXPIRED`, `INVALID_SIGNATURE`...): [Identificação de usuários no Webchat](https://chat.atalia.ai/docs/ajuda/webchat-identificacao).

## Notificações push

Quando o atendente, a IA ou o sistema responde e o usuário está fora do chat, a Atalia avisa o aparelho. Ela decide **quando** notificar:

- não notifica com o chat aberto na tela;
- espera alguns segundos e junta uma sequência de mensagens numa notificação só;
- nunca manda a mesma mensagem duas vezes.

A entrega você escolhe no painel da Atalia, em Configurações › Canais › Webchat › App mobile e notificações push:

| Modo | Quem envia | O que você configura |
|---|---|---|
| Firebase | a Atalia, pelo Firebase do seu app | a conta de serviço do Firebase, no painel |
| Webhook | o seu backend (OneSignal, APNs próprio...) | a URL que recebe o evento assinado |

O SDK registra o aparelho na Atalia e o mantém registrado quando o usuário entra ou sai. No logout, o aparelho volta como visitante anônimo: quem usar o app depois não recebe as mensagens do usuário anterior. Para tirar o aparelho (o usuário desligou as notificações do atendimento no seu app), chame `Atalia.clearPushToken()`.

### Android

Com o [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging/android/client) já no app, repasse o token e as mensagens ao SDK:

```kotlin title="MyMessagingService.kt"
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Atalia.setPushToken(token)
    }

    // Com o app aberto o Firebase não mostra nada sozinho: quem mostra é o SDK.
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        if (Atalia.handleNotification(this, message.data, notification?.title, notification?.body)) return
        // ...as notificações do seu app
    }
}
```

Ao abrir o app, envie também o token atual:

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { Atalia.setPushToken(it) }
```

Com o app em segundo plano, quem mostra a notificação é o Android, e o toque abre a activity de entrada do app. Passe o `intent` para o SDK abrir o chat na conversa:

```kotlin title="MainActivity.kt"
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Atalia.handleNotificationTap(this, intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Atalia.handleNotificationTap(this, intent)
}
```

- **Android 13+:** declare `POST_NOTIFICATIONS` no manifest e peça a permissão ao usuário. Sem ela, nenhuma notificação aparece.
- **Ícone:** `AtaliaOptions(notificationIcon = R.drawable.ic_notificacao)` vale para o app aberto. Com o app em segundo plano, o ícone vem do `com.google.firebase.messaging.default_notification_icon` do manifest, como em qualquer push do Firebase.
- O SDK cria o canal de notificação "Mensagens do atendimento" (`atalia_chat`).
- Quando o usuário lê tudo, as notificações da Atalia saem da bandeja, e com elas a bolinha do ícone. As do seu app ficam.

### iOS

No modo Firebase, suba a chave de autenticação do APNs (`.p8`) no console do Firebase e ative a capability Push Notifications no Xcode. Depois repasse o token do Firebase e trate a exibição e o toque:

```swift title="AppDelegate.swift"
import AtaliaChat
import FirebaseCore
import FirebaseMessaging
import UserNotifications

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        FirebaseApp.configure()
        Atalia.configure(channelToken: "<token-do-canal>")
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
        application.registerForRemoteNotifications()
        return true
    }

    // `nonisolated` nos três abaixo: o iOS e o Firebase podem chamá-los fora da main thread.
    // As funções de push da Atalia podem ser chamadas de qualquer thread.

    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        if let fcmToken { Atalia.setPushToken(fcmToken) }
    }

    // App aberto: sem banner quando o chat já está na tela.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler(Atalia.presentationOptions(for: notification) ?? [.banner, .sound]) // senão, as do seu app
    }

    // Toque: abre o chat na conversa, inclusive quando o toque é que abriu o app.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        if !Atalia.handleNotificationTap(response.notification.request.content.userInfo) {
            // ...as notificações do seu app
        }
        completionHandler()
    }
}
```

> [!WARNING]
> Use as versões com `completionHandler` destes dois métodos, não as `async`. Na `async` o Swift chama o `completionHandler` do iOS fora da main thread, e o UIKit fecha o app no toque da notificação.

Em SwiftUI, ligue o `AppDelegate` com `@UIApplicationDelegateAdaptor(AppDelegate.self)`.

O número no ícone do app vem da Atalia: são as não lidas do usuário. O SDK o mantém atualizado e, quando o usuário lê tudo, tira da Central de Notificações as notificações da Atalia (as do seu app ficam). Se o seu app tem contador próprio no ícone, desligue com `AtaliaOptions(appBadge: false)`: aí nem o SDK nem o push da Atalia mexem no número, e você pode somar o `Atalia.unreadCount` ao seu.

### Modo webhook

O seu backend recebe o evento `webchat.notification` com os aparelhos do usuário e a notificação pronta (título, corpo, badge e `data`), e envia pelo serviço que já usa. Repasse o `data` sem mudar: é a chave `atalia` que faz o SDK reconhecer o toque. O formato do `data` está em [protocol/README.md](protocol/README.md#notificação-push).

No app, registre o token do serviço que o seu backend usa. Para o APNs direto, no iOS:

```swift
func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    Atalia.setAPNsToken(deviceToken, environment: .production) // .sandbox nos builds instalados pelo Xcode
}
```

## Eventos

| Evento | Quando |
|---|---|
| `ready` | o chat carregou |
| `opened` / `closed` | a tela abriu ou fechou |
| `identified` / `loggedOut` | identidade mudou |
| `conversationStarted` / `conversationClosed` | conversa começou ou foi encerrada |
| `messageReceived` | chegou mensagem do atendente, da IA ou do sistema (só ids, nunca o texto) |
| `unreadChanged` | contador de não lidas mudou |
| `error` | falha ao carregar ou num comando |

```swift title="iOS"
Atalia.addListener { event in
    if case let .messageReceived(conversationId, _) = event { print(conversationId) }
}
```

```kotlin title="Android"
Atalia.events.collect { event ->
    if (event is AtaliaEvent.MessageReceived) println(event.conversationId)
}
```

## Opções

Passadas no `configure` (`AtaliaOptions`):

| Opção | Padrão | Para quê |
|---|---|---|
| `baseURL` (iOS) / `baseUrl` (Android) | `https://chat.atalia.ai` | Outro ambiente, ou o domínio próprio do canal |
| `customDomain` | `false` | `true` quando a origem é o domínio próprio vinculado ao canal |
| `closeButton` | `true` | Desligue quando o chat for uma aba do app |
| `presentationStyle` (só iOS) | `.fullScreen` | `.pageSheet` abre como folha |
| `urlHandler` (só Android) | nenhum | Decide como abrir os links tocados no chat. No iOS: `Atalia.urlHandler` |
| `notificationIcon` (só Android) | ícone do app | Ícone da notificação mostrada com o app aberto |
| `appBadge` (só iOS) | `true` | Com o push ligado, o número no ícone do app acompanha as não lidas. Desligue se o app tem contador próprio: o push da Atalia também deixa de mexer nele |
| `debug` | `false` | Inspecionar o WebView (Safari / `chrome://inspect`). Nunca em produção |

## Privacidade

- O SDK não rastreia o usuário nem usa identificador de publicidade.
- O iOS inclui um `PrivacyInfo.xcprivacy`, que declara as conversas como dados de suporte ao cliente, sem rastreamento.
- No formulário de privacidade da App Store e do Google Play, declare o que o chat coleta no seu caso: mensagens e anexos, mais nome e e-mail se você identifica o usuário.

## Desenvolvimento

O Android pede o JDK 17 (o do Android Studio serve).

```bash
swift test                                    # iOS: núcleo, roda no macOS
cd android && ./gradlew :chat:testDebugUnitTest   # Android: testes de JVM
cd android && ./gradlew :sample:installDebug -Patalia.channelToken=<token>   # app de exemplo
```

O contrato com o chat (bridge) está em [protocol/README.md](protocol/README.md), e os testes das duas plataformas leem as mesmas fixtures de `protocol/fixtures/`. Para lançar uma versão, veja [RELEASING.md](RELEASING.md).

## Licença

[MIT](LICENSE)
