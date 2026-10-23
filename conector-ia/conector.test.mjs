import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startConnector } from './conector.mjs';

class FakeWebSocket {
  static instances = [];
  constructor(url) {
    this.url = url;
    this.sent = [];
    this.listeners = {};
    FakeWebSocket.instances.push(this);
    queueMicrotask(() => this.emit('open', {}));
  }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  emit(type, ev) { (this.listeners[type] || []).forEach((fn) => fn(ev)); }
  send(data) { this.sent.push(JSON.parse(data)); }
  close() { this.emit('close', { code: 1000, reason: '' }); }
  receive(obj) { this.emit('message', { data: JSON.stringify(obj) }); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(cond, ms = 2000) {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error('timeout esperando condición');
    await sleep(5);
  }
}

function ndjsonResponse(lines) {
  const enc = new TextEncoder();
  return new Response(new ReadableStream({
    start(c) { for (const l of lines) c.enqueue(enc.encode(JSON.stringify(l) + '\n')); c.close(); },
  }), { status: 200 });
}

function setup(fetchImpl, extra = {}) {
  FakeWebSocket.instances = [];
  const logs = [];
  const c = startConnector({
    serverUrl: 'ws://x/ws', secret: 's3', ollamaUrl: 'http://o', model: 'm',
    log: (m) => logs.push(m), WebSocketImpl: FakeWebSocket, fetchImpl, ...extra,
  });
  return { c, logs, ws: () => FakeWebSocket.instances.at(-1) };
}

test('sendsHelloWithSecretAndModel', async () => {
  const { c, ws } = setup(async () => { throw new Error('no'); });
  await until(() => ws() && ws().sent.length === 1);
  assert.deepEqual(ws().sent[0], { kind: 'hello', secret: 's3', model: 'm' });
  await c.stop();
});

test('streamsChunksThenDone', async () => {
  const f = async () => ndjsonResponse([
    { message: { content: 'Ho' } }, { message: { content: 'la' } }, { message: { content: '' }, done: true }]);
  const { c, ws } = setup(f);
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'job', id: 'j1', messages: [], format: null, options: {} });
  await until(() => ws().sent.some((m) => m.kind === 'done'));
  assert.deepEqual(ws().sent.slice(1), [
    { kind: 'chunk', id: 'j1', text: 'Ho' },
    { kind: 'chunk', id: 'j1', text: 'la' },
    { kind: 'done', id: 'j1', content: 'Hola' }]);
  await c.stop();
});

test('passesFormatAndOptionsToOllama', async () => {
  const bodies = [];
  const f = async (url, init) => {
    bodies.push({ url, body: JSON.parse(init.body) });
    return ndjsonResponse([{ message: { content: 'x' }, done: true }]);
  };
  const { c, ws } = setup(f);
  await until(() => ws() && ws().sent.length === 1);
  const msgs = [{ role: 'user', content: 'hi' }];
  ws().receive({ kind: 'job', id: 'a', messages: msgs, format: { type: 'object' }, options: { num_ctx: 4096, temperature: 0.3 } });
  await until(() => ws().sent.some((m) => m.kind === 'done'));
  ws().receive({ kind: 'job', id: 'b', messages: msgs, format: null, options: { num_ctx: 4096 } });
  await until(() => bodies.length === 2 && ws().sent.filter((m) => m.kind === 'done').length === 2);
  assert.equal(bodies[0].url, 'http://o/api/chat');
  assert.deepEqual(bodies[0].body.format, { type: 'object' });
  assert.equal(bodies[0].body.options.num_ctx, 4096);
  assert.equal(bodies[0].body.stream, true);
  assert.equal(bodies[0].body.model, 'm');
  assert.deepEqual(bodies[0].body.messages, msgs);
  assert.equal('format' in bodies[1].body, false);
  await c.stop();
});

test('ollamaErrorBecomesErrorMessage', async () => {
  const { c, ws } = setup(async () => new Response('boom', { status: 500 }));
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'job', id: 'e1', messages: [], format: null, options: {} });
  await until(() => ws().sent.some((m) => m.kind === 'error'));
  const err = ws().sent.find((m) => m.kind === 'error');
  assert.equal(err.id, 'e1');
  assert.match(err.detail, /500/);
  await c.stop();
});

test('reconnectsWithBackoffAndSaysHelloAgain', async () => {
  const { c, ws } = setup(async () => { throw new Error('no'); }, { backoffBaseMs: 10, backoffMaxMs: 40 });
  await until(() => ws() && ws().sent.length === 1);
  const first = ws();
  first.close();
  await until(() => FakeWebSocket.instances.length === 2);
  const second = ws();
  await until(() => second.sent.length === 1);
  assert.deepEqual(second.sent[0], { kind: 'hello', secret: 's3', model: 'm' });
  await c.stop();
});

test('badSecretLogsClearMessageAndKeepsRetrying', async () => {
  const { c, ws, logs } = setup(async () => { throw new Error('no'); }, { backoffBaseMs: 10, backoffMaxMs: 20 });
  await until(() => ws() && ws().sent.length === 1);
  ws().emit('close', { code: 4001, reason: 'Bad secret' });
  await until(() => FakeWebSocket.instances.length === 2);
  assert.ok(logs.some((l) => l.includes('Secreto rechazado por el servidor: revisa IA_CONECTOR_SECRETO')));
  await c.stop();
});

test('answersPingWithPong', async () => {
  const { c, ws } = setup(async () => { throw new Error('no'); });
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'ping' });
  assert.deepEqual(ws().sent.at(-1), { kind: 'pong' });
  await c.stop();
});

// Ollama falso que se queda colgado hasta que se aborta la señal.
function hangingFetch(calls) {
  return (url, init) => new Promise((_, reject) => {
    calls.push(init);
    init.signal.addEventListener('abort', () => reject(new DOMException('abort', 'AbortError')));
  });
}

test('newJobAbortsRunningJobWithoutErrorForOldId', async () => {
  const calls = [];
  let n = 0;
  const f = (url, init) => (n++ === 0 ? hangingFetch(calls)(url, init)
    : Promise.resolve(ndjsonResponse([{ message: { content: 'ok' }, done: true }])));
  const { c, ws } = setup(f);
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'job', id: 'old', messages: [], format: null, options: {} });
  await until(() => calls.length === 1);
  ws().receive({ kind: 'job', id: 'new', messages: [], format: null, options: {} });
  await until(() => ws().sent.some((m) => m.kind === 'done'));
  assert.equal(calls[0].signal.aborted, true);
  assert.deepEqual(ws().sent.slice(1), [
    { kind: 'chunk', id: 'new', text: 'ok' }, { kind: 'done', id: 'new', content: 'ok' }]);
  await c.stop();
});

test('socketCloseAbortsRunningJobAndSendsNothing', async () => {
  const calls = [];
  const { c, ws } = setup(hangingFetch(calls), { backoffBaseMs: 10 });
  await until(() => ws() && ws().sent.length === 1);
  const first = ws();
  first.receive({ kind: 'job', id: 'j', messages: [], format: null, options: {} });
  await until(() => calls.length === 1);
  first.close();
  await until(() => calls[0].signal.aborted);
  await sleep(30);
  assert.deepEqual(first.sent.map((m) => m.kind), ['hello']);
  await c.stop();
});

test('stopAbortsRunningGenerationAndReturns', async () => {
  const calls = [];
  const { c, ws } = setup(hangingFetch(calls));
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'job', id: 'j', messages: [], format: null, options: {} });
  await until(() => calls.length === 1);
  await c.stop();
  assert.equal(calls[0].signal.aborted, true);
});

test('joinsNdjsonLineSplitAcrossStreamChunks', async () => {
  const enc = new TextEncoder();
  const line = JSON.stringify({ message: { content: 'Hola' } }) + '\n';
  const last = JSON.stringify({ message: { content: '' }, done: true }) + '\n';
  const f = async () => new Response(new ReadableStream({
    start(ctl) {
      ctl.enqueue(enc.encode(line.slice(0, 10)));
      ctl.enqueue(enc.encode(line.slice(10) + last));
      ctl.close();
    },
  }), { status: 200 });
  const { c, ws } = setup(f);
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'job', id: 's', messages: [], format: null, options: {} });
  await until(() => ws().sent.some((m) => m.kind === 'done'));
  assert.deepEqual(ws().sent.slice(1), [
    { kind: 'chunk', id: 's', text: 'Hola' }, { kind: 'done', id: 's', content: 'Hola' }]);
  await c.stop();
});

// ---- final fix wave ----

test('errorWithoutCloseStillReconnects', async () => {
  const { c, ws } = setup(async () => { throw new Error('no'); }, { backoffBaseMs: 10, backoffMaxMs: 20 });
  await until(() => ws() && ws().sent.length === 1);
  const first = ws();
  let closed = 0;
  first.close = () => { closed += 1; }; // Node 22 puede disparar error sin close
  first.emit('error', {});
  await until(() => FakeWebSocket.instances.length === 2);
  assert.equal(closed, 1);
  await until(() => ws().sent.length === 1);
  assert.deepEqual(ws().sent[0], { kind: 'hello', secret: 's3', model: 'm' });
  await c.stop();
});

test('errorThenCloseReconnectsOnlyOnce', async () => {
  const { c, ws } = setup(async () => { throw new Error('no'); }, { backoffBaseMs: 10, backoffMaxMs: 20 });
  await until(() => ws() && ws().sent.length === 1);
  ws().emit('error', {});
  await until(() => FakeWebSocket.instances.length === 2);
  await sleep(60);
  assert.equal(FakeWebSocket.instances.length, 2);
  await c.stop();
});

test('silentServerIsDroppedAndReconnected', async () => {
  const { c, ws, logs } = setup(async () => { throw new Error('no'); },
    { backoffBaseMs: 10, backoffMaxMs: 20, livenessMs: 60 });
  await until(() => ws() && ws().sent.length === 1);
  await until(() => FakeWebSocket.instances.length === 2, 1000);
  assert.ok(logs.some((l) => l.includes('sin noticias del servidor')));
  await c.stop();
});

test('pingsKeepTheConnectionAlive', async () => {
  const { c, ws } = setup(async () => { throw new Error('no'); }, { livenessMs: 80 });
  await until(() => ws() && ws().sent.length === 1);
  for (let i = 0; i < 8; i++) {
    await sleep(25);
    ws().receive({ kind: 'ping' });
  }
  assert.equal(FakeWebSocket.instances.length, 1);
  await c.stop();
});

test('defaultLivenessIsLongerThanTheServerPing', async () => {
  const { livenessMsDefault } = await import('./conector.mjs');
  assert.equal(livenessMsDefault, 50_000);
});

test('keepsTheModelLoadedBetweenQuestions', async () => {
  const bodies = [];
  const f = async (url, init) => {
    bodies.push(JSON.parse(init.body));
    return ndjsonResponse([{ message: { content: 'x' }, done: true }]);
  };
  const { c, ws } = setup(f);
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'job', id: 'k', messages: [], format: null, options: {} });
  await until(() => ws().sent.some((m) => m.kind === 'done'));
  assert.equal(bodies[0].keep_alive, '30m');
  await c.stop();
});

test('cancelAbortsThatJobAndSendsNothingForIt', async () => {
  const calls = [];
  const { c, ws } = setup(hangingFetch(calls));
  await until(() => ws() && ws().sent.length === 1);
  ws().receive({ kind: 'job', id: 'j', messages: [], format: null, options: {} });
  await until(() => calls.length === 1);
  ws().receive({ kind: 'cancel', id: 'otro' });
  await sleep(20);
  assert.equal(calls[0].signal.aborted, false);
  ws().receive({ kind: 'cancel', id: 'j' });
  await until(() => calls[0].signal.aborted);
  await sleep(20);
  assert.deepEqual(ws().sent.map((m) => m.kind), ['hello']);
  await c.stop();
});
