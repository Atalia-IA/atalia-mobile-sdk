# Changelog

Versões seguem [SemVer](https://semver.org/lang/pt-BR/). O protocolo do bridge tem versão própria (ver [protocol/README.md](protocol/README.md)).

## 0.1.0 (não lançada)

Primeira versão, Android e iOS.

- Chat da Atalia em tela cheia (`Atalia.open` / `Atalia.present`), com todos os recursos do webchat: mensagens ricas, anexos, câmera, nota de voz, avaliação, horário de atendimento.
- Identificação do usuário logado (`identify` com JWT) e `logout`, com ou sem o chat aberto.
- Contador de não lidas (`unreadCount`) e eventos (`ready`, `messageReceived`, `unreadChanged`...).
- Sessão guardada no Keychain (iOS) e cifrada pelo Android Keystore.
- Links abrem fora do app; downloads com o nome certo; o chat avisa a Atalia quando o app vai para segundo plano.
- Botão flutuante opcional (`.ataliaLauncher()`, `AtaliaLauncherView`, `Atalia.showLauncher`) com a aparência do botão do site configurada no painel: abre com o texto, recolhe em círculo ou numa aba na borda, mostra as não lidas e pode ser arrastado.
- Notificações push: `setPushToken` (Firebase) e `setAPNsToken` (iOS, modo webhook) registram o aparelho na Atalia e o acompanham no `identify` e no `logout`. `handleNotification` (Android) e `presentationOptions(for:)` (iOS) não notificam com o chat na tela, e `handleNotificationTap` abre o chat na conversa. Lido tudo, as notificações da Atalia saem da bandeja; no iOS o número do ícone acompanha as não lidas (`appBadge`).