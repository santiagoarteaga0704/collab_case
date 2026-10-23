import test from 'node:test'
import assert from 'node:assert/strict'
import { initialChatState, chatReducer, mentionsAi, aiStatusLabel, markApplying, isApplying } from './chatState.js'

const msg = (id, extra = {}) => ({ id, content: '', status: 'COMPLETE', kind: 'AI', ...extra })

test('chunksForDifferentMessagesDoNotMix', () => {
  let s = chatReducer(initialChatState, { kind: 'history', messages: [msg('a'), msg('b')] })
  s = chatReducer(s, { kind: 'chunk', messageId: 'a', text: 'Ho' })
  s = chatReducer(s, { kind: 'chunk', messageId: 'b', text: 'Adiós' })
  s = chatReducer(s, { kind: 'chunk', messageId: 'a', text: 'la' })
  assert.equal(s.messages.find((m) => m.id === 'a').content, 'Hola')
  assert.equal(s.messages.find((m) => m.id === 'b').content, 'Adiós')
})

test('messageUpdatedReplacesContentButKeepsOrder', () => {
  let s = chatReducer(initialChatState, { kind: 'history', messages: [msg('a', { content: 'x' }), msg('b')] })
  s = chatReducer(s, { kind: 'message_updated', message: msg('a', { content: 'final' }) })
  assert.deepEqual(s.messages.map((m) => m.id), ['a', 'b'])
  assert.equal(s.messages[0].content, 'final')
})

test('historyThenLiveMessagesDeduplicateById', () => {
  let s = chatReducer(initialChatState, { kind: 'history', messages: [msg('a')] })
  s = chatReducer(s, { kind: 'message', message: msg('a', { content: 'again' }) })
  s = chatReducer(s, { kind: 'message', message: msg('c') })
  assert.deepEqual(s.messages.map((m) => m.id), ['a', 'c'])
  assert.equal(s.messages[0].content, 'again')
})

test('aiStatusLabelShowsQueuePosition', () => {
  let s = chatReducer(initialChatState, { kind: 'queue_position', messageId: 'a', position: 2 })
  assert.equal(aiStatusLabel(s, msg('a', { status: 'QUEUED' })), 'en cola, posición 2')
  assert.equal(aiStatusLabel(s, msg('a', { status: 'WRITING' })), 'escribiendo…')
  assert.equal(aiStatusLabel(s, msg('a', { status: 'FAILED', content: 'IA desconectada' })), 'IA desconectada')
  assert.equal(aiStatusLabel(s, msg('a', { status: 'FAILED', content: 'La IA no respondió a tiempo' })),
    'La IA no respondió a tiempo')
  assert.equal(aiStatusLabel(s, msg('a')), '')
})

test('aiStatusAndHelpLevelEventsUpdateState', () => {
  let s = chatReducer(initialChatState, { kind: 'ai_status', connected: true, queued: 3 })
  s = chatReducer(s, { kind: 'help_level', level: 'SUGGEST' })
  assert.equal(s.aiConnected, true)
  assert.equal(s.queued, 3)
  assert.equal(s.helpLevel, 'SUGGEST')
})

test('mentionsAiMatchesOnlyTheMention', () => {
  assert.equal(mentionsAi('@IA resumen'), true)
  assert.equal(mentionsAi('hola @ia ayuda'), true)
  assert.equal(mentionsAi('@ia'), true)
  assert.equal(mentionsAi('mail@ia.com'), false)
  assert.equal(mentionsAi('@iaaa'), false)
  assert.equal(mentionsAi('hola'), false)
  assert.equal(mentionsAi(null), false)
})

// ---- final fix wave ----

test('historyMergesWithMessagesAlreadyShown', () => {
  let s = chatReducer(initialChatState, { kind: 'history', messages: [
    msg('a', { createdAt: '2026-10-23T12:00:00.000001Z' }), msg('b', { createdAt: '2026-10-23T12:00:01Z' })] })
  // live message that arrived while the socket was up
  s = chatReducer(s, { kind: 'message', message: msg('d', { createdAt: '2026-10-23T12:00:05Z' }) })
  // catch-up after a reconnect: b changed and c was missed while down
  s = chatReducer(s, { kind: 'history', messages: [
    msg('b', { createdAt: '2026-10-23T12:00:01Z', content: 'final' }),
    msg('c', { createdAt: '2026-10-23T12:00:03Z' })] })
  assert.deepEqual(s.messages.map((m) => m.id), ['a', 'b', 'c', 'd'])
  assert.equal(s.messages.find((m) => m.id === 'b').content, 'final')
})

test('liveMessageBeforeHistoryIsNotDropped', () => {
  let s = chatReducer(initialChatState, { kind: 'message', message: msg('z', { createdAt: '2026-10-23T12:00:09Z' }) })
  s = chatReducer(s, { kind: 'history', messages: [msg('a', { createdAt: '2026-10-23T12:00:00Z' })] })
  assert.deepEqual(s.messages.map((m) => m.id), ['a', 'z'])
})

test('historyOrdersBySubMillisecondTimeThenId', () => {
  const s = chatReducer(initialChatState, { kind: 'history', messages: [
    msg('q', { createdAt: '2026-10-23T12:00:00.000300Z' }),
    msg('p', { createdAt: '2026-10-23T12:00:00.0002Z' }),
    msg('y', { createdAt: '2026-10-23T12:00:00.0003Z' })] })
  assert.deepEqual(s.messages.map((m) => m.id), ['p', 'q', 'y'])
})

test('applyStaysDisabledUntilTheMessageChanges', () => {
  let s = chatReducer(initialChatState, { kind: 'history', messages: [msg('a', { proposalState: 'PENDING' })] })
  const shown = s.messages[0]
  const applying = markApplying({}, shown)
  assert.equal(isApplying(applying, shown), true)
  assert.equal(isApplying(applying, msg('b')), false)
  s = chatReducer(s, { kind: 'message_updated', message: msg('a', { proposalState: 'PENDING' }) })
  assert.equal(isApplying(applying, s.messages[0]), false)
})
