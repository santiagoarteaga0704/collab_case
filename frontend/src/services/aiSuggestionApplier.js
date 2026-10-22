const CLASS_WIDTH = 160
const EMPTY_CLASS_HEIGHT = 40
const MEMBER_HEIGHT = 30

export const SUGGESTION_ORDER = {
  ADD_CLASS: 1, ADD_ATTRIBUTE: 2, ADD_METHOD: 2, ADD_RELATIONSHIP: 3,
  CHANGE_RELATIONSHIP_TYPE: 4, CHANGE_CARDINALITY: 5,
}

const RELATIONSHIP_TYPES = {
  ASSOCIATION: 'ClassBidirectional', BIDIRECTIONAL: 'ClassBidirectional',
  UNIDIRECTIONAL: 'ClassUnidirectional', AGGREGATION: 'ClassAggregation',
  COMPOSITION: 'ClassComposition', INHERITANCE: 'ClassInheritance',
  DEPENDENCY: 'ClassDependency', REALIZATION: 'ClassRealization',
  ClassBidirectional: 'ClassBidirectional', ClassUnidirectional: 'ClassUnidirectional',
  ClassAggregation: 'ClassAggregation', ClassComposition: 'ClassComposition',
  ClassInheritance: 'ClassInheritance', ClassDependency: 'ClassDependency',
  ClassRealization: 'ClassRealization',
}

const result = (status, message, extra = {}) => ({ status, message, ...extra })
const text = (value) => typeof value === 'string' ? value.trim() : ''
const normalized = (value) => text(value).replace(/^[+#~-]\s*/, '').trim().toLocaleLowerCase()
const attributeName = (value) => normalized(value).split(':', 1)[0].trim()
const methodName = (value) => normalized(value).split('(', 1)[0].trim()
const list = (value) => Array.isArray(value) ? value : []
const validMember = (member) => text(member?.name) && text(member?.dataType)
const validMethod = (method) => text(method?.name) && text(method?.returnType)
  && list(method.parameters).every(validMember)

function relationshipType(value) {
  const clean = text(value)
  return RELATIONSHIP_TYPES[clean] ?? RELATIONSHIP_TYPES[clean.toUpperCase()]
}

function classByName(model, name) {
  const matches = model.nodes.filter((node) => node.type === 'class'
    && normalized(node.data?.name) === normalized(name))
  return matches.length === 1 ? matches[0] : undefined
}

function resolveClass(model, id, name, createdClassIds = new Map()) {
  const mappedId = createdClassIds.get(normalized(name))
  return model.nodes.find((node) => node.type === 'class' && (node.id === id || node.id === mappedId))
    ?? classByName(model, name)
}

function sameRelationship(edge, sourceId, targetId, type) {
  if (edge.type !== type) return false
  if (edge.source === sourceId && edge.target === targetId) return true
  return type === 'ClassBidirectional' && edge.source === targetId && edge.target === sourceId
}

const validCardinality = (value) => /^(?:\d+|\*)(?:\.\.(?:\d+|\*))?$/.test(text(value))

export function validateAiSuggestion({
  model, suggestion, createdClassIds = new Map(), suggestedClassNames = new Set(),
}) {
  if (!model || !suggestion?.type) return result('invalid', 'La sugerencia no es válida.')
  if (['WARNING', 'IMPROVEMENT'].includes(suggestion.type)) {
    return result('informative', 'Esta sugerencia es solo informativa.')
  }
  if (suggestion.type === 'ADD_CLASS') {
    const name = text(suggestion.className)
    if (!name) return result('invalid', 'La sugerencia no incluye el nombre de la clase.')
    if (classByName(model, name)) return result('invalid', `Ya existe una clase llamada ${name}.`)
    if (!list(suggestion.attributes).every(validMember)
      || !list(suggestion.methods).every(validMethod)) {
      return result('invalid', 'Los miembros iniciales de la clase están incompletos o duplicados.')
    }
    const attributeNames = list(suggestion.attributes).map((item) => attributeName(item.name))
    const methodNames = list(suggestion.methods).map((item) => methodName(item.name))
    if (new Set(attributeNames).size !== attributeNames.length
      || new Set(methodNames).size !== methodNames.length) {
      return result('invalid', 'La clase propuesta contiene miembros duplicados.')
    }
    return result('pending', '', { suggestion })
  }
  if (suggestion.type === 'ADD_ATTRIBUTE' || suggestion.type === 'ADD_METHOD') {
    const node = resolveClass(model, suggestion.targetElementId, suggestion.targetClass, createdClassIds)
    if (!node && suggestedClassNames.has(normalized(suggestion.targetClass))) {
      return result('pending', '', { suggestion })
    }
    if (!node) return result('stale', 'La clase destino ya no existe o no puede identificarse sin ambigüedad.')
    const isMethod = suggestion.type === 'ADD_METHOD'
    if (isMethod && (!text(suggestion.returnType)
      || !list(suggestion.methodParameters).every(validMember))) {
      return result('invalid', 'El método debe indicar parámetros tipados y tipo de retorno explícito.')
    }
    const proposed = isMethod ? methodName(suggestion.methodName) : attributeName(suggestion.attributeName)
    if (!proposed) return result('invalid', `La sugerencia no incluye el ${isMethod ? 'método' : 'atributo'}.`)
    const field = isMethod ? 'methods' : 'attributes'
    const members = Array.isArray(node.data?.[field]) ? node.data[field] : []
    const duplicate = members.some((member) => (isMethod ? methodName(member.name) : attributeName(member.name)) === proposed)
    if (duplicate) return result('invalid', `La clase ya contiene un ${isMethod ? 'método' : 'atributo'} con ese nombre.`)
    return result('pending', '', { suggestion: { ...suggestion, targetElementId: node.id } })
  }
  if (suggestion.type === 'ADD_RELATIONSHIP') {
    const source = resolveClass(model, suggestion.sourceElementId, suggestion.sourceClass, createdClassIds)
    const target = resolveClass(model, suggestion.targetElementId, suggestion.targetClassName, createdClassIds)
    const sourcePending = !source && suggestedClassNames.has(normalized(suggestion.sourceClass))
    const targetPending = !target && suggestedClassNames.has(normalized(suggestion.targetClassName))
    if ((!source && !sourcePending) || (!target && !targetPending)) {
      return result('stale', 'No se pueden identificar ambas clases de la relación.')
    }
    if (sourcePending || targetPending) return result('pending', '', { suggestion })
    if (source.id === target.id) return result('invalid', 'Una relación nueva requiere dos clases distintas.')
    const type = relationshipType(suggestion.relationshipType)
    if (!type) return result('invalid', 'El tipo de relación no corresponde a un tipo oficial de Apollon.')
    if (model.edges.some((edge) => sameRelationship(edge, source.id, target.id, type))) {
      return result('invalid', 'Ya existe exactamente esa relación entre las clases.')
    }
    return result('pending', '', { suggestion: {
      ...suggestion, sourceElementId: source.id, targetElementId: target.id, relationshipType: type,
    } })
  }
  if (suggestion.type === 'CHANGE_RELATIONSHIP_TYPE') {
    const edge = model.edges.find((item) => item.id === suggestion.targetRelationshipId)
    if (!edge) return result('stale', 'La relación destino ya no existe.')
    const type = relationshipType(suggestion.suggestedRelationshipType)
    if (!type) return result('invalid', 'El nuevo tipo no corresponde a un tipo oficial de Apollon.')
    if (edge.type === type) return result('invalid', 'La relación ya tiene el tipo sugerido.')
    return result('pending', '', { suggestion: { ...suggestion, suggestedRelationshipType: type } })
  }
  if (suggestion.type === 'CHANGE_CARDINALITY') {
    const edge = model.edges.find((item) => item.id === suggestion.targetRelationshipId)
    if (!edge) return result('stale', 'La relación destino ya no existe.')
    const source = text(suggestion.sourceCardinality)
    const target = text(suggestion.targetCardinality)
    if ((!source && !target) || (source && !validCardinality(source)) || (target && !validCardinality(target))) {
      return result('invalid', 'La sugerencia no incluye cardinalidades UML válidas.')
    }
    const changes = (source && source !== text(edge.data?.sourceMultiplicity))
      || (target && target !== text(edge.data?.targetMultiplicity))
    return changes ? result('pending', '', { suggestion })
      : result('invalid', 'La relación ya tiene las cardinalidades sugeridas.')
  }
  return result('invalid', 'Este tipo de sugerencia no es compatible.')
}

function blockedBy(remoteLocks, elementId) {
  return remoteLocks?.get(elementId)
}

async function requireLock(elementId, remoteLocks, acquireLock) {
  const remoteLock = blockedBy(remoteLocks, elementId)
  if (remoteLock) return result('locked', `Este elemento está siendo editado por ${remoteLock.userName}`)
  return acquireLock ? acquireLock(elementId) : result('locked', 'No se pudo solicitar el bloqueo del elemento.')
}

function nextClassPosition(nodes) {
  for (let row = 0; row < 20; row += 1) {
    for (let column = 0; column < 4; column += 1) {
      const candidate = { x: 40 + column * 220, y: 40 + row * 160 }
      const occupied = nodes.some((node) => candidate.x < node.position.x + (node.width ?? CLASS_WIDTH) + 20
        && candidate.x + CLASS_WIDTH + 20 > node.position.x
        && candidate.y < node.position.y + (node.height ?? 100) + 20
        && candidate.y + EMPTY_CLASS_HEIGHT + 20 > node.position.y)
      if (!occupied) return candidate
    }
  }
  return { x: 40, y: 40 + nodes.length * 160 }
}

function memberLabel(name, type, method = false, parameters = []) {
  const clean = text(name)
  const argumentsLabel = list(parameters).map((item) => `${text(item.name)}: ${text(item.dataType)}`).join(', ')
  const signature = method && !clean.includes('(') ? `${clean}(${argumentsLabel})` : clean
  const typed = text(type) ? `${signature}: ${text(type)}` : signature
  return /^[+#~-]\s/.test(typed) ? typed : `+ ${typed}`
}

function applyModel(editor, model, extra = {}) {
  editor.model = model
  return result('applied', 'Sugerencia aplicada correctamente.', extra)
}

export async function applyAiSuggestion({
  editor, suggestion, canEdit, remoteLocks, acquireLock, createdClassIds = new Map(),
}) {
  if (!canEdit) return result('invalid', 'Tu rol no permite modificar el diagrama.')
  if (!editor) return result('invalid', 'El editor no está disponible.')
  const validation = validateAiSuggestion({ model: editor.model, suggestion, createdClassIds })
  if (validation.status !== 'pending') return validation
  const applicable = validation.suggestion
  const model = structuredClone(editor.model)

  if (applicable.type === 'ADD_CLASS') {
    const name = text(applicable.className)
    const attributes = list(applicable.attributes).map((attribute) => ({
      id: crypto.randomUUID(), name: memberLabel(attribute.name, attribute.dataType),
    }))
    const methods = list(applicable.methods).map((method) => ({
      id: crypto.randomUUID(), name: memberLabel(method.name, method.returnType, true, method.parameters),
    }))
    const memberCount = attributes.length + methods.length
    const height = EMPTY_CLASS_HEIGHT + memberCount * MEMBER_HEIGHT
    const node = { id: crypto.randomUUID(), type: 'class', position: nextClassPosition(model.nodes),
      width: CLASS_WIDTH, height,
      measured: { width: CLASS_WIDTH, height },
      data: { name, attributes, methods } }
    return applyModel(editor, { ...model, nodes: [...model.nodes, node] }, { elementId: node.id, className: name })
  }
  if (applicable.type === 'ADD_ATTRIBUTE' || applicable.type === 'ADD_METHOD') {
    const node = model.nodes.find((item) => item.id === applicable.targetElementId)
    const lock = await requireLock(node.id, remoteLocks, acquireLock)
    if (lock.status !== 'acquired') return lock
    const isMethod = applicable.type === 'ADD_METHOD'
    const field = isMethod ? 'methods' : 'attributes'
    const members = Array.isArray(node.data?.[field]) ? node.data[field] : []
    const label = memberLabel(isMethod ? applicable.methodName : applicable.attributeName,
      isMethod ? applicable.returnType : applicable.attributeType, isMethod,
      applicable.methodParameters)
    const height = (node.height ?? EMPTY_CLASS_HEIGHT) + MEMBER_HEIGHT
    const changed = { ...node, height, measured: { ...node.measured, height },
      data: { ...node.data, [field]: [...members, { id: crypto.randomUUID(), name: label }] } }
    return applyModel(editor, { ...model,
      nodes: model.nodes.map((item) => item.id === changed.id ? changed : item) })
  }
  if (applicable.type === 'ADD_RELATIONSHIP') {
    for (const elementId of [applicable.sourceElementId, applicable.targetElementId]) {
      const remote = blockedBy(remoteLocks, elementId)
      if (remote) return result('locked', `Este elemento está siendo editado por ${remote.userName}`)
    }
    const edge = { id: crypto.randomUUID(), source: applicable.sourceElementId,
      target: applicable.targetElementId, type: applicable.relationshipType,
      sourceHandle: 'right', targetHandle: 'left', data: { points: [],
        ...(text(applicable.sourceCardinality) && { sourceMultiplicity: text(applicable.sourceCardinality) }),
        ...(text(applicable.targetCardinality) && { targetMultiplicity: text(applicable.targetCardinality) }) } }
    return applyModel(editor, { ...model, edges: [...model.edges, edge] })
  }
  if (applicable.type === 'CHANGE_RELATIONSHIP_TYPE' || applicable.type === 'CHANGE_CARDINALITY') {
    const edge = model.edges.find((item) => item.id === applicable.targetRelationshipId)
    const lock = await requireLock(edge.id, remoteLocks, acquireLock)
    if (lock.status !== 'acquired') return lock
    const changed = applicable.type === 'CHANGE_RELATIONSHIP_TYPE'
      ? { ...edge, type: applicable.suggestedRelationshipType }
      : { ...edge, data: { ...edge.data,
          ...(text(applicable.sourceCardinality) && { sourceMultiplicity: text(applicable.sourceCardinality) }),
          ...(text(applicable.targetCardinality) && { targetMultiplicity: text(applicable.targetCardinality) }) } }
    return applyModel(editor, { ...model,
      edges: model.edges.map((item) => item.id === changed.id ? changed : item) })
  }
  return result('invalid', 'Este tipo de sugerencia no es compatible.')
}
