import { NativeModule, requireNativeModule } from 'expo';

import type { AtaliaContact, AtaliaEvent, AtaliaLauncherOptions, AtaliaOptions, AtaliaState } from './types';

type NativeEvents = {
  onEvent: (event: AtaliaEvent) => void;
};

declare class AtaliaChatNativeModule extends NativeModule<NativeEvents> {
  configure(channelToken: string, options: AtaliaOptions): void;
  open(): void;
  openConversation(conversationId: number): void;
  close(): void;
  identify(jwt: string): Promise<AtaliaContact>;
  logout(): Promise<void>;
  refreshUnread(): Promise<number>;
  getState(): Promise<AtaliaState>;
  setPushToken(token: string): void;
  setAPNsToken(token: string, environment: string): void;
  clearPushToken(): void;
  handleNotification(data: Record<string, string>, title: string | null, body: string | null): Promise<boolean>;
  handleNotificationTap(data: Record<string, string>): boolean;
  showLauncher(options: AtaliaLauncherOptions): void;
  hideLauncher(): void;
}

export default requireNativeModule<AtaliaChatNativeModule>('AtaliaChat');
