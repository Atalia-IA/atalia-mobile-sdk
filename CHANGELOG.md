# Changelog

Versões seguem [SemVer](https://semver.org/lang/pt-BR/). O protocolo do bridge tem versão própria (ver [protocol/README.md](protocol/README.md)).

## 0.3.0 (2026-10-25)

React Native e Expo.

- React Native e Expo: pacote `@atalia/react-native-chat`, feito com Expo Modules sobre os dois SDKs nativos. Plugin do Expo (token do canal e permissões, com o SDK configurado antes do JavaScript), chat, identificação, não lidas e estado (`useAtaliaState`), eventos, push com `@react-native-firebase/messaging` ou `expo-notifications` e botão flutuante.

### Correções

- Android: sem `notificationIcon`, a notificação com o app aberto usa o balão da Atalia. Antes usava o ícone do app, que o sistema pode recusar ("Invalid notification (no valid small icon)") e fechava o app.

## 0.2.0 (2026-10-25)

Notificações push e botão flutuante, Android e iOS.

- Notificações push: `setPushToken` (Firebase) e `setAPNsToken` (iOS, modo webhook) registram o aparelho na Atalia e o acompanham no `identify` e no `logout`. `handleNotification` (Android) e `presentationOptions(for:)` (iOS) não notificam com o chat na tela, e `handleNotificationTap` abre o chat na conversa. Com tudo lido, as notificações da Atalia saem da bandeja.
- iOS: o número no ícone do app acompanha as não lidas. `AtaliaOptions(appBadge: false)` desliga, inclusive no push da Atalia, para apps com contador próprio.
- Botão flutuante opcional (`.ataliaLauncher()`, `AtaliaLauncherView`, `Atalia.showLauncher`) com a aparência do botão do site configurada no painel: abre com o texto, recolhe em círculo ou numa aba na borda, mostra as não lidas e pode ser arrastado.
- `isChatOpen` (Android e iOS): a tela do chat está aberta.
- Android: `AtaliaOptions(notificationIcon = ...)` para o ícone da notificação com o app aberto.
- iOS: as funções de push podem ser chamadas de qualquer thread (delegates do iOS e do Firebase em Swift 6).

### Correções

- iOS: no primeiro toque no campo de mensagem, o teclado empurrava o chat para cima e deixava uma faixa vazia até ele.
- O topo da tela do chat ficava na cor padrão quando o chat abria antes de a cor do canal chegar (por exemplo, pelo toque numa notificação logo ao iniciar o app). A aparência do canal agora fica salva no aparelho.

## 0.1.0 (2026-10-25)

Primeira versão, Android e iOS.

- Chat da Atalia em tela cheia (`Atalia.open` / `Atalia.present`), com todos os recursos do webchat: mensagens ricas, anexos, câmera, nota de voz, avaliação, horário de atendimento.
- Identificação do usuário logado (`identify` com JWT) e `logout`, com ou sem o chat aberto.
- Contador de não lidas (`unreadCount`) e eventos (`ready`, `messageReceived`, `unreadChanged`...).
- Sessão guardada no Keychain (iOS) e cifrada pelo Android Keystore.
- Links abrem fora do app; downloads com o nome certo; o chat avisa a Atalia quando o app vai para segundo plano.
