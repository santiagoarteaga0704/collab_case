import test from 'node:test'
import assert from 'node:assert/strict'
import { agentActionError, executeAgentAction } from './AgentActionExecutor.js'
import { normalizeAgentAction } from './AgentActionNormalizer.js'

const PROJECT = { id: 'project-uuid', name: 'Biblioteca' }
const VERSION = { id: 'version-uuid', name: 'main', mainVersion: true }
function services(overrides = {}) {
  const calls = []
  return { calls, api: {
    getProjects: async () => ({ data: [PROJECT] }), getVersions: async () => ({ data: [VERSION] }),
    createProject: async (body) => { calls.push(['createProject', body]); return { data: { id: PROJECT.id, name: body.name } } },
    createVersion: async (projectId, body) => { calls.push(['createVersion', projectId, body]); return { data: { id: 'new-version-uuid', name: body.name } } },
    createVersionDiagram: async (projectId, versionId, body) => { calls.push(['createVersionDiagram', projectId, versionId, body]); return { data: { id: 'diagram-uuid', name: body.name } } },
    ...overrides,
  } }
}
const navigate = () => {}
const createDiagram = (arguments_) => ({ name: 'CREATE_DIAGRAM', arguments: arguments_ })

test('CREATE_PROJECT funciona desde Dashboard', async () => { const mock = services(); const result = await executeAgentAction({ name: 'CREATE_PROJECT', arguments: { name: 'Biblioteca' } }, {}, navigate, mock.api); assert.equal(result.success, true); assert.equal(mock.calls[0][0], 'createProject') })
test('CREATE_VERSION usa el proyecto y la versión activa de ProjectDetail', async () => { const mock = services(); const result = await executeAgentAction({ name: 'CREATE_VERSION', arguments: { name: 'v2' } }, { projectId: PROJECT.id, versionId: VERSION.id }, navigate, mock.api); assert.equal(result.success, true); assert.deepEqual(mock.calls[0], ['createVersion', PROJECT.id, { name: 'v2', description: '', sourceVersionId: VERSION.id }]) })
test('SWITCH_VERSION resuelve la versión y navega con UUID reales', async () => { const mock = services({ getVersions: async () => ({ data: [VERSION, { id: 'version-v2', name: 'v2', mainVersion: false }] }) }); const paths = []; const result = await executeAgentAction({ name: 'SWITCH_VERSION', arguments: { versionName: 'v2' } }, { projectId: PROJECT.id, versionId: VERSION.id }, (path) => paths.push(path), mock.api); await new Promise((resolve) => queueMicrotask(resolve)); assert.equal(result.success, true); assert.deepEqual(paths, [`/projects/${PROJECT.id}/versions/version-v2`]) })
test('CREATE_DIAGRAM usa contexto real de ProjectDetail', async () => { const mock = services(); await executeAgentAction(createDiagram({ name: 'Modelo Principal' }), { projectId: PROJECT.id, versionId: VERSION.id }, navigate, mock.api); assert.deepEqual(mock.calls[0].slice(0, 3), ['createVersionDiagram', PROJECT.id, VERSION.id]) })
test('CREATE_DIAGRAM usa contexto real de DiagramEditor', async () => { const mock = services(); await executeAgentAction(createDiagram({ name: 'Otro' }), { projectId: PROJECT.id, versionId: VERSION.id, diagramId: 'current-diagram' }, navigate, mock.api); assert.equal(mock.calls[0][1], PROJECT.id); assert.equal(mock.calls[0][2], VERSION.id) })
test('Dashboard sin proyecto devuelve NEED_CONTEXT y no crea', async () => { const mock = services(); const result = await executeAgentAction(createDiagram({ name: 'Modelo' }), {}, navigate, mock.api); assert.equal(result.errorCode, 'NEED_CONTEXT'); assert.equal(mock.calls.length, 0) })
test('projectName se resuelve a un UUID accesible', async () => { const mock = services(); await executeAgentAction(createDiagram({ name: 'Modelo', projectName: 'biblioteca' }), {}, navigate, mock.api); assert.equal(mock.calls[0][1], PROJECT.id) })
test('projectId null nunca llega a createVersionDiagram', async () => { const mock = services(); await executeAgentAction(createDiagram({ name: 'Modelo' }), { projectId: null, versionId: null }, navigate, mock.api); assert.equal(mock.calls.length, 0) })
test('versionId null resuelve main', async () => { const mock = services(); await executeAgentAction(createDiagram({ name: 'Modelo' }), { projectId: PROJECT.id, versionId: null }, navigate, mock.api); assert.equal(mock.calls[0][2], VERSION.id) })
test('error 400 conserva el detalle seguro', () => { const result = agentActionError({ response: { status: 400, data: { message: 'Versión inválida.' } } }); assert.deepEqual(result, { errorCode: 'BAD_REQUEST', message: 'Versión inválida.' }) })
test('error sin response se clasifica como red', () => { assert.equal(agentActionError({ request: {} }).errorCode, 'NETWORK_ERROR') })
test('extrae Modelo Principal y lo envía como payload name', async () => { const mock = services(); const result = await executeAgentAction(createDiagram({}), { projectId: PROJECT.id, versionId: VERSION.id }, navigate, mock.api, 'Crea un diagrama llamado Modelo Principal.'); assert.equal(result.success, true); assert.equal(mock.calls[0][3].name, 'Modelo Principal') })
test('extrae nombre sin la palabra llamado', async () => { const mock = services(); await executeAgentAction(createDiagram({}), { projectId: PROJECT.id, versionId: VERSION.id }, navigate, mock.api, 'Crea un diagrama Modelo de Biblioteca.'); assert.equal(mock.calls[0][3].name, 'Modelo de Biblioteca') })
test('diagrama sin nombre devuelve NEED_ARGUMENT sin HTTP', async () => { const mock = services(); const result = await executeAgentAction(createDiagram({}), { projectId: PROJECT.id, versionId: VERSION.id }, navigate, mock.api, 'Crea un diagrama.'); assert.equal(result.errorCode, 'NEED_ARGUMENT'); assert.equal(mock.calls.length, 0) })
test('normaliza diagramName sin confundir projectName', () => { const normalized = normalizeAgentAction(createDiagram({ diagramName: 'Reservas', projectName: 'Biblioteca' })); assert.equal(normalized.arguments.name, 'Reservas') })
test('fallback completa arguments vacío desde texto original', () => { const normalized = normalizeAgentAction(createDiagram({}), 'Crea un diagrama llamado Clientes'); assert.equal(normalized.arguments.name, 'Clientes') })
test('sin name ni texto reconocible jamás ejecuta createVersionDiagram', async () => { const mock = services(); const result = await executeAgentAction(createDiagram({}), { projectId: PROJECT.id, versionId: VERSION.id }, navigate, mock.api, 'Hazlo'); assert.equal(result.errorCode, 'NEED_ARGUMENT'); assert.equal(mock.calls.length, 0) })
test('otras acciones CRUD también validan name antes de HTTP', async () => { const mock = services(); for (const name of ['CREATE_PROJECT', 'CREATE_VERSION', 'RENAME_PROJECT', 'RENAME_DIAGRAM', 'ADD_CLASS', 'ADD_ATTRIBUTE', 'ADD_METHOD']) { const result = await executeAgentAction({ name, arguments: {} }, {}, navigate, mock.api, 'Hazlo'); assert.equal(result.errorCode, 'NEED_ARGUMENT') } assert.equal(mock.calls.length, 0) })
