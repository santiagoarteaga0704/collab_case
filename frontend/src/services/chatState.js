export const initialChatState = {
  messages: [], aiConnected: false, queued: 0, helpLevel: 'DRAW', positions: {},
}

const MENTION = /(^|\s)@ia\b/i

export function mentionsAi(text) {
  return typeof text === 'string' && MENTION.test(text)
}

function upsert(messages, message) {
  const index = messages.findIndex((m) => m.id === message.id)
  if (index === -1) return [...messages, message]
  const next = messages.slice()
  next[index] = message
  return next
}

// createdAt is an ISO instant that may carry micro/nanoseconds; Date.parse would drop them.
function timeOf(value) {
  if (typeof value === 'number') return [value, 0]
  if (typeof value !== 'string') return [Infinity, 0]
  const fraction = value.match(/\.(\d+)/)
  const millis = Date.parse(value.replace(/\.\d+/, ''))
  if (Number.isNaN(millis)) return [Infinity, 0]
  return [millis, fraction ? Number(fraction[1].padEnd(9, '0').slice(0, 9)) : 0]
}

function byTimeThenId(a, b) {
  const [ma, na] = timeOf(a.createdAt)
  const [mb, nb] = timeOf(b.createdAt)
  if (ma !== mb) return ma < mb ? -1 : 1
  if (na !== nb) return na - nb
  if (ma === Infinity) return 0
  return String(a.id).localeCompare(String(b.id))
}

export function chatReducer(state, event) {
  switch (event?.kind) {
    case 'history':
      // merge, never replace: live messages that came first and older ones stay; the list is re-sorted
      return { ...state, messages: (event.messages ?? []).reduce(upsert, state.messages.slice()).sort(byTimeThenId) }
    case 'message':
    case 'message_updated': {
      if (!event.message) return state
      const positions = { ...state.positions }
      if (event.message.status !== 'QUEUED') delete positions[event.message.id]
      return { ...state, messages: upsert(state.messages, event.message), positions }
    }
    case 'chunk': {
      if (!state.messages.some((m) => m.id === event.messageId)) return state
      return {
        ...state,
        messages: state.messages.map((m) => m.id === event.messageId
          ? { ...m, content: (m.content ?? '') + (event.text ?? '') } : m),
      }
    }
    case 'queue_position':
      return { ...state, positions: { ...state.positions, [event.messageId]: event.position } }
    case 'ai_status':
      return { ...state, aiConnected: Boolean(event.connected), queued: event.queued ?? 0 }
    case 'help_level':
      return { ...state, helpLevel: event.level }
    default:
      return state
  }
}

// Aplicar stays disabled after a click until the server sends a new version of that message.
export function markApplying(applying, message) {
  return { ...applying, [message.id]: message }
}

export function isApplying(applying, message) {
  return Boolean(message) && applying[message.id] === message
}

// These texts are the ones the server stores as the content of a failed answer.
const OFFLINE_TEXT = 'IA desconectada'
const TIMEOUT_TEXT = 'La IA no respondió a tiempo'

export function aiStatusLabel(state, message) {
  if (!message) return ''
  if (message.status === 'QUEUED') return `en cola, posición ${state.positions[message.id] ?? 1}`
  if (message.status === 'WRITING') return 'escribiendo…'
  if (message.status === 'FAILED') {
    const content = (message.content ?? '').trim()
    if (content === OFFLINE_TEXT || content === TIMEOUT_TEXT) return content
  }
  return ''
}
