import api from './api'

export const validateBackendGeneration = (diagramId) => api.get(`/diagrams/${diagramId}/codegen/backend/validate`)
export const downloadGeneratedBackend = (diagramId) => api.post(`/diagrams/${diagramId}/codegen/backend`, null, { responseType: 'blob' })
