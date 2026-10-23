import test from 'node:test'
import assert from 'node:assert/strict'

class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 3
  static instances = []
  constructor(url) {
    this.url = url
    this.readyState = FakeWebSocket.CONNECTING
    this.sent = []
    this.listeners = {}
    FakeWebSocket.instances.push(this)
  }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn) }
  emit(type, ev = {}) { (this.listeners[type] || []).forEach((fn) => fn(ev)) }
  send(data) { this.sent.push(JSON.parse(data)) }
  open() { this.readyState = FakeWebSocket.OPEN; this.emit('open') }
  close(code = 1006) { this.readyState = FakeWebSocket.CLOSED; this.emit('close', { code }) }
  receive(obj) { this.emit('message', { data: JSON.stringify(obj) }) }
}

globalThis.WebSocket = FakeWebSocket
globalThis.window = {
  location: { protocol: 'http:', host: 'localhost' },
  setTimeout: (fn) => setTimeout(fn, 0),
  clearTimeout: (t) => clearTimeout(t),
}
const { createChatSocket } = await import('./chatSocket.js')
const tick = () => new Promise((r) => setTimeout(r, 5))

test('reconnectCallsBackSoTheChatCanCatchUp', async () => {
  FakeWebSocket.instances = []
  const calls = []
  const chat = createChatSocket({
    projectId: 'p', token: 't', onEvent: (e) => calls.push(e.kind),
    onDisconnect: () => calls.push('disconnect'), onReconnect: () => calls.push('reconnect'),
  })
  const first = FakeWebSocket.instances.at(-1)
  first.open()
  first.receive({ kind: 'auth_ok' })
  assert.deepEqual(calls, ['auth_ok'])

  first.close()
  assert.deepEqual(calls, ['auth_ok', 'disconnect'])
  assert.equal(chat.send('hola'), false)

  await tick()
  const second = FakeWebSocket.instances.at(-1)
  assert.notEqual(second, first)
  second.open()
  second.receive({ kind: 'auth_ok' })
  assert.deepEqual(calls, ['auth_ok', 'disconnect', 'auth_ok', 'reconnect'])
  assert.equal(chat.send('hola'), true)
  chat.close()
})

test('closingTheChatOnPurposeIsNotADisconnect', () => {
  FakeWebSocket.instances = []
  const calls = []
  const chat = createChatSocket({ projectId: 'p', token: 't', onDisconnect: () => calls.push('disconnect') })
  const ws = FakeWebSocket.instances.at(-1)
  ws.open()
  ws.receive({ kind: 'auth_ok' })
  chat.close()
  assert.deepEqual(calls, [])
})
