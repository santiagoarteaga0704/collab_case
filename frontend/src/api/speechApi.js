import api from './api'

export function transcribeAudio(blob, filename, signal) {
  const form = new FormData()
  form.append('file', blob, filename)
  return api.post('/speech/transcribe', form, { signal })
}
