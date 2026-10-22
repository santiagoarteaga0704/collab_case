const present = (value) => typeof value === 'string' ? Boolean(value.trim()) : value !== null && value !== undefined

export const ACTION_CONTRACTS = {
  CREATE_PROJECT: { requiredContext: [], requiredArguments: ['name'], prompt: '¿Qué nombre quieres darle al proyecto?' },
  CREATE_VERSION: { requiredContext: ['projectId'], requiredArguments: ['name'], prompt: '¿Qué nombre quieres darle a la versión?' },
  CREATE_DIAGRAM: { requiredContext: ['projectId', 'versionId'], requiredArguments: ['name'], prompt: '¿Qué nombre quieres darle al diagrama?' },
  RENAME_PROJECT: { requiredContext: ['projectId'], requiredArguments: ['name'], prompt: '¿Cuál será el nuevo nombre del proyecto?' },
  RENAME_DIAGRAM: { requiredContext: ['diagramId'], requiredArguments: ['name'], prompt: '¿Cuál será el nuevo nombre del diagrama?' },
  ADD_CLASS: { requiredContext: ['diagramId'], requiredArguments: ['name'], prompt: '¿Qué nombre tendrá la clase?' },
  ADD_ATTRIBUTE: { requiredContext: ['diagramId'], requiredArguments: ['name'], prompt: '¿Qué nombre tendrá el atributo?' },
  ADD_METHOD: { requiredContext: ['diagramId'], requiredArguments: ['name'], prompt: '¿Qué nombre tendrá el método?' },
  ADD_RELATIONSHIP: { requiredContext: ['diagramId'], requiredArguments: ['sourceClass', 'targetClassName'] },
  SET_MULTIPLICITY: { requiredContext: ['diagramId'], requiredArguments: ['relationshipId'] },
  NAVIGATE: { requiredContext: [], requiredArguments: [] }, SWITCH_VERSION: { requiredContext: ['projectId'], requiredArguments: [] },
  OPEN_AI_PANEL: { requiredContext: ['diagramId'], requiredArguments: [] }, GENERATE_SEQUENCE: { requiredContext: ['diagramId'], requiredArguments: [] },
  EXPORT_JSON: { requiredContext: ['diagramId'], requiredArguments: [] }, EXPORT_SQL: { requiredContext: ['diagramId'], requiredArguments: [] },
  EXPORT_PLANTUML: { requiredContext: ['diagramId'], requiredArguments: [] }, GENERATE_BACKEND: { requiredContext: ['diagramId'], requiredArguments: [] },
}

export function validateAgentAction(action) {
  const contract = ACTION_CONTRACTS[action.name]
  if (!contract) return { valid: false, errorCode: 'UNSUPPORTED_ACTION', message: `Acción no soportada: ${action.name}` }
  const missingArguments = contract.requiredArguments.filter((key) => !present(action.arguments?.[key]))
  if (missingArguments.length) return { valid: false, errorCode: 'NEED_ARGUMENT', missingArguments, message: contract.prompt || `Faltan datos obligatorios: ${missingArguments.join(', ')}.` }
  return { valid: true, contract }
}
