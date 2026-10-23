import test from 'node:test'
import assert from 'node:assert/strict'
import { initialChatState, chatReducer, mentionsAi, aiStatusLabel } from './chatState.js'

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
