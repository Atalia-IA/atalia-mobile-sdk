import Atalia, { type AtaliaLauncherOptions, useAtaliaState } from '@atalia-ia/react-native-chat';
import { useEffect, useState } from 'react';
import { Alert, Button, StyleSheet, Text, TextInput, View } from 'react-native';

const COLLAPSE: NonNullable<AtaliaLauncherOptions['collapse']>[] = ['circle', 'edge', 'never'];
const COLLAPSE_LABEL = { circle: 'recolhe em círculo', edge: 'recolhe na borda', never: 'sempre com o nome' };

/** Tela mínima: abrir o chat, identificar, sair, ver as não lidas e trocar o botão flutuante. */
export default function App() {
  const { unreadCount, isIdentified, isChatOpen } = useAtaliaState();
  const [jwt, setJwt] = useState('');
  const [collapse, setCollapse] = useState<(typeof COLLAPSE)[number]>('circle');
  const [draggable, setDraggable] = useState(false);

  // O plugin do Expo já configurou o SDK com o token do app.config.js.
  useEffect(() => {
    Atalia.showLauncher({ collapse, draggable });
  }, [collapse, draggable]);

  const identify = async () => {
    try {
      const contact = await Atalia.identify(jwt.trim());
      Alert.alert('Identificado', contact.externalId);
    } catch (error) {
      Alert.alert('Falhou', String((error as { code?: string }).code ?? error));
    }
  };

  return (
    <View style={styles.screen}>
      <View style={styles.content}>
        <Text style={styles.status}>
          Não lidas: {unreadCount}
          {'\n'}
          {isIdentified ? 'Usuário identificado' : 'Visitante anônimo'}
          {isChatOpen ? ' · chat aberto' : ''}
        </Text>
        <Button title="Abrir chat" onPress={Atalia.open} />
        <TextInput
          style={styles.input}
          placeholder="JWT de identificação (opcional)"
          autoCapitalize="none"
          autoCorrect={false}
          value={jwt}
          onChangeText={setJwt}
        />
        <Button title="Identificar" onPress={identify} />
        <Button title="Sair" onPress={() => Atalia.logout()} />
        <Button
          title={`Botão flutuante: ${COLLAPSE_LABEL[collapse]}`}
          onPress={() => setCollapse(COLLAPSE[(COLLAPSE.indexOf(collapse) + 1) % COLLAPSE.length])}
        />
        <Button title={`Arrastar o botão: ${draggable ? 'ligado' : 'desligado'}`} onPress={() => setDraggable(!draggable)} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, paddingTop: 48, backgroundColor: '#fafafa' },
  content: { padding: 24, gap: 12 },
  status: { fontSize: 16, marginBottom: 8 },
  input: { borderBottomWidth: 1, borderColor: '#999', paddingVertical: 8, fontSize: 16 },
});
