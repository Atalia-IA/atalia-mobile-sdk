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
| `page` | texto (até 2048) | Tela do app em que o visitante está: a URL equivalente do site (`https://exemplo.com.br/cursos/10`) ou um caminho (`/cursos/10`). É o que as regras por página do canal comparam. Ausente: só valem as regras de "todas as páginas". |
| `notice` | `{ ruleId, button? }` | Abre direto no aviso dessa regra (toque no balão do aviso). Com `button` (posição do botão de fluxo tocado no balão), o aviso já vira conversa. |
| `background` | booleano | `true` = chat pré-carregado pelo botão flutuante: a página nasce em segundo plano (sem WebSocket, sem heartbeat, sem marcar lidas) até o `app_state` `foreground`. Ausente = primeiro plano. |

O widget só aceita esses dados quando encontra o bridge nativo (`window.webkit.messageHandlers.atalia` no iOS, `window.AtaliaAndroid` no Android). Num navegador comum o fragmento é descartado.

## Camada dos balões

Com o chat fechado, os balões do widget (aviso das regras por página, mensagem nova e boas-vindas) aparecem numa **segunda página**, num WebView transparente que o SDK põe por cima das telas do app, acima do botão flutuante:

```text
<baseUrl>/chat/<channelToken>?sdk=popup#sdk=<base64url(JSON)>   canal na Atalia
<baseUrl>/?sdk=popup#sdk=<base64url(JSON)>                      domínio próprio do canal
```

O fragmento é o mesmo, só com `protocol`, `platform`, `sdkVersion`, `appVersion`, `session` e `page`. Nunca `identity`, `conversationId`, `notice` nem `background`.

Regras desta página:

- Ela não tem painel nem botão: só desenha o balão, ocupando o WebView inteiro menos 12 px de margem transparente (a sombra do balão cabe dentro do WebView). O SDK dá a LARGURA (314 pt/dp, ou a largura da tela menos 16 quando não couber) e a página avisa a ALTURA por `popup_changed`.
- O WebView fica na hierarquia desde o começo, invisível e sem receber toque, e só aparece (e passa a receber toque) com `popup_changed` `visible: true`. Precisa estar na hierarquia para a página rodar.
- O toque no balão não abre nada na página: ela pede `open_chat` e o SDK abre a tela do chat.
- Das mensagens desta página o SDK trata só `popup_changed`, `open_chat`, `open_url` e `ready` (para liberar os comandos enfileirados). Todas as outras (`session_changed`, `unread_changed`, `error`...) são **ignoradas**: quem fala pela sessão e pelos eventos públicos é a tela do chat.
- Ela não avisa presença (nem heartbeat, nem offline): não muda quando o push é enviado.
- Comandos que ela recebe: `set_page` e `app_state`. O SDK manda `app_state: background` quando a tela do chat abre ou o app sai da tela, e `foreground` quando o chat fecha ou o app volta. Em `background` ela fecha a conexão e tira o balão; em `foreground` recarrega a sessão (conversas e não lidas), então pega o que mudou enquanto o chat estava aberto.
- Sessão trocada (`session_changed` da tela do chat com outro token, `identify`, `logout`): o SDK recarrega esta página com o fragmento novo.

## Web → nativo

Sempre uma **string JSON** `{ "v": 1, "type": "...", "payload": {...} | null }`.

| `type` | `payload` | O SDK faz |
|---|---|---|
| `session_changed` | `{ sessionToken, identified, externalId }` | Guarda a sessão no Keychain/Keystore. |
| `ready` | `null` | Evento `ready`; libera comandos enfileirados. |
| `close` | `null` | Fecha a tela do chat. |
| `background_changed` | `{ color }` (`#rrggbb`) | Pinta a faixa abaixo do chat (atrás do teclado e da barra do sistema) com o fundo do painel do widget, que muda com a tela (branco na home, o da conversa nela). |
| `unread_changed` | `{ count }` | Atualiza o contador de não lidas. |
| `message_received` | `{ conversationId, messageId }` | Evento público. |
| `conversation_started` / `conversation_closed` | `{ conversationId }` | Evento público. |
| `identified` | `{ externalId, contactId }` | Evento público. |
| `logout` | `null` | Evento público `loggedOut`. |
| `open_url` | `{ url }` | Abre fora do WebView (navegador, discador, WhatsApp). |
| `download` | `{ url, filename, mimeType }` | Baixa o arquivo com o nome certo. |
| `error` | `{ code, command? }` | Evento público `error`. |
| `popup_changed` | `{ visible, kind?, width?, height? }` | Só da camada dos balões. `visible: true` traz `kind` (`notice`, `message` ou `teaser`) e o tamanho que o WebView precisa ter, em px de CSS (= pt/dp), já com a margem da sombra. Pode repetir com outra altura (a imagem do aviso carregou). `visible: false`: esconde. |
| `open_chat` | `{ conversationId, noticeRuleId, noticeButton }` (cada um inteiro ou `null`) | Só da camada dos balões: o visitante tocou no balão. O SDK abre o chat: na conversa (`conversationId`), no aviso (`noticeRuleId`, com `noticeButton` quando o toque foi num botão de fluxo) ou do jeito padrão (tudo `null`). |

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
| `set_page` | `{ url }` (texto ou `null`) | Tela do app mudou (mesmo formato do `page` do fragmento). Vai para a tela do chat e para a camada dos balões. |
| `open_notice` | `{ ruleId, button }` (`button` inteiro ou `null`) | Chat já carregado (aberto ou pré-carregado): abre no aviso, como o `notice` do fragmento. |

`app_state` não é opcional: em segundo plano o widget fecha o WebSocket e avisa a Atalia que o visitante saiu. Sem o aviso, o visitante continua contando como conectado, e é por esse sinal que as notificações push sabem quando avisar.

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
