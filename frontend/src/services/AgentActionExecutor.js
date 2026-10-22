import { createProject, getProjects, renameProject } from '../api/projectApi.js'
import { renameDiagram } from '../api/diagramApi.js'
import { createVersion, createVersionDiagram, getVersions } from '../api/versionApi.js'
import { normalizeAgentAction } from './AgentActionNormalizer.js'
import { ACTION_CONTRACTS, validateAgentAction } from './AgentActionValidator.js'

const isRealId = (value) => typeof value === 'string' && value.trim() && value !== 'null' && value !== 'undefined'
const sameName = (left, right) => left?.trim().toLocaleLowerCase() === right?.trim().toLocaleLowerCase()

export function agentActionError(error, fallback = 'No se pudo ejecutar la acción.') {
  if (!error?.response) return { errorCode: 'NETWORK_ERROR', message: 'No se pudo conectar con el servidor.' }
  const status = error.response.status
  const data = error.response.data || {}
  const validation = [...(Array.isArray(data.errors) ? data.errors : []), ...(Array.isArray(data.fieldErrors) ? data.fieldErrors : [])]
    .map((item) => typeof item === 'string' ? item : item.defaultMessage || item.message || item.reason).find(Boolean)
  const detail = validation || data.detail || data.message
  const codes = { 400: 'BAD_REQUEST', 401: 'SESSION_EXPIRED', 403: 'FORBIDDEN', 404: 'NOT_FOUND', 409: 'CONFLICT' }
  const defaults = { 401: 'Tu sesión expiró. Inicia sesión nuevamente.', 403: 'No tienes permisos para realizar esta acción.', 404: 'No se encontró el recurso solicitado.', 409: 'La acción entra en conflicto con el estado actual.' }
  return { errorCode: codes[status] || (status >= 500 ? 'SERVER_ERROR' : 'REQUEST_ERROR'), message: detail || defaults[status] || fallback }
}

const needContext = (message, options) => ({ success: false, errorCode: 'NEED_CONTEXT', message, options })
const success = (action, message, data) => ({ success: true, action, message, data })

async function resolveProject(args, context, services) {
  if (isRealId(context.projectId)) return { id: context.projectId, name: context.projectName }
  const projects = (await services.getProjects()).data
  if (!args.projectName) return needContext('¿En qué proyecto deseas realizar esta acción?', projects.map(({ id, name }) => ({ id, name })))
  const matches = projects.filter((project) => sameName(project.name, args.projectName))
  if (!matches.length) return needContext(`No encontré el proyecto ${args.projectName}.`, projects.map(({ id, name }) => ({ id, name })))
  if (matches.length > 1) return needContext(`Encontré varios proyectos llamados ${args.projectName}. ¿Cuál deseas usar?`, matches.map(({ id, name }) => ({ id, name })))
  return matches[0]
}

async function resolveVersion(projectId, args, context, services) {
  if (isRealId(context.versionId)) return { id: context.versionId, name: context.versionName }
  const versions = (await services.getVersions(projectId)).data
  const requested = args.versionName
    ? versions.filter((version) => sameName(version.name, args.versionName))
    : versions.filter((version) => version.mainVersion)
  if (requested.length === 1) return requested[0]
  if (!requested.length) return needContext(args.versionName ? `No encontré la versión ${args.versionName}.` : 'No se encontró la versión main.', versions.map(({ id, name }) => ({ id, name })))
  return needContext('Encontré varias versiones coincidentes. ¿Cuál deseas usar?', requested.map(({ id, name }) => ({ id, name })))
}

const defaultServices = { createProject, getProjects, renameProject, renameDiagram, createVersion, createVersionDiagram, getVersions }
let navigationPending = false
function navigateOnce(navigate, path) {
  if (navigationPending) return
  navigationPending = true
  queueMicrotask(() => { navigate(path); navigationPending = false })
}

const actionHandlers = {
  NAVIGATE: { requiredContext: [], run: async ({ args, navigate }) => { navigateOnce(navigate, args.path || '/'); return success('NAVIGATE', 'Navegación completada.') } },
  SWITCH_VERSION: { requiredContext: ['projectId'], run: async ({ args, context, navigate, services }) => { const project = await resolveProject(args, context, services); if (project.errorCode) return project; const version = await resolveVersion(project.id, args, { ...context, versionId: null }, services); if (version.errorCode) return version; navigateOnce(navigate, `/projects/${project.id}/versions/${version.id}`); return success('SWITCH_VERSION', `Cambié a la versión ${version.name}.`, version) } },
  CREATE_PROJECT: { requiredContext: [], run: async ({ args, navigate, services }) => { const response = await services.createProject({ name: args.name, description: args.description || '' }); navigateOnce(navigate, `/projects/${response.data.id}`); return success('CREATE_PROJECT', `Proyecto ${response.data.name} creado correctamente.`, response.data) } },
  RENAME_PROJECT: { requiredContext: ['projectId'], run: async ({ args, context, services }) => { const project = await resolveProject(args, context, services); if (project.errorCode) return project; const response = await services.renameProject(project.id, args.name); window.dispatchEvent(new Event('agent:data-changed')); return success('RENAME_PROJECT', `Proyecto renombrado a ${response.data.name}.`, response.data) } },
  CREATE_VERSION: { requiredContext: ['projectId'], run: async ({ args, context, navigate, services }) => { const project = await resolveProject(args, context, services); if (project.errorCode) return project; const source = await resolveVersion(project.id, {}, context, services); if (source.errorCode) return source; const response = await services.createVersion(project.id, { name: args.name, description: args.description || '', sourceVersionId: source.id }); navigateOnce(navigate, `/projects/${project.id}/versions/${response.data.id}`); return success('CREATE_VERSION', `Versión ${response.data.name} creada correctamente.`, response.data) } },
  CREATE_DIAGRAM: { requiredContext: ['projectId', 'versionId'], run: async ({ args, context, navigate, services }) => { const project = await resolveProject(args, context, services); if (project.errorCode) return project; const version = await resolveVersion(project.id, args, context, services); if (version.errorCode) return version; const diagramName = args.name.trim(); console.debug(`[AGENT] CREATE_DIAGRAM projectId=${project.id}`); console.debug(`[AGENT] CREATE_DIAGRAM versionId=${version.id}`); console.debug(`[AGENT] CREATE_DIAGRAM name="${diagramName}"`); console.debug('[AGENT] validation=OK'); const model = { id: crypto.randomUUID(), version: '4.2.0', title: diagramName, type: 'ClassDiagram', nodes: [], edges: [], assessments: [] }; const payload = { name: diagramName, type: 'ClassDiagram', model }; const response = await services.createVersionDiagram(project.id, version.id, payload); console.debug('[AGENT] execution=SUCCESS'); navigateOnce(navigate, `/projects/${project.id}/versions/${version.id}/diagrams/${response.data.id}`); return success('CREATE_DIAGRAM', `Diagrama ${response.data.name} creado correctamente.`, response.data) } },
  RENAME_DIAGRAM: { requiredContext: ['diagramId'], run: async ({ args, context, services }) => { if (!isRealId(context.diagramId)) return needContext('Abre el diagrama que deseas renombrar.'); const response = await services.renameDiagram(context.diagramId, args.name); window.dispatchEvent(new Event('agent:data-changed')); return success('RENAME_DIAGRAM', `Diagrama renombrado a ${response.data.name}.`, response.data) } },
}

for (const name of ['ADD_CLASS', 'ADD_ATTRIBUTE', 'ADD_METHOD', 'ADD_RELATIONSHIP', 'SET_MULTIPLICITY']) actionHandlers[name] = { requiredContext: ['diagramId'], run: async ({ action, context }) => { if (!isRealId(context.diagramId)) return needContext('Abre un diagrama para modificar el modelo UML.'); window.dispatchEvent(new CustomEvent('agent:uml-action', { detail: action })); return success(name, 'Acción aplicada al editor colaborativo.') } }
for (const [name, event] of Object.entries({ OPEN_AI_PANEL: 'agent:open-ai', GENERATE_SEQUENCE: 'agent:sequence', GENERATE_BACKEND: 'agent:codegen' })) actionHandlers[name] = { requiredContext: ['diagramId'], run: async ({ context }) => { if (!isRealId(context.diagramId)) return needContext('Abre un diagrama para usar esta acción.'); window.dispatchEvent(new Event(event)); return success(name, 'Acción preparada en el editor.') } }
for (const name of ['EXPORT_JSON', 'EXPORT_SQL', 'EXPORT_PLANTUML']) actionHandlers[name] = { requiredContext: ['diagramId'], run: async ({ context }) => { if (!isRealId(context.diagramId)) return needContext('Abre un diagrama para exportarlo.'); window.dispatchEvent(new CustomEvent('agent:export', { detail: name })); return success(name, 'Exportación preparada.') } }

export function validateActionContext(actionName, context) {
  const contract = ACTION_CONTRACTS[actionName]
  if (!contract) return { valid: false, errorCode: 'UNSUPPORTED_ACTION', message: `Acción no soportada: ${actionName}` }
  const missing = contract.requiredContext.filter((key) => !isRealId(context[key]))
  return { valid: !missing.length, missing, requiredContext: contract.requiredContext }
}

export async function executeAgentAction(action, context, navigate, injectedServices = {}, originalMessage = '') {
  const services = { ...defaultServices, ...injectedServices }
  const normalizedAction = normalizeAgentAction(action, originalMessage)
  const handler = actionHandlers[normalizedAction.name]
  console.debug('[AGENT] runtimeContext=', context)
  console.debug('[AGENT] parsedAction=', normalizedAction)
  console.debug('[AGENT] actionArguments=', normalizedAction.arguments)
  const validation = validateAgentAction(normalizedAction)
  if (!validation.valid) { const result = { success: false, action: normalizedAction.name, errorCode: validation.errorCode, message: validation.message }; console.debug('[AGENT] validation=', validation.errorCode); return result }
  if (!handler) return { success: false, errorCode: 'UNSUPPORTED_ACTION', message: `Acción no soportada: ${normalizedAction.name}` }
  try {
    const result = await handler.run({ action: normalizedAction, args: normalizedAction.arguments, context, navigate, services })
    console.debug('[AGENT] result=', result)
    return result
  } catch (error) {
    const normalized = agentActionError(error)
    const result = { success: false, action: normalizedAction.name, ...normalized }
    console.debug('[AGENT] result=', result)
    return result
  }
}
