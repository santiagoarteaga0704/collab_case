// Conector de la IA local: une Ollama (en esta laptop) con el servidor de Collab CASE.
import { pathToFileURL } from 'node:url';

export function startConnector({
  serverUrl, secret, ollamaUrl = 'http://localhost:11434', model = 'qwen3:4b-instruct',
  log = console.log, WebSocketImpl = globalThis.WebSocket, fetchImpl = fetch,
  backoffBaseMs = 1000, backoffMaxMs = 15000,
}) {
  let stopped = false;
  let socket = null;
  let timer = null;
  let attempt = 0;
  let work = Promise.resolve(); // atiende de a un trabajo

  const send = (ws, obj) => {
    try { ws.send(JSON.stringify(obj)); } catch { /* la conexión se cayó; el servidor reintenta */ }
  };

  async function runJob(ws, job) {
    const { id } = job;
    try {
      const body = { model, messages: job.messages, stream: true, options: job.options };
      if (job.format != null) body.format = job.format;
      const res = await fetchImpl(`${ollamaUrl}/api/chat`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
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
          send(ws, { kind: 'chunk', id, text });
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
      send(ws, { kind: 'done', id, content: full });
    } catch (e) {
      log(`Falló el trabajo ${id}: ${e.message}`);
      send(ws, { kind: 'error', id, detail: e.message });
    }
  }

  function connect() {
    if (stopped) return;
    const ws = new WebSocketImpl(serverUrl);
    socket = ws;
    ws.addEventListener('open', () => {
      log('Conectado al servidor, presentándome...');
      send(ws, { kind: 'hello', secret, model });
    });
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data)); } catch { return; }
      if (msg.kind === 'ready') {
        attempt = 0;
        log(`Listo: la IA (${model}) está conectada al servidor.`);
      } else if (msg.kind === 'ping') {
        send(ws, { kind: 'pong' });
      } else if (msg.kind === 'job') {
        work = work.then(() => runJob(ws, msg));
      }
    });
    let handled = false;
    const onGone = (ev) => {
      if (handled) return;
      handled = true;
      if (stopped) return;
      if (ev?.code === 4001) log('Secreto rechazado por el servidor: revisa IA_CONECTOR_SECRETO');
      const wait = Math.min(backoffBaseMs * 2 ** attempt, backoffMaxMs);
      attempt += 1;
      log(`Conexión cerrada. Reintento en ${Math.round(wait / 100) / 10} s...`);
      timer = setTimeout(connect, wait);
    };
    ws.addEventListener('close', onGone);
    ws.addEventListener('error', () => { log('Error de conexión con el servidor.'); });
  }

  connect();

  return {
    async stop() {
      stopped = true;
      clearTimeout(timer);
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
