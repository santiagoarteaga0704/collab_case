import api from './api'

export function extractDiagramFromImage(diagramId, file, instruction, signal) {
  const form = new FormData()
  form.append('file', file)
  if (instruction?.trim()) form.append('instruction', instruction.trim())
  return api.post(`/vision/diagrams/${diagramId}/extract`, form, { signal })
}
