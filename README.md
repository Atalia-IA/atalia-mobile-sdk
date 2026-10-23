# Atalia Chat · SDK mobile

O chat de atendimento da [Atalia](https://atalia.ai) dentro do seu app Android ou iOS, com todos os recursos do webchat:

- mensagens com botões, listas, carrossel e cobrança Pix;
- anexos, câmera e nota de voz;
- avaliação do atendimento e horário de atendimento;
- histórico do usuário logado.

> **Pré-lançamento (0.1.0).** A API pode mudar antes da primeira versão publicada. Notificações push, React Native/Expo e Flutter entram nas próximas versões.

| Plataforma | Requisito | Pacote |
|---|---|---|
| iOS | iOS 15+, Xcode 15+ | Swift Package Manager: `https://github.com/Atalia-IA/atalia-mobile-sdk` |
| Android | Android 7.0 (API 24)+ | `ai.atalia:chat` |

## Como funciona

A tela do chat é o próprio webchat da Atalia num WebView, então todo recurso novo do chat chega ao seu app sem atualizar o SDK. O SDK nativo cuida do resto:

- **Sessão:** guarda a sessão do visitante no Keychain (iOS) ou cifrada pelo Android Keystore.
- **Identificação:** liga o visitante ao usuário logado no seu app.
- **Arquivos:** abre câmera, galeria e microfone, e baixa documentos com o nome certo.
- **Links:** abre fora do app.
- **Não lidas:** conta as mensagens não lidas sem abrir o chat.

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

Sem a do microfone, o SDK nega o pedido e o chat mostra um aviso: o app não fecha.

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
val contact = Atalia.identify(tokenDoSeuBackend)
// no logout do app:
Atalia.logout()
```

Regras do JWT:

- vale no máximo 1 hora;
- gere um novo a cada chamada: token com `jti` é recusado se for usado de novo (`REPLAYED`).

Como gerar o token e os códigos de erro (`EXPIRED`, `INVALID_SIGNATURE`...): veja a página "Identificação de usuários no Webchat" na documentação da Atalia.

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

| Opção | Padrão | Para quê |
|---|---|---|
| `baseUrl` | `https://chat.atalia.ai` | Homologação, ou o domínio próprio do canal |
| `customDomain` | `false` | `true` quando `baseUrl` é o domínio próprio vinculado ao canal |
| `closeButton` | `true` | Desligue quando o chat for uma aba do app |
| `debug` | `false` | Inspecionar o WebView (Safari / `chrome://inspect`) |

## Privacidade

- O SDK não rastreia o usuário nem usa identificador de publicidade.
- O iOS inclui um `PrivacyInfo.xcprivacy`, que declara as conversas como dados de suporte ao cliente, sem rastreamento.
- No formulário de privacidade da App Store e do Google Play, declare o que o chat coleta no seu caso: mensagens e anexos, mais nome e e-mail se você identifica o usuário.

## Desenvolvimento

```bash
swift test                                    # iOS: núcleo, roda no macOS
cd android && ./gradlew :chat:testDebugUnitTest   # Android: testes de JVM
cd android && ./gradlew :sample:installDebug -Patalia.channelToken=<token>   # app de exemplo
```

O contrato com o chat (bridge) está em [protocol/README.md](protocol/README.md), e os testes das duas plataformas leem as mesmas fixtures de `protocol/fixtures/`.

## Licença

[MIT](LICENSE)
