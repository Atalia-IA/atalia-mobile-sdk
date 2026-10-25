// ATALIA_CHANNEL_TOKEN=<token-do-canal> npx expo run:android (ou run:ios)
module.exports = {
  name: 'Atalia Chat RN',
  slug: 'atalia-chat-rn-example',
  version: '1.0.0',
  orientation: 'portrait',
  ios: { bundleIdentifier: 'ai.atalia.chat.rnexample' },
  android: { package: 'ai.atalia.chat.rnexample' },
  experiments: { autolinkingModuleResolution: true },
  plugins: [
    [
      '../app.plugin.js',
      {
        channelToken: process.env.ATALIA_CHANNEL_TOKEN,
        baseUrl: process.env.ATALIA_BASE_URL,
        notificationIcon: './assets/notification-icon.png',
      },
    ],
    // ATALIA_IOS_FRAMEWORKS=dynamic: como fica o app com o @react-native-firebase 27 (o CI compila os dois jeitos).
    ...(process.env.ATALIA_IOS_FRAMEWORKS
      ? [['expo-build-properties', { ios: { useFrameworks: process.env.ATALIA_IOS_FRAMEWORKS } }]]
      : []),
  ],
};
