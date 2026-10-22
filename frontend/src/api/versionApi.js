import api from './api.js'
export const getVersions = (projectId) => api.get(`/projects/${projectId}/versions`)
export const createVersion = (projectId, data) => api.post(`/projects/${projectId}/versions`, data)
export const renameVersion = (projectId, versionId, name) => api.patch(`/projects/${projectId}/versions/${versionId}`, { name })
export const deleteVersion = (projectId, versionId) => api.delete(`/projects/${projectId}/versions/${versionId}`)
export const getVersionDiagrams = (projectId, versionId) => api.get(`/projects/${projectId}/versions/${versionId}/diagrams`)
export const createVersionDiagram = (projectId, versionId, data) => api.post(`/projects/${projectId}/versions/${versionId}/diagrams`, data)
