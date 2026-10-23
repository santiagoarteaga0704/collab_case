const DEFAULT_WS_BASE_URL =
  `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
const INITIAL_RECONNECT_DELAY_MS = 1000
const MAX_RECONNECT_DELAY_MS = 10000

// onDisconnect: the socket went down (it will retry). onReconnect: authenticated again after a drop,
// so whatever was published meanwhile must be fetched again.
export function createChatSocket({ projectId, diagramId, token, onEvent, onDisconnect, onReconnect }) {
  let socket
  let reconnectTimer
  let reconnectDelay = INITIAL_RECONNECT_DELAY_MS
  let stopped = false
  let terminalClose = false
  let authenticated = false
  let everAuthenticated = false

  function connect() {
    if (stopped || terminalClose ||
        socket?.readyState === WebSocket.CONNECTING || socket?.readyState === WebSocket.OPEN) return
    const baseUrl = import.meta.env?.VITE_WS_BASE_URL || DEFAULT_WS_BASE_URL
    const connection = new WebSocket(`${baseUrl}/ws/chat/${projectId}`)
    socket = connection

    connection.addEventListener('open', () => {
      if (stopped || terminalClose) {
        connection.close(1000, 'Connection disposed')
        return
      }
      const auth = { kind: 'auth', token }
      if (diagramId) auth.diagramId = diagramId
      connection.send(JSON.stringify(auth))
    })

    connection.addEventListener('message', (event) => {
      if (stopped) return
      let message
      try { message = JSON.parse(event.data) } catch { return }
      const reconnected = message.kind === 'auth_ok' && everAuthenticated
      if (message.kind === 'auth_ok') {
        authenticated = true
        everAuthenticated = true
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
      } else if (message.kind === 'auth_error') {
        terminalClose = true
        authenticated = false
        connection.close()
      }
      onEvent?.(message)
      if (reconnected) onReconnect?.()
    })

    connection.addEventListener('close', (event) => {
      authenticated = false
      if (socket === connection) socket = undefined
      if (!stopped) onDisconnect?.()
      if ([4001, 4003, 4004].includes(event.code)) terminalClose = true
      if (!stopped && !terminalClose) {
        reconnectTimer = window.setTimeout(connect, reconnectDelay)
        reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS)
      }
    })
  }

  function send(message) {
    if (authenticated && socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message))
      return true
    }
    return false
  }

  connect()

  return {
    send: (content, isPrivate = false) => send({ kind: 'send', content, private: Boolean(isPrivate) }),
    reportApplied: (messageId, applied, failed) =>
      send({ kind: 'changes_applied', messageId, applied, failed }),
    applyProposal: (messageId) => send({ kind: 'apply_proposal', messageId }),
    close() {
      if (stopped) return
      stopped = true
      authenticated = false
      window.clearTimeout(reconnectTimer)
      if (socket?.readyState === WebSocket.OPEN) socket.close(1000, 'Chat closed')
    },
  }
}
