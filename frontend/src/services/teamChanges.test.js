import test from 'node:test'
import assert from 'node:assert/strict'
import { applyTeamChanges } from './teamChanges.js'

const model = { nodes: [], edges: [] }

test('appliesClassesBeforeRelations', async () => {
  const seen = []
  const suggestions = [
    { type: 'ADD_RELATIONSHIP', sourceClass: 'A', targetClassName: 'B', relationshipType: 'ASSOCIATION', description: 'A-B' },
    { type: 'ADD_CLASS', className: 'A' },
    { type: 'ADD_CLASS', className: 'B' },
  ]
  const out = await applyTeamChanges({
    suggestions, getModel: () => model,
    applyOne: async (s) => { seen.push(s.type); return { status: 'applied', message: '' } },
  })
  assert.deepEqual(seen, ['ADD_CLASS', 'ADD_CLASS', 'ADD_RELATIONSHIP'])
  assert.deepEqual(out.applied, ['A', 'B', 'A-B'])
  assert.deepEqual(out.failed, [])
})

test('lockedSuggestionIsReportedAsFailedWithReason', async () => {
  const out = await applyTeamChanges({
    suggestions: [{ type: 'ADD_CLASS', className: 'Pedido' }], getModel: () => model,
    applyOne: async () => ({ status: 'locked', message: 'Este elemento está siendo editado por Ana' }),
  })
  assert.deepEqual(out.applied, [])
  assert.deepEqual(out.failed, [{ name: 'Pedido', reason: 'Este elemento está siendo editado por Ana' }])
})

test('invalidSuggestionIsFailedWithoutCallingApplyOne', async () => {
  let called = 0
  const out = await applyTeamChanges({
    suggestions: [{ type: 'ADD_ATTRIBUTE', targetClass: 'Nada', attributeName: 'x' }], getModel: () => model,
    applyOne: async () => { called++; return { status: 'applied' } },
  })
  assert.equal(called, 0)
  assert.equal(out.failed[0].name, 'x')
})

test('memberNamesAreReportedAsTheServerAcceptsThem', async () => {
  const m = { nodes: [{ id: '1', type: 'class', data: { name: 'A', attributes: [], methods: [] } }], edges: [] }
  const out = await applyTeamChanges({
    suggestions: [
      { type: 'ADD_ATTRIBUTE', targetClass: 'A', attributeName: 'edad', dataType: 'int' },
      { type: 'ADD_METHOD', targetClass: 'A', methodName: 'calcular', returnType: 'int' },
    ],
    getModel: () => m, applyOne: async () => ({ status: 'applied', message: '' }),
  })
  assert.deepEqual(out.applied, ['edad', 'calcular'])
})
