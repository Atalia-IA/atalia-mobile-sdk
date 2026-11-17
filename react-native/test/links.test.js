// Roda sobre o build (npm test compila antes): o mesmo JavaScript que vai para o app.
const { test } = require('node:test');
const assert = require('node:assert/strict');

const { normalizeAppLinkHosts } = require('../build/links');

test('appLinkHosts: sobra só o host, em minúsculas e sem repetição', () => {
  assert.deepEqual(
    normalizeAppLinkHosts(['https://App.Exemplo.com.br/cursos?x=1', 'exemplo.com.br', '*.exemplo.com.br', ' loja.exemplo.com:8443 ']),
    ['app.exemplo.com.br', 'exemplo.com.br', 'loja.exemplo.com'],
  );
});

test('appLinkHosts: ignora o que não é host', () => {
  assert.deepEqual(normalizeAppLinkHosts(undefined), []);
  assert.deepEqual(normalizeAppLinkHosts('exemplo.com.br'), []);
  assert.deepEqual(normalizeAppLinkHosts(['', '   ', null, 42, 'com espaço.br', 'https://']), []);
});
