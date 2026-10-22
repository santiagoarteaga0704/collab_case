import { useEffect, useRef, useState } from 'react'

import { transcribeAudio } from '../api/speechApi'

const MAX_DURATION_MS = 60_000
const MAX_AUDIO_BYTES = 10 * 1024 * 1024
const MIME_TYPES = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
]

const extensionFor = (mimeType) => {
  if (mimeType.includes('ogg')) return 'ogg'
  if (mimeType.includes('mp4')) return 'mp4'
  return 'webm'
}

function microphoneError(error) {
  if (error?.name === 'NotAllowedError') return 'No se concedio permiso para usar el microfono.'
  if (error?.name === 'NotFoundError') return 'No se encontro un microfono disponible.'
  if (error?.name === 'NotReadableError') return 'El microfono esta ocupado o no puede utilizarse.'
  return 'No se pudo iniciar la grabacion de audio.'
}

export function useAudioRecorder(onTranscript) {
  const isSupported = typeof navigator !== 'undefined'
    && Boolean(navigator.mediaDevices?.getUserMedia)
    && typeof MediaRecorder !== 'undefined'
  const [status, setStatus] = useState('idle')
  const [error, setError] = useState('')
  const recorderRef = useRef()
  const streamRef = useRef()
  const chunksRef = useRef([])
  const timeoutRef = useRef()
  const requestRef = useRef()
  const mountedRef = useRef(true)
  const callbackRef = useRef(onTranscript)

  useEffect(() => {
    callbackRef.current = onTranscript
  }, [onTranscript])

  function releaseMicrophone() {
    window.clearTimeout(timeoutRef.current)
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = undefined
  }

  useEffect(() => () => {
    mountedRef.current = false
    window.clearTimeout(timeoutRef.current)
    requestRef.current?.abort()
    const recorder = recorderRef.current
    if (recorder && recorder.state !== 'inactive') {
      recorder.ondataavailable = null
      recorder.onstop = null
      recorder.stop()
    }
    releaseMicrophone()
    chunksRef.current = []
  }, [])

  async function uploadRecording(mimeType) {
    const blob = new Blob(chunksRef.current, { type: mimeType })
    chunksRef.current = []
    if (!blob.size) throw new Error('La grabacion esta vacia.')
    if (blob.size > MAX_AUDIO_BYTES) throw new Error('El audio supera el limite de 10 MB.')
    const controller = new AbortController()
    requestRef.current = controller
    const response = await transcribeAudio(
      blob,
      `recording.${extensionFor(mimeType)}`,
      controller.signal,
    )
    callbackRef.current(response.data.text)
  }

  async function start() {
    if (!isSupported || status !== 'idle') return
    setError('')
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      if (!mountedRef.current) {
        stream.getTracks().forEach((track) => track.stop())
        return
      }
      const mimeType = MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type)) ?? ''
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream)
      streamRef.current = stream
      recorderRef.current = recorder
      chunksRef.current = []
      recorder.ondataavailable = (event) => {
        if (event.data.size) chunksRef.current.push(event.data)
      }
      recorder.onerror = () => {
        releaseMicrophone()
        if (mountedRef.current) {
          setStatus('idle')
          setError('Ocurrio un error durante la grabacion.')
        }
      }
      recorder.onstop = async () => {
        releaseMicrophone()
        if (!mountedRef.current) return
        setStatus('transcribing')
        try {
          await uploadRecording(recorder.mimeType || mimeType || 'audio/webm')
        } catch (requestError) {
          if (requestError.name !== 'CanceledError' && requestError.name !== 'AbortError') {
            setError(requestError.response?.data?.message ?? requestError.message
              ?? 'No se pudo transcribir el audio.')
          }
        } finally {
          requestRef.current = undefined
          if (mountedRef.current) setStatus('idle')
        }
      }
      recorder.start(250)
      setStatus('recording')
      timeoutRef.current = window.setTimeout(() => {
        if (recorder.state === 'recording') recorder.stop()
      }, MAX_DURATION_MS)
    } catch (recordingError) {
      releaseMicrophone()
      setStatus('idle')
      setError(microphoneError(recordingError))
    }
  }

  function stop() {
    window.clearTimeout(timeoutRef.current)
    if (recorderRef.current?.state === 'recording') recorderRef.current.stop()
  }

  return {
    isSupported,
    isRecording: status === 'recording',
    isTranscribing: status === 'transcribing',
    error,
    start,
    stop,
  }
}
