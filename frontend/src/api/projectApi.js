import api from './api.js'

export function createProject(data) {
  return api.post('/projects', data)
}

export function getProjects() {
  return api.get('/projects')
}

export function getProject(id) {
  return api.get(`/projects/${id}`)
}

export function updateProject(id, data) {
  return api.put(`/projects/${id}`, data)
}

export function renameProject(id, name) {
  return api.patch(`/projects/${id}`, { name })
}
export function deleteProject(id) { return api.delete(`/projects/${id}`) }

export function getProjectDiagrams(projectId) {
  return api.get(`/projects/${projectId}/diagrams`)
}

export function createProjectDiagram(projectId, data) {
  return api.post(`/projects/${projectId}/diagrams`, data)
}

export function getProjectMembers(projectId) {
  return api.get(`/projects/${projectId}/members`)
}

export function addProjectMember(projectId, data) {
  return api.post(`/projects/${projectId}/members`, data)
}

export function updateProjectMember(projectId, userId, data) {
  return api.put(`/projects/${projectId}/members/${userId}`, data)
}

export function removeProjectMember(projectId, userId) {
  return api.delete(`/projects/${projectId}/members/${userId}`)
}
