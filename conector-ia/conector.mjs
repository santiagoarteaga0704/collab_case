// Conector de la IA local: une Ollama (en esta laptop) con el servidor de Collab CASE.
import { pathToFileURL } from 'node:url';

// El servidor manda un ping cada 20 s; si en 50 s no llega ninguno, la conexión está muerta.
export const livenessMsDefault = 50_000;

export function startConnector({
  serverUrl, secret, ollamaUrl = 'http://localhost:11434', model = 'qwen3:4b-instruct',
  log = console.log, WebSocketImpl = globalThis.WebSocket, fetchImpl = fetch,
  backoffBaseMs = 1000, backoffMaxMs = 15000, livenessMs = livenessMsDefault,
}) {
  let stopped = false;
  let socket = null;
  let timer = null;
  let attempt = 0;
  let work = Promise.resolve(); // atiende de a un trabajo
  let current = null; // AbortController del trabajo en curso
  let currentId = null; // id del trabajo en curso
  let watchdog = null; // vence si el servidor deja de mandar pings

  const abortCurrent = () => { current?.abort(); current = null; currentId = null; };

  const send = (ws, obj) => {
    try { ws.send(JSON.stringify(obj)); } catch { /* la conexión se cayó; el servidor reintenta */ }
  };

  async function runJob(ws, job, controller) {
    const { id } = job;
    const { signal } = controller;
    if (signal.aborted) return;
    try {
      const body = { model, messages: job.messages, stream: true, options: job.options, keep_alive: '30m' };
      if (job.format != null) body.format = job.format;
      const res = await fetchImpl(`${ollamaUrl}/api/chat`, {
        method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      if (!res.ok) {
        const detail = (await res.text().catch(() => '')).slice(0, 300);
        throw new Error(`Ollama respondió ${res.status} ${detail}`.trim());
      }
      const decoder = new TextDecoder();
      let pending = '';
      let full = '';
      let finished = false;
      const handleLine = (line) => {
        if (!line.trim()) return;
        const data = JSON.parse(line);
        if (data.error) throw new Error(`Ollama: ${data.error}`);
        const text = data.message?.content;
        if (text) {
          full += text;
          if (!signal.aborted) send(ws, { kind: 'chunk', id, text });
        }
        if (data.done) finished = true;
      };
      for await (const part of res.body) {
        pending += decoder.decode(part, { stream: true });
        let nl;
        while ((nl = pending.indexOf('\n')) >= 0) {
          handleLine(pending.slice(0, nl));
          pending = pending.slice(nl + 1);
        }
      }
      handleLine(pending);
      if (!finished) throw new Error('Ollama cerró la respuesta antes de terminar');
      if (signal.aborted) return;
      send(ws, { kind: 'done', id, content: full });
    } catch (e) {
      if (signal.aborted) return; // cancelado: el servidor ya dio por perdido este trabajo
      log(`Falló el trabajo ${id}: ${e.message}`);
      send(ws, { kind: 'error', id, detail: e.message });
    }
  }

  function connect() {
    if (stopped) return;
    const ws = new WebSocketImpl(serverUrl);
    socket = ws;
    let handled = false;
    const armWatchdog = () => {
      clearTimeout(watchdog);
      watchdog = setTimeout(() => {
        log(`Conexión sin noticias del servidor en ${Math.round(livenessMs / 1000)} s: reconectando.`);
        drop();
      }, livenessMs);
    };
    ws.addEventListener('open', () => {
      log('Conectado al servidor, presentándome...');
      armWatchdog();
      send(ws, { kind: 'hello', secret, model });
    });
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data)); } catch { return; }
      if (msg.kind === 'ready') {
        attempt = 0;
        log(`Listo: la IA (${model}) está conectada al servidor.`);
      } else if (msg.kind === 'ping') {
        if (!handled) armWatchdog();
        send(ws, { kind: 'pong' });
      } else if (msg.kind === 'job') {
        abortCurrent(); // el servidor solo manda otro si ya abandonó el anterior
        const controller = new AbortController();
        current = controller;
        currentId = msg.id;
        work = work.then(() => runJob(ws, msg, controller));
      } else if (msg.kind === 'cancel') {
        if (msg.id != null && msg.id === currentId) abortCurrent(); // el servidor ya no lo espera
      }
    });
    const onGone = (ev) => {
      if (handled) return;
      handled = true;
      clearTimeout(watchdog);
      abortCurrent();
      if (stopped) return;
      if (ev?.code === 4001) log('Secreto rechazado por el servidor: revisa IA_CONECTOR_SECRETO');
      const wait = Math.min(backoffBaseMs * 2 ** attempt, backoffMaxMs);
      attempt += 1;
      log(`Conexión cerrada. Reintento en ${Math.round(wait / 100) / 10} s...`);
      timer = setTimeout(connect, wait);
    };
    // Node 22 puede disparar error sin close: se agenda el reintento igual y se cierra el socket
    const drop = () => {
      onGone();
      try { ws.close(); } catch { /* ya cerrado */ }
    };
    ws.addEventListener('close', onGone);
    ws.addEventListener('error', () => {
      log('Error de conexión con el servidor.');
      drop();
    });
  }

  connect();

  return {
    async stop() {
      stopped = true;
      clearTimeout(timer);
      clearTimeout(watchdog);
      abortCurrent();
      try { socket?.close(); } catch { /* ya cerrado */ }
      await work;
    },
  };
}

export async function checkOllama({ ollamaUrl, model, fetchImpl = fetch }) {
  const res = await fetchImpl(`${ollamaUrl}/api/tags`);
  if (!res.ok) throw new Error(`Ollama respondió ${res.status}`);
  const { models = [] } = await res.json();
  return models.some((m) => m.name === model || m.model === model || m.name === `${model}:latest`);
}

async function main() {
  const serverUrl = process.env.SERVIDOR_WS;
  const secret = process.env.IA_CONECTOR_SECRETO;
  const ollamaUrl = process.env.OLLAMA_URL || 'http://localhost:11434';
  const model = process.env.MODELO || 'qwen3:4b-instruct';
  if (!serverUrl || !secret) {
    console.error('Faltan variables: define SERVIDOR_WS e IA_CONECTOR_SECRETO.');
    process.exit(1);
  }
  let present;
  try {
    present = await checkOllama({ ollamaUrl, model });
  } catch (e) {
    console.error(`No pude hablar con Ollama en ${ollamaUrl}: ${e.message}. ¿Está abierto?`);
    process.exit(1);
  }
  if (!present) {
    console.error(`Falta el modelo: ollama pull ${model}`);
    process.exit(1);
  }
  const connector = startConnector({ serverUrl, secret, ollamaUrl, model });
  process.on('SIGINT', async () => { await connector.stop(); process.exit(0); });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
