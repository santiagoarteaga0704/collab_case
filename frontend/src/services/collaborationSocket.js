const DEFAULT_WS_BASE_URL =
  `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}`
const INITIAL_RECONNECT_DELAY_MS = 1000
const MAX_RECONNECT_DELAY_MS = 10000

export function createCollaborationSocket(options) {
  let socket
  let reconnectTimer
  let reconnectDelay = INITIAL_RECONNECT_DELAY_MS
  let stopped = false
  let terminalClose = false
  let authenticated = false
  let currentRole

  function connect() {
    if (stopped || terminalClose ||
        socket?.readyState === WebSocket.CONNECTING || socket?.readyState === WebSocket.OPEN) return
    const baseUrl = import.meta.env.VITE_WS_BASE_URL || DEFAULT_WS_BASE_URL
    const connection = new WebSocket(`${baseUrl}/ws/collaboration/${options.diagramId}`)
    socket = connection

    connection.addEventListener('open', () => {
      if (stopped || terminalClose) {
        connection.close(1000, 'Connection disposed')
        return
      }
      connection.send(JSON.stringify({ kind: 'auth', token: options.token }))
    })

    connection.addEventListener('message', (event) => {
      if (stopped) return
      let message
      try { message = JSON.parse(event.data) } catch { return }

      if (message.kind === 'auth_ok') {
        authenticated = true
        currentRole = message.role
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        options.onAuthenticated?.(message.role)
      } else if (message.kind === 'yjs' && authenticated && typeof message.data === 'string') {
        options.onYjsMessage?.(message.data)
      } else if (message.kind === 'role_changed') {
        currentRole = message.role
        options.onRoleChanged?.(message.role)
      } else if (message.kind === 'diagram_renamed') {
        options.onDiagramRenamed?.(message.name)
      } else if (message.kind === 'access_revoked') {
        terminalClose = true
        authenticated = false
        options.onAccessRevoked?.()
        connection.close()
      } else if (message.kind === 'locks_snapshot') {
        options.onLocksSnapshot?.(message.locks || [])
      } else if (message.kind === 'lock_granted') {
        options.onLockGranted?.(message)
      } else if (message.kind === 'lock_denied') {
        options.onLockDenied?.(message)
      } else if (message.kind === 'lock_changed') {
        options.onLockChanged?.(message)
      } else if (message.kind === 'auth_error') {
        terminalClose = true
        authenticated = false
        options.onAuthenticationError?.()
        connection.close()
      }
    })

    connection.addEventListener('close', (event) => {
      authenticated = false
      if (socket === connection) socket = undefined
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
    }
  }

  function sendYjs(data) {
    const frameType = data ? atob(data).charCodeAt(0) : -1
    if (currentRole === 'VIEWER' && frameType === 1) return
    send({ kind: 'yjs', data })
  }

  function requestLock(elementId) {
    if (currentRole !== 'VIEWER') send({ kind: 'lock_request', elementId })
  }

  function renewLock(elementId) {
    if (currentRole !== 'VIEWER') send({ kind: 'lock_renew', elementId })
  }

  function releaseLock(elementId) {
    send({ kind: 'lock_release', elementId })
  }

  function close() {
    if (stopped) return
    stopped = true
    authenticated = false
    window.clearTimeout(reconnectTimer)
    if (socket?.readyState === WebSocket.OPEN) {
      socket.close(1000, 'Editor closed')
    }
    // A CONNECTING socket is closed by its open handler, before authentication.
  }

  return { connect, sendYjs, requestLock, renewLock, releaseLock, close }
}
