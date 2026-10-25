# Protocolo do bridge (v1)

Os SDKs abrem o chat da Atalia num WebView e conversam com ele por um bridge. Este diretório guarda o contrato que os quatro SDKs (Android, iOS, React Native e Flutter) precisam entender do mesmo jeito.

A fonte da verdade é o widget que a Atalia serve em `<baseUrl>/webchat.js`. Os arquivos em `fixtures/` são lidos pelos testes de cada SDK: mudou o contrato, muda a fixture, e os testes de todas as plataformas acusam.

## Abrir o chat

```text
<baseUrl>/chat/<channelToken>?sdk=1#sdk=<base64url(JSON)>   canal na Atalia
<baseUrl>/?sdk=1#sdk=<base64url(JSON)>                      domínio próprio do canal
```

O JSON do fragmento:

| Campo | Tipo | Uso |
|---|---|---|
| `protocol` | inteiro | Versão do bridge que o SDK fala (hoje 1). |
| `platform` | texto | `android`, `ios`, `react-native` ou `flutter`. |
| `sdkVersion`, `appVersion` | texto | Diagnóstico. |
| `session` | `[A-Za-z0-9_-]{8,64}` | Sessão guardada pelo SDK. Ausente: o widget cria uma e avisa por `session_changed`. |
| `identity` | JWT | Opcional. Mesmo efeito do `identify`. |
| `conversationId` | inteiro | Abre direto nessa conversa (toque numa notificação). |
| `closeButton` | booleano | `false` esconde o botão "Fechar". |

O widget só aceita esses dados quando encontra o bridge nativo (`window.webkit.messageHandlers.atalia` no iOS, `window.AtaliaAndroid` no Android). Num navegador comum o fragmento é descartado.

## Web → nativo

Sempre uma **string JSON** `{ "v": 1, "type": "...", "payload": {...} | null }`.

| `type` | `payload` | O SDK faz |
|---|---|---|
| `session_changed` | `{ sessionToken, identified, externalId }` | Guarda a sessão no Keychain/Keystore. |
| `ready` | `null` | Evento `ready`; libera comandos enfileirados. |
| `close` | `null` | Fecha a tela do chat. |
| `unread_changed` | `{ count }` | Atualiza o contador de não lidas. |
| `message_received` | `{ conversationId, messageId }` | Evento público. |
| `conversation_started` / `conversation_closed` | `{ conversationId }` | Evento público. |
| `identified` | `{ externalId, contactId }` | Evento público. |
| `logout` | `null` | Evento público `loggedOut`. |
| `open_url` | `{ url }` | Abre fora do WebView (navegador, discador, WhatsApp). |
| `download` | `{ url, filename, mimeType }` | Baixa o arquivo com o nome certo. |
| `error` | `{ code, command? }` | Evento público `error`. |

Mensagem com `type` desconhecido, sem `type` ou que não seja JSON é **ignorada**, nunca erro: o protocolo só cresce.

## Nativo → web

`window.AtaliaChat._native({ type, payload })`, chamado depois do evento `ready`.

| `type` | `payload` |
|---|---|
| `identify` | `{ jwt }` |
| `logout` | `null` |
| `open_conversation` | `{ conversationId }` |
| `open_home` | `null` |
| `app_state` | `{ state: "background" \| "foreground" }` |

`app_state` não é opcional: em segundo plano o widget fecha o WebSocket e avisa a Atalia que o visitante saiu. Sem o aviso, o visitante continua contando como conectado, e é por esse sinal que as notificações push (próxima versão) vão saber quando avisar.

## Notificação push

Quando o visitante está fora do chat, a Atalia envia uma notificação pelo Firebase do app ou entrega o mesmo conteúdo ao backend do cliente por webhook. O `data` é igual nos dois casos, sempre com valores em texto (exigência do FCM):

| Chave | Valor |
|---|---|
| `atalia` | `"1"`: é assim que o SDK sabe que a notificação é da Atalia |
| `v` | versão deste formato (hoje `"1"`) |
| `channel_token` | canal que enviou |
| `conversation_id`, `message_id` | conversa a abrir no toque |
| `sender_type` | `agent`, `bot` ou `system` |
| `type` | `"test"` no teste enviado pelo painel (sem conversa) |

Notificações da mesma conversa se agrupam e a nova substitui a anterior: `tag` no Android e `thread-id`/`apns-collapse-id` no iOS, valor `atalia-conv-<conversation_id>`. No Android o canal de notificação é `atalia_chat`. Exemplos em `fixtures/push-payload.json`.

## Convenções

- Nome de evento e de comando em `snake_case`; chaves do payload em `camelCase`.
- Mudança incompatível exige `protocol: 2`. O widget mantém a versão anterior por 12 meses.
