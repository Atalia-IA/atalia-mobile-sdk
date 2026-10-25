import { useEffect, useState } from 'react';
import { Platform } from 'react-native';

import Native from './AtaliaChatModule';
import { isAtaliaNotification, notificationData, notificationText } from './notifications';
import type {
  AtaliaContact,
  AtaliaEvent,
  AtaliaLauncherOptions,
  AtaliaNotificationInput,
  AtaliaOptions,
  AtaliaPushEnvironment,
  AtaliaState,
} from './types';

export * from './types';
export { isAtaliaNotification } from './notifications';

/** Devolvido por {@link addListener}: chame `remove()` para parar de ouvir. */
export type AtaliaSubscription = { remove(): void };

/**
 * Configura o SDK com o token do canal (Configurações › Canais › Webchat, no painel da Atalia).
 * Com o plugin do Expo (`channelToken` no app.json), o SDK já nasce configurado e esta chamada
 * só é necessária para trocar as opções.
 */
export function configure(channelToken: string, options: AtaliaOptions = {}): void {
  Native.configure(channelToken, options);
}

/** Abre o chat por cima do app. */
export function open(): void {
  Native.open();
}

/** Abre o chat direto numa conversa. Com o chat já aberto, só troca de conversa. */
export function openConversation(conversationId: number): void {
  Native.openConversation(conversationId);
}

/** Fecha o chat aberto por {@link open} (iOS; no Android o visitante fecha pela própria tela). */
export function close(): void {
  Native.close();
}

/**
 * Vincula o visitante ao usuário logado no app. O JWT é assinado pelo SEU backend com o segredo
 * do canal (nunca no app) e vale no máximo 1 hora. Gere um novo a cada chamada.
 * Em caso de erro, `error.code` traz o código da Atalia (`EXPIRED`, `INVALID_SIGNATURE`...).
 */
export function identify(jwt: string): Promise<AtaliaContact> {
  return Native.identify(jwt);
}

/** Encerra a sessão do usuário identificado. Chame no logout do app. */
export function logout(): Promise<void> {
  return Native.logout();
}

/** Consulta as não lidas na Atalia. */
export function refreshUnread(): Promise<number> {
  return Native.refreshUnread();
}

/** Não lidas, identificação e se o chat está aberto. Para a tela, prefira {@link useAtaliaState}. */
export function getState(): Promise<AtaliaState> {
  return Native.getState();
}

/** Recebe os eventos do chat. */
export function addListener(listener: (event: AtaliaEvent) => void): AtaliaSubscription {
  return Native.addListener('onEvent', listener);
}

// Push

/**
 * Token do Firebase Cloud Messaging (a Atalia envia pelo Firebase do app). O SDK registra o
 * aparelho na Atalia e o mantém registrado quando o usuário entra ou sai.
 */
export function setPushToken(token: string): void {
  Native.setPushToken(token);
}

/**
 * iOS: token do APNs em hexadecimal, para quem entrega pelo próprio backend (modo webhook).
 * É o `data` de `getDevicePushTokenAsync()` do expo-notifications no iOS. No Android não faz nada.
 */
export function setAPNsToken(token: string, environment: AtaliaPushEnvironment): void {
  Native.setAPNsToken(token, environment);
}

/** Tira o aparelho da Atalia (o usuário desligou as notificações do atendimento no app). */
export function clearPushToken(): void {
  Native.clearPushToken();
}

/**
 * Para o handler de notificações com o app aberto (ex.: `setNotificationHandler` do
 * expo-notifications): `false` quando a notificação é da Atalia e o chat já está na tela, porque a
 * mensagem já aparece nele. Notificação de outra origem devolve `true`: decida você.
 */
export async function shouldShowNotification(notification: AtaliaNotificationInput): Promise<boolean> {
  if (!isAtaliaNotification(notification)) return true;
  refreshUnread().catch(() => {});
  const state = await getState();
  return !state.isChatOpen;
}

/**
 * Com o app aberto, para quem recebe o push pelo Firebase (`onMessage` do
 * @react-native-firebase/messaging): no Android mostra a notificação (a não ser que o chat esteja
 * na tela); no iOS só atualiza as não lidas, porque quem mostra é o sistema.
 * @returns `false` quando a notificação não é da Atalia (trate como as suas).
 */
export async function handleNotification(notification: AtaliaNotificationInput): Promise<boolean> {
  const data = notificationData(notification);
  if (!data || data.atalia !== '1') return false;
  const { title, body } = notificationText(notification);
  return Native.handleNotification(data, title, body);
}

/**
 * Toque na notificação: abre o chat na conversa dela, inclusive quando o toque é que abriu o app.
 * Passe a resposta do expo-notifications ou a `RemoteMessage` do Firebase.
 * @returns `false` quando a notificação não é da Atalia.
 */
export function handleNotificationTap(notification: AtaliaNotificationInput): boolean {
  const data = notificationData(notification);
  if (!data || data.atalia !== '1') return false;
  return Native.handleNotificationTap(data);
}

// Botão flutuante

/**
 * Mostra o botão flutuante que abre o chat, por cima de todas as telas. A aparência vem do painel;
 * posição e comportamento, de `options`. Chamar de novo troca as opções.
 */
export function showLauncher(options: AtaliaLauncherOptions = {}): void {
  Native.showLauncher(options);
}

/** Tira o botão flutuante. */
export function hideLauncher(): void {
  Native.hideLauncher();
}

// Hooks

const INITIAL_STATE: AtaliaState = { unreadCount: 0, isIdentified: false, isChatOpen: false };

/** Estado do SDK para a tela: não lidas, identificação e se o chat está aberto. Atualiza sozinho. */
export function useAtaliaState(): AtaliaState {
  const [state, setState] = useState<AtaliaState>(INITIAL_STATE);
  useEffect(() => {
    let active = true;
    const sync = () => {
      getState()
        .then((next) => active && setState(next))
        .catch(() => {});
    };
    sync();
    const subscription = addListener((event) => {
      if (event.type === 'unreadChanged') {
        setState((current) => ({ ...current, unreadCount: event.count }));
      } else if (event.type === 'identified' || event.type === 'loggedOut' || event.type === 'opened' || event.type === 'closed') {
        sync();
      }
    });
    return () => {
      active = false;
      subscription.remove();
    };
  }, []);
  return state;
}

/** Só as não lidas, para um badge. */
export function useAtaliaUnreadCount(): number {
  return useAtaliaState().unreadCount;
}

/** Ouve os eventos do chat enquanto o componente estiver na tela. */
export function useAtaliaEvent(listener: (event: AtaliaEvent) => void): void {
  useEffect(() => {
    const subscription = addListener(listener);
    return () => subscription.remove();
  }, [listener]);
}

/** Plataforma onde o SDK roda (o mesmo de `Platform.OS`): só iOS e Android. */
export const isSupported = Platform.OS === 'ios' || Platform.OS === 'android';

const Atalia = {
  configure,
  open,
  openConversation,
  close,
  identify,
  logout,
  refreshUnread,
  getState,
  addListener,
  setPushToken,
  setAPNsToken,
  clearPushToken,
  isAtaliaNotification,
  shouldShowNotification,
  handleNotification,
  handleNotificationTap,
  showLauncher,
  hideLauncher,
};

export default Atalia;
