import fs from 'node:fs';
import path from 'node:path';
import {
  AndroidConfig,
  type ConfigPlugin,
  createRunOncePlugin,
  withAndroidManifest,
  withDangerousMod,
  withInfoPlist,
} from 'expo/config-plugins';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const pkg: { name: string; version: string } = require('../../package.json');

/** Opções do plugin no app.json / app.config: `["@atalia/react-native-chat", { ... }]`. */
export type AtaliaPluginProps = {
  /**
   * Token do canal. Com ele o SDK é configurado ao iniciar o app, antes do JavaScript: o toque numa
   * notificação que abre o app já encontra o chat pronto.
   */
  channelToken?: string;
  /** Outra origem do chat (homologação ou o domínio próprio do canal). */
  baseUrl?: string;
  /** `true` quando `baseUrl` é o domínio próprio vinculado ao canal. */
  customDomain?: boolean;
  /** iOS: texto do pedido de câmera. A chave é obrigatória: sem ela o iOS fecha o app na foto. */
  cameraPermission?: string;
  /** Texto do pedido de microfone (nota de voz), ou `false` para não pedir: o chat avisa o visitante. */
  microphonePermission?: string | false;
  /** Android 13+: declara POST_NOTIFICATIONS. Padrão: `true`. */
  notifications?: boolean;
  /**
   * Android: ícone pequeno das notificações, um PNG branco com fundo transparente (96×96), com o
   * caminho a partir da raiz do projeto. Vale para as notificações que o sistema mostra (app em
   * segundo plano) e para as que o SDK mostra. Sem ele, o Android usa o ícone do app, que aparece
   * como um quadrado branco.
   */
  notificationIcon?: string;
};

const CAMERA_TEXT = 'Para enviar fotos no chat de atendimento.';
const MICROPHONE_TEXT = 'Para gravar mensagens de voz no chat de atendimento.';

const META_CHANNEL_TOKEN = 'ai.atalia.chat.CHANNEL_TOKEN';
const META_BASE_URL = 'ai.atalia.chat.BASE_URL';
const META_CUSTOM_DOMAIN = 'ai.atalia.chat.CUSTOM_DOMAIN';
/** Ícone das notificações do Firebase. O módulo Android também o usa nas notificações do SDK. */
const META_FIREBASE_ICON = 'com.google.firebase.messaging.default_notification_icon';
const ICON_RESOURCE = 'atalia_notification_icon';

const withAtaliaIos: ConfigPlugin<AtaliaPluginProps> = (config, props) =>
  withInfoPlist(config, (mod) => {
    const plist = mod.modResults;
    plist.NSCameraUsageDescription = props.cameraPermission || plist.NSCameraUsageDescription || CAMERA_TEXT;
    if (props.microphonePermission !== false) {
      plist.NSMicrophoneUsageDescription = props.microphonePermission || plist.NSMicrophoneUsageDescription || MICROPHONE_TEXT;
    }
    const set = (key: string, value: string | boolean | undefined) => {
      if (value === undefined || value === '' || value === false) delete plist[key];
      else plist[key] = value;
    };
    set('AtaliaChannelToken', props.channelToken);
    set('AtaliaBaseURL', props.baseUrl);
    set('AtaliaCustomDomain', props.customDomain === true ? true : undefined);
    return mod;
  });

const withAtaliaAndroid: ConfigPlugin<AtaliaPluginProps> = (config, props) => {
  const permissions: string[] = [];
  if (props.microphonePermission !== false) {
    permissions.push('android.permission.RECORD_AUDIO', 'android.permission.MODIFY_AUDIO_SETTINGS');
  }
  if (props.notifications !== false) permissions.push('android.permission.POST_NOTIFICATIONS');
  config = AndroidConfig.Permissions.withPermissions(config, permissions);
  if (props.notificationIcon) config = withNotificationIcon(config, props.notificationIcon);

  return withAndroidManifest(config, (mod) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(mod.modResults);
    const set = (name: string, value: string | undefined) => {
      if (value) AndroidConfig.Manifest.addMetaDataItemToMainApplication(app, name, value);
      else AndroidConfig.Manifest.removeMetaDataItemFromMainApplication(app, name);
    };
    set(META_CHANNEL_TOKEN, props.channelToken);
    set(META_BASE_URL, props.baseUrl);
    set(META_CUSTOM_DOMAIN, props.customDomain === true ? 'true' : undefined);
    // Ícone já definido por outro plugin (expo-notifications, @react-native-firebase/messaging) vence:
    // é um só para todas as notificações do Firebase no app.
    const hasIcon = (app['meta-data'] ?? []).some((item) => item.$['android:name'] === META_FIREBASE_ICON);
    if (props.notificationIcon && !hasIcon) {
      AndroidConfig.Manifest.addMetaDataItemToMainApplication(app, META_FIREBASE_ICON, `@drawable/${ICON_RESOURCE}`, 'resource');
    }
    return mod;
  });
};

/** Copia o PNG para os recursos do app. Uma densidade só (xxxhdpi): o Android reduz para as outras. */
const withNotificationIcon: ConfigPlugin<string> = (config, icon) =>
  withDangerousMod(config, [
    'android',
    async (mod) => {
      const source = path.resolve(mod.modRequest.projectRoot, icon);
      if (path.extname(source).toLowerCase() !== '.png' || !fs.existsSync(source)) {
        throw new Error(`[@atalia/react-native-chat] notificationIcon: PNG não encontrado em ${source}`);
      }
      const dir = path.join(mod.modRequest.platformProjectRoot, 'app', 'src', 'main', 'res', 'drawable-xxxhdpi');
      await fs.promises.mkdir(dir, { recursive: true });
      await fs.promises.copyFile(source, path.join(dir, `${ICON_RESOURCE}.png`));
      return mod;
    },
  ]);

const withAtalia: ConfigPlugin<AtaliaPluginProps | void> = (config, props) => {
  const options = props || {};
  config = withAtaliaIos(config, options);
  config = withAtaliaAndroid(config, options);
  return config;
};

export default createRunOncePlugin(withAtalia, pkg.name, pkg.version);
