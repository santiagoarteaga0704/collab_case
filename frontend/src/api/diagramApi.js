import api from './api.js'

export function getDiagram(id) {
  return api.get(`/diagrams/${id}`)
}

export function updateDiagram(id, data) {
  return api.put(`/diagrams/${id}`, data)
}

export function renameDiagram(id, name) {
  return api.patch(`/diagrams/${id}`, { name })
}
export function deleteDiagram(id) { return api.delete(`/diagrams/${id}`) }
