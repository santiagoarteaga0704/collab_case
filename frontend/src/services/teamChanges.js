import { SUGGESTION_ORDER, validateAiSuggestion } from './aiSuggestionApplier.js'

// The name the server accepts for each kind of change (see TeamAssistant.proposalNames).
function suggestionName(suggestion) {
  switch (suggestion.type) {
    case 'ADD_CLASS': return suggestion.className
    case 'ADD_ATTRIBUTE': return suggestion.attributeName
    case 'ADD_METHOD': return suggestion.methodName
    default: return suggestion.description
  }
}

const normalized = (value) => typeof value === 'string' ? value.trim().toLocaleLowerCase() : ''

export async function applyTeamChanges({ suggestions, applyOne, getModel }) {
  const ordered = (suggestions ?? [])
    .map((suggestion, index) => ({ suggestion, index }))
    .filter(({ suggestion }) => SUGGESTION_ORDER[suggestion?.type] !== undefined)
    .sort((a, b) => SUGGESTION_ORDER[a.suggestion.type] - SUGGESTION_ORDER[b.suggestion.type]
      || a.index - b.index)
    .map(({ suggestion }) => suggestion)
  const suggestedClassNames = new Set(ordered
    .filter((s) => s.type === 'ADD_CLASS').map((s) => normalized(s.className)))
  const applied = []
  const failed = []
  for (const suggestion of ordered) {
    const name = suggestionName(suggestion) ?? ''
    const validation = validateAiSuggestion({ model: getModel(), suggestion, suggestedClassNames })
    if (validation.status !== 'pending') {
      failed.push({ name, reason: validation.message })
      continue
    }
    const outcome = await applyOne(suggestion)
    if (outcome?.status === 'applied') applied.push(name)
    else failed.push({ name, reason: outcome?.message || 'No se pudo aplicar el cambio.' })
  }
  return { applied, failed }
}
