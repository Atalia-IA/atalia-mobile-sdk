// Roda sobre o build (npm test compila antes): o mesmo JavaScript que vai para o app.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const { notificationData, isAtaliaNotification, notificationText } = require('../build/notifications');
const fixture = require(path.join(__dirname, '..', '..', 'protocol', 'fixtures', 'push-payload.json'));

const data = fixture.atalia[0].data;

test('aceita o data puro (fixture compartilhada com os SDKs nativos)', () => {
  for (const item of fixture.atalia) assert.equal(isAtaliaNotification(item.data), true, item.name);
  for (const item of fixture.notAtalia) assert.equal(isAtaliaNotification(item.data), false, item.name);
});

test('expo-notifications no Android: data em request.content.data', () => {
  const notification = { request: { content: { title: 'Ana', body: 'Oi', data }, trigger: { type: 'push' } } };
  assert.deepEqual(notificationData(notification), data);
  assert.deepEqual(notificationText(notification), { title: 'Ana', body: 'Oi' });
});

test('expo-notifications no iOS: payload cru do APNs em request.trigger.payload', () => {
  const payload = { aps: { alert: { title: 'Ana' } }, ...data };
  const response = { notification: { request: { content: { title: 'Ana', body: 'Oi', data: {} }, trigger: { type: 'push', payload } } } };
  const found = notificationData(response);
  assert.equal(found.atalia, '1');
  assert.equal(found.conversation_id, data.conversation_id);
  assert.equal(found.aps, undefined, 'só valores simples viram texto');
});

test('Firebase: RemoteMessage.data', () => {
  const message = { data, notification: { title: 'Ana', body: 'Oi' } };
  assert.deepEqual(notificationData(message), data);
  assert.deepEqual(notificationText(message), { title: 'Ana', body: 'Oi' });
});

test('números do backend do app (modo webhook) viram texto', () => {
  const found = notificationData({ atalia: 1, conversation_id: 812 });
  assert.deepEqual(found, { atalia: '1', conversation_id: '812' });
});

test('entrada vazia ou de outra origem', () => {
  assert.equal(notificationData(null), null);
  assert.equal(isAtaliaNotification(undefined), false);
  assert.equal(isAtaliaNotification({ request: { content: { data: { campaign: 'x' } } } }), false);
});
