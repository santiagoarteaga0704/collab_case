import api from './api'

export function analyzeDiagram(diagramId, message) {
  return api.post(`/ai/diagrams/${diagramId}/analyze`, { message })
}
