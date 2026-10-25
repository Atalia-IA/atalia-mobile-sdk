#!/usr/bin/env node
/**
 * O pod do pacote React Native compila o próprio SDK iOS (ios/Sources/AtaliaChat da raiz do
 * repositório), sem depender do CocoaPods central. O CocoaPods não aceita arquivo fora da pasta
 * do podspec, então o código é copiado para ios/vendor antes de publicar e de rodar o exemplo.
 * A cópia não vai para o git: a fonte única é ios/Sources/AtaliaChat.
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const source = path.resolve(root, '..', 'ios', 'Sources', 'AtaliaChat');
const target = path.join(root, 'ios', 'vendor', 'AtaliaChat');

if (!fs.existsSync(source)) {
  // Instalado do npm: a cópia já veio no pacote.
  if (fs.existsSync(target)) process.exit(0);
  console.error(`SDK iOS não encontrado em ${source}`);
  process.exit(1);
}

fs.rmSync(target, { recursive: true, force: true });
fs.cpSync(source, target, { recursive: true });
fs.copyFileSync(path.resolve(root, '..', 'LICENSE'), path.join(root, 'LICENSE'));
console.log('SDK iOS copiado para ios/vendor/AtaliaChat');
