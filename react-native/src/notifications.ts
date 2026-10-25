import type { AtaliaNotificationInput } from './types';

/** Valores do `data` como o SDK nativo recebe do Firebase: sempre texto. */
export type NotificationData = Record<string, string>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function toStrings(value: Record<string, unknown>): NotificationData {
  const out: NotificationData = {};
  for (const [key, item] of Object.entries(value)) {
    if (typeof item === 'string') out[key] = item;
    else if (typeof item === 'number' || typeof item === 'boolean') out[key] = String(item);
  }
  return out;
}

function isAtaliaData(value: unknown): value is Record<string, unknown> {
  return isRecord(value) && String(value.atalia) === '1';
}

/**
 * Acha o `data` da notificação, onde cada biblioteca o guarda:
 * - expo-notifications: `request.content.data`, ou o payload cru em `request.trigger.payload`
 *   (iOS) e `request.trigger.remoteMessage.data` (Android);
 * - Firebase: `RemoteMessage.data`;
 * - ou o próprio `data`.
 */
export function notificationData(input: AtaliaNotificationInput): NotificationData | null {
  if (!isRecord(input)) return null;
  const notification = isRecord(input.notification) && isRecord(input.notification.request) ? input.notification : input;
  const request = isRecord(notification.request) ? notification.request : null;
  const candidates: unknown[] = [];
  if (request) {
    const content = isRecord(request.content) ? request.content : null;
    const trigger = isRecord(request.trigger) ? request.trigger : null;
    const remoteMessage = trigger && isRecord(trigger.remoteMessage) ? trigger.remoteMessage : null;
    candidates.push(content?.data, trigger?.payload, remoteMessage?.data);
  }
  candidates.push(input.data, input);
  const atalia = candidates.find(isAtaliaData);
  if (atalia) return toStrings(atalia);
  const first = candidates.find(isRecord);
  return first ? toStrings(first) : null;
}

/** A notificação veio da Atalia? Use antes de tratar as suas. */
export function isAtaliaNotification(input: AtaliaNotificationInput): boolean {
  return notificationData(input)?.atalia === '1';
}

/** Título e corpo, quando a biblioteca entrega a notificação inteira. */
export function notificationText(input: AtaliaNotificationInput): { title: string | null; body: string | null } {
  if (!isRecord(input)) return { title: null, body: null };
  const notification = isRecord(input.notification) ? input.notification : null;
  const request = notification && isRecord(notification.request) ? notification.request : isRecord(input.request) ? input.request : null;
  const content = request && isRecord(request.content) ? request.content : null;
  const source = content ?? notification;
  const title = source && typeof source.title === 'string' ? source.title : null;
  const body = source && typeof source.body === 'string' ? source.body : null;
  return { title, body };
}
