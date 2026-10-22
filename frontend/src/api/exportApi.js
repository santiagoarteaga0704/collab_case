import api from './api'

export const exportJson = (diagramId) => api.get(`/diagrams/${diagramId}/export/json`, { responseType: 'blob' })
export const previewPlantUml = (diagramId) => api.get(`/diagrams/${diagramId}/export/plantuml/preview`)
export const downloadPlantUml = (diagramId) => api.get(`/diagrams/${diagramId}/export/plantuml`, { responseType: 'blob' })
export const previewSql = (diagramId) => api.get(`/diagrams/${diagramId}/export/sql/preview`)
export const downloadSql = (diagramId) => api.get(`/diagrams/${diagramId}/export/sql`, { responseType: 'blob' })
export const downloadXmi = (diagramId) => api.get(`/diagrams/${diagramId}/export/xmi`, { responseType: 'blob' })
