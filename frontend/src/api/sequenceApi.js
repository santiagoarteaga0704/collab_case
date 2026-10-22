import api from './api'

export const generateSequenceDiagram = (diagramId, data) => api.post(`/diagrams/${diagramId}/sequence-diagrams`, data)
export const getSequenceDiagrams = (diagramId) => api.get(`/diagrams/${diagramId}/sequence-diagrams`)
export const getSequenceDiagram = (id) => api.get(`/sequence-diagrams/${id}`)
export const updateSequenceDiagram = (id, data) => api.patch(`/sequence-diagrams/${id}`, data)
export const regenerateSequenceDiagram = (id) => api.post(`/sequence-diagrams/${id}/regenerate`)
export const deleteSequenceDiagram = (id) => api.delete(`/sequence-diagrams/${id}`)
export const downloadSequencePlantUml = (id) => api.get(`/sequence-diagrams/${id}/plantuml`, { responseType: 'blob' })
