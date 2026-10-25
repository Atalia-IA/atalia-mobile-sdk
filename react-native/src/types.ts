/** Opções de {@link configure}. */
export type AtaliaOptions = {
  /** Origem do chat. Troque só para homologação ou para o domínio próprio do canal. Padrão: `https://chat.atalia.ai`. */
  baseUrl?: string;
  /** `true` quando `baseUrl` é o domínio próprio vinculado ao canal (o chat fica em `/`). */
  customDomain?: boolean;
  /** Mostra o botão "Fechar" no chat. Padrão: `true`. */
  closeButton?: boolean;
  /** iOS: o chat abre em tela cheia (padrão) ou como folha. */
  presentationStyle?: 'fullScreen' | 'pageSheet';
  /** Permite inspecionar o WebView (Safari no iOS, `chrome://inspect` no Android). Nunca em produção. */
  debug?: boolean;
  /** iOS: com o push ligado, o número no ícone do app acompanha as não lidas. Padrão: `true`. */
  appBadge?: boolean;
};

/** Contato da Atalia vinculado ao usuário identificado. */
export type AtaliaContact = {
  externalId: string;
  contactId: number;
  name: string | null;
};

/** Eventos do chat, entregues a {@link addListener}. */
export type AtaliaEvent =
  /** O chat terminou de carregar. */
  | { type: 'ready' }
  /** A tela do chat abriu. */
  | { type: 'opened' }
  /** A tela do chat fechou. */
  | { type: 'closed' }
  /** O visitante foi identificado. */
  | { type: 'identified'; externalId: string; contactId: number }
  | { type: 'loggedOut' }
  | { type: 'conversationStarted'; conversationId: number }
  | { type: 'conversationClosed'; conversationId: number }
  /** Chegou mensagem do atendente, da IA ou do sistema. Só ids: o texto nunca sai do chat. */
  | { type: 'messageReceived'; conversationId: number; messageId: number }
  | { type: 'unreadChanged'; count: number }
  /** Falha do chat (`INIT_FAILED`) ou de um comando (`command` = "identify", "open_conversation"...). */
  | { type: 'error'; code: string; command: string | null };

export type AtaliaEventType = AtaliaEvent['type'];

/** Estado atual do SDK. */
export type AtaliaState = {
  /** Mensagens do atendente, da IA ou do sistema ainda não lidas pelo visitante. */
  unreadCount: number;
  /** A sessão atual pertence a um usuário identificado por {@link identify}. */
  isIdentified: boolean;
  /** A tela do chat está aberta. */
  isChatOpen: boolean;
};

/** Ambiente do token do APNs: `sandbox` nos builds instalados pelo Xcode, `production` no TestFlight e na App Store. */
export type AtaliaPushEnvironment = 'production' | 'sandbox';

/**
 * Notificação recebida pelo app. Aceita o `data` da notificação ou o objeto inteiro da biblioteca
 * de push: `Notification` e `NotificationResponse` do expo-notifications e `RemoteMessage` do
 * Firebase (@react-native-firebase/messaging).
 */
export type AtaliaNotificationInput = Record<string, unknown> | object | null | undefined;

/** Posição e comportamento do botão flutuante. A aparência (cor, texto e lado) vem do painel. */
export type AtaliaLauncherOptions = {
  /** Lado da tela. Padrão: o lado do painel. */
  side?: 'start' | 'end';
  /** Distância da lateral, em pontos/dp. Padrão: 16. */
  horizontalMargin?: number;
  /** Distância do fim da tela, além da área segura. Aumente para ficar acima de uma barra de abas. Padrão: 16. */
  bottomMargin?: number;
  /** Texto do botão aberto. Padrão: o do painel ("Fale conosco"). */
  text?: string;
  /** Cor do botão, `#RRGGBB`. Padrão: a do painel. */
  color?: string;
  /** Forma depois de recolher: `circle` (padrão), `edge` (aba na lateral) ou `never` (sempre com o texto). */
  collapse?: 'circle' | 'edge' | 'never';
  /** Milissegundos com o texto antes de recolher. Padrão: 5000. */
  collapseAfter?: number;
  /** O usuário pode arrastar o botão; ao soltar, ele gruda na lateral mais próxima. Padrão: `false`. */
  draggable?: boolean;
};
