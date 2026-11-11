import api from './api'

export const validateBackendGeneration = (diagramId) => api.get(`/diagrams/${diagramId}/codegen/backend/validate`)
export const downloadGeneratedBackend = (diagramId, onDownloadProgress) =>
  api.post(`/diagrams/${diagramId}/codegen/backend`, null, { responseType: 'blob', onDownloadProgress })
