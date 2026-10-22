import api from './api'

export function importXmi(projectId, file, mode, diagramId, versionId) {
  const form = new FormData()
  form.append('file', file)
  form.append('mode', mode)
  if (mode === 'REPLACE') form.append('diagramId', diagramId)
  if (versionId) form.append('versionId', versionId)
  return api.post(`/projects/${projectId}/import/xmi`, form)
}
