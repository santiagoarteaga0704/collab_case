import api from './api.js'

export function getChatHistory(projectId) {
  return api.get(`/projects/${projectId}/chat`)
}

export function getChatStatus(projectId) {
  return api.get(`/projects/${projectId}/chat/status`)
}

export function setHelpLevel(projectId, level) {
  return api.put(`/projects/${projectId}/chat/help-level`, { level })
}
