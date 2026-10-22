const clean = (value) => typeof value === 'string' ? value.trim().replace(/[.!?]+$/, '').trim() : ''

const NAME_ALIASES = {
  CREATE_DIAGRAM: ['name', 'diagramName', 'title'],
  CREATE_PROJECT: ['name', 'projectName', 'title'],
  CREATE_VERSION: ['name', 'versionName', 'title'],
  RENAME_PROJECT: ['name', 'newName', 'title'],
  RENAME_DIAGRAM: ['name', 'newName', 'diagramName', 'title'],
  ADD_CLASS: ['name', 'className', 'title'],
  ADD_ATTRIBUTE: ['name', 'attributeName'],
  ADD_METHOD: ['name', 'methodName'],
}

const SUBJECTS = {
  CREATE_DIAGRAM: 'diagrama', CREATE_PROJECT: 'proyecto', CREATE_VERSION: 'versión',
  ADD_CLASS: 'clase', ADD_ATTRIBUTE: 'atributo', ADD_METHOD: 'método',
}

export function extractActionName(actionName, message = '') {
  const subject = SUBJECTS[actionName]
  if (!subject || !message.trim()) return ''
  const escaped = subject.replace('ó', '[óo]')
  const patterns = [
    new RegExp(`(?:crea(?:r)?|nuevo|nueva|agrega(?:r)?|añade)\\s+(?:un|una)?\\s*${escaped}\\s+(?:llamad[oa]\\s+)?["“]?(.+?)["”]?[.!?]*$`, 'iu'),
    new RegExp(`${escaped}\\s*:\\s*["“]?(.+?)["”]?[.!?]*$`, 'iu'),
  ]
  for (const pattern of patterns) {
    const match = message.match(pattern)
    if (match) return clean(match[1])
  }
  return ''
}

export function normalizeAgentAction(action, originalMessage = '') {
  const arguments_ = { ...(action.arguments || {}) }
  const aliases = NAME_ALIASES[action.name] || []
  const aliasValue = aliases.map((key) => clean(arguments_[key])).find(Boolean)
  const extracted = aliasValue || extractActionName(action.name, originalMessage)
  if (extracted) arguments_.name = extracted
  return { ...action, arguments: arguments_ }
}
