// Prueba de punta a punta del chat con la IA local: backend + conector + Ollama reales.
// Uso: node scripts/prueba-chat-ia.mjs   (variable opcional: BASE=http://localhost:8080)
const BASE = process.env.BASE ?? 'http://localhost:8080';
const WS = BASE.replace(/^http/, 'ws');
const LIMIT_MS = 60_000;
const run = Date.now().toString(36);
const results = [];

const check = (name, ok, detail = '') => {
  results.push(ok);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -> ' + detail : ''}`);
};

async function api(method, path, token, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

async function register(name) {
  const email = `${name.toLowerCase()}.${run}@prueba.test`;
  const r = await api('POST', '/api/auth/register', null, { name, email, password: 'clave-segura-1' });
  return { name, email, token: r.token, id: r.user.id };
}

const classNode = (id, name, x) => ({
  id, type: 'class', position: { x, y: 0 }, width: 200, height: 100,
  data: { name, attributes: [{ id: id + '-a', name: '+ id: Long' }], methods: [] },
});

class Person {
  constructor(user, projectId, diagramId) {
    this.user = user; this.projectId = projectId; this.diagramId = diagramId;
    this.events = []; this.waiters = [];
  }
  connect() {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(`${WS}/ws/chat/${this.projectId}`);
      this.ws = ws;
      ws.onopen = () => ws.send(JSON.stringify({
        kind: 'auth', token: this.user.token, ...(this.diagramId ? { diagramId: this.diagramId } : {}),
      }));
      ws.onerror = () => reject(new Error('WebSocket error'));
      ws.onmessage = (m) => {
        const e = JSON.parse(m.data);
        e.at = Date.now();
        if (e.kind === 'auth_ok') resolve();
        if (e.kind === 'auth_error') reject(new Error('auth_error'));
        this.events.push(e);
        this.waiters = this.waiters.filter((w) => !w(e));
      };
    });
  }
  send(content, isPrivate = false) {
    this.ws.send(JSON.stringify({ kind: 'send', content, private: isPrivate }));
  }
  waitFor(pred, ms, from = 0) {
    const found = this.events.slice(from).find(pred);
    if (found) return Promise.resolve(found);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('timeout esperando evento')), ms);
      this.waiters.push((e) => (pred(e) ? (clearTimeout(t), resolve(e), true) : false));
    });
  }
  /** Sends a question and returns the AI answer plus timing/chunk facts. */
  async ask(content, isPrivate = false) {
    const from = this.events.length;
    const t0 = Date.now();
    this.send(content, isPrivate);
    // La respuesta de la IA nace como un mensaje AI propio: se sigue por su id, no por el primero que termine.
    const old = new Set(this.events.slice(0, from).filter((e) => e.message?.kind === 'AI').map((e) => e.message.id));
    const placeholder = await this.waitFor((e) => e.message?.kind === 'AI' && !old.has(e.message.id), LIMIT_MS, from)
      .catch(() => null);
    const final = placeholder && await this.waitFor(
      (e) => e.kind === 'message_updated' && e.message?.id === placeholder.message.id
        && (e.message.status === 'COMPLETE' || e.message.status === 'FAILED'),
      LIMIT_MS * 2, from).catch(() => null);
    await sleep(500); // deja llegar apply_changes, que sale justo después del mensaje final
    const seen = this.events.slice(from);
    const aiId = placeholder?.message.id;
    const chunks = seen.filter((e) => e.kind === 'chunk' && e.messageId === aiId);
    return {
      text: final?.message.content ?? '(sin respuesta)', status: final?.message.status ?? 'TIMEOUT',
      aiId, ms: final ? final.at - t0 : Date.now() - t0, chunks,
      chunksBeforeEnd: chunks.length > 0 && !!final && chunks[0].at < final.at,
      firstChunkMs: chunks[0] ? chunks[0].at - t0 : null,
      apply: seen.find((e) => e.kind === 'apply_changes'), from,
    };
  }
}

function show(label, r) {
  console.log(`\n--- ${label} ---`);
  console.log(`estado=${r.status}  tiempo=${(r.ms / 1000).toFixed(1)} s  chunks=${r.chunks.length}`
    + `  primer chunk a ${r.firstChunkMs == null ? '-' : (r.firstChunkMs / 1000).toFixed(1) + ' s'}`
    + `  chunks antes del final=${r.chunksBeforeEnd}`);
  console.log(r.text);
  if (r.apply) console.log('apply_changes:', JSON.stringify(r.apply.suggestions, null, 1));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const ana = await register('Ana');
  const luis = await register('Luis');
  const carla = await register('Carla');
  const project = await api('POST', '/api/projects', ana.token, { name: `Tienda ${run}`, description: 'Sistema de ventas' });
  for (const u of [luis, carla]) {
    await api('POST', `/api/projects/${project.id}/members`, ana.token, { email: u.email, role: 'EDITOR' });
  }
  const diagram = await api('POST', `/api/projects/${project.id}/diagrams`, ana.token, {
    name: 'Ventas', type: 'ClassDiagram',
    model: {
      version: '4.0.0', type: 'ClassDiagram', title: 'Ventas', edges: [],
      nodes: [classNode('n-cliente', 'Cliente', 0), classNode('n-producto', 'Producto', 300)],
    },
  });
  console.log(`Proyecto ${project.id}, diagrama ${diagram.id}`);

  const pAna = new Person(ana, project.id, null);
  const pLuis = new Person(luis, project.id, diagram.id);
  const pCarla = new Person(carla, project.id, null);
  await Promise.all([pAna.connect(), pLuis.connect(), pCarla.connect()]);

  pAna.send('Yo hago inventario'); await sleep(400);
  pLuis.send('Yo hago clientes y después ventas'); await sleep(400);

  const r1 = await pLuis.ask('@IA ¿qué me falta?');
  show('Luis: @IA ¿qué me falta?', r1);
  const r2 = await pLuis.ask('@IA armá el módulo de ventas conectado a Cliente y Producto');
  show('Luis: @IA armá el módulo de ventas conectado a Cliente y Producto', r2);

  // El navegador de Luis aplica lo sugerido y lo reporta.
  if (r2.apply) {
    const sug = r2.apply.suggestions ?? [];
    const names = sug.map((s) => s.className || s.attributeName || s.methodName || s.description).filter(Boolean);
    pLuis.ws.send(JSON.stringify({ kind: 'changes_applied', messageId: r2.apply.messageId, applied: names, failed: [] }));
    const notice = await pLuis.waitFor(
      (e) => e.kind === 'message' && e.message?.kind === 'NOTICE' && /^IA agregó/.test(e.message.content),
      10_000, r2.from).catch(() => null);
    console.log('Aviso público:', notice ? notice.message.content : '(no llegó)');
    check('aviso "IA agregó ..." tras changes_applied', !!notice);
  }

  const r3 = await pAna.ask('@IA resumen');
  show('Ana: @IA resumen', r3);

  const secret = '¿diferencia entre agregación y composición?';
  const r4 = await pCarla.ask('@IA ' + secret, true);
  show('Carla (privado): @IA ' + secret, r4);

  await sleep(500);
  const history = await api('GET', `/api/projects/${project.id}/chat`, ana.token);
  const isLeak = (m) => /agregaci[oó]n y composici[oó]n/i.test(m.content ?? '') || m.id === r4.aiId;
  const wsLeak = pAna.events.filter((e) => (e.message && isLeak(e.message)) || (e.kind === 'chunk' && e.messageId === r4.aiId));
  const histLeak = history.filter(isLeak);

  console.log('\n=== Resultados ===');
  const rows = [['Luis ¿qué me falta?', r1, 'Luis'], ['Luis arma ventas', r2, 'Luis'],
    ['Ana resumen', r3, 'Ana'], ['Carla privado', r4, 'Carla']];
  for (const [label, r, who] of rows) {
    check(`${label}: responde (no FAILED)`, r.status === 'COMPLETE', r.status);
    check(`${label}: nombra a ${who}`, new RegExp(who, 'i').test(r.text));
    check(`${label}: chunks antes del mensaje final`, r.chunksBeforeEnd, `${r.chunks.length} chunks`);
    check(`${label}: < 60 s`, r.ms < LIMIT_MS, `${(r.ms / 1000).toFixed(1)} s`);
  }
  const sug = r2.apply?.suggestions ?? [];
  const classNames = sug.filter((s) => s.type === 'ADD_CLASS').map((s) => s.className);
  const rels = sug.filter((s) => s.type === 'ADD_RELATIONSHIP');
  check('apply_changes llegó a Luis', !!r2.apply);
  check('dibuja Venta y DetalleVenta',
    ['Venta', 'DetalleVenta'].every((n) => classNames.some((c) => c?.toLowerCase() === n.toLowerCase())),
    classNames.join(', '));
  check('3 relaciones', rels.length === 3, `${rels.length}: ` + rels.map((s) => s.description).join(' | '));
  check('resumen menciona a Ana y a Luis', /Ana/.test(r3.text) && /Luis/.test(r3.text));
  check('Carla privado no visible para Ana (WS ni historial)', wsLeak.length === 0 && histLeak.length === 0,
    `ws=${wsLeak.length} historial=${histLeak.length}`);

  [pAna, pLuis, pCarla].forEach((p) => p.ws.close());
  const failed = results.filter((x) => !x).length;
  console.log(`\n${failed === 0 ? 'TODO PASS' : failed + ' FAIL'} (${results.length} checks)`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error('Error:', e); process.exit(2); });
