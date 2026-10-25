# @atalia/react-native-chat

O chat de atendimento da [Atalia](https://atalia.ai) no seu app React Native ou Expo. É o mesmo SDK nativo de Android e iOS, chamado pelo JavaScript:

- chat com mensagens ricas, anexos, câmera, nota de voz e avaliação;
- identificação do usuário logado;
- contador de não lidas;
- notificações push que abrem a conversa certa;
- botão flutuante opcional, com a aparência configurada no painel.

Funciona com Expo e com React Native puro (com os módulos do Expo). Não roda no Expo Go: use um development build (`npx expo run:android`, `npx expo run:ios` ou EAS Build).

## Instalar

```bash
npx expo install @atalia/react-native-chat
```

Sem Expo: `npx install-expo-modules@latest`, depois `npm install @atalia/react-native-chat` e `cd ios && pod install`.

## Configurar

```json title="app.json"
{
  "expo": {
    "plugins": [["@atalia/react-native-chat", { "channelToken": "<token-do-canal>" }]]
  }
}
```

O token fica no painel da Atalia, em Configurações › Canais › Editar canal › Instalação › Token do Widget. O plugin também cuida das permissões de câmera, microfone e notificações.

## Usar

```tsx
import Atalia, { useAtaliaUnreadCount } from '@atalia/react-native-chat';

function BotaoSuporte() {
  const unread = useAtaliaUnreadCount();
  return <Button title={`Suporte (${unread})`} onPress={() => Atalia.open()} />;
}

// Usuário logado: o JWT vem do seu backend, assinado com o segredo do canal.
await Atalia.identify(jwt);

// Botão flutuante por cima de todas as telas.
Atalia.showLauncher({ bottomMargin: 72 });
```

## Documentação

Guia completo, push com Firebase ou expo-notifications e referência de todos os tipos: **[chat.atalia.ai/docs/sdk/react-native](https://chat.atalia.ai/docs/sdk/react-native)**.

O código fica no repositório [Atalia-IA/atalia-mobile-sdk](https://github.com/Atalia-IA/atalia-mobile-sdk), junto dos SDKs nativos. Licença MIT.
