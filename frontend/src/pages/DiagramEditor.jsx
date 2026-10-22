import { useEffect, useRef, useState } from 'react'
import { Apollon, ApollonEditor, UMLDiagramType } from '@tumaet/apollon'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Bot, Check, Download, FileCode2, ImagePlus, LoaderCircle, Lock, Mic, Network, Pencil, Sparkles, Square, Trash2, Upload, X } from 'lucide-react'
import '@tumaet/apollon/style.css'

import { analyzeDiagram } from '../api/aiApi'
import { deleteDiagram, getDiagram, renameDiagram, updateDiagram } from '../api/diagramApi'
import { createProjectDiagram, getProject } from '../api/projectApi'
import { createVersionDiagram, getVersions } from '../api/versionApi'
import { extractDiagramFromImage } from '../api/visionApi'
import { downloadPlantUml, downloadSql, downloadXmi, exportJson, previewPlantUml, previewSql } from '../api/exportApi'
import { importXmi } from '../api/importApi'
import { downloadGeneratedBackend, validateBackendGeneration } from '../api/codegenApi'
import { useAuth } from '../context/AuthContext'
import { useAudioRecorder } from '../hooks/useAudioRecorder'
import {
  applyAiSuggestion,
  SUGGESTION_ORDER,
  validateAiSuggestion,
} from '../services/aiSuggestionApplier'
import { createCollaborationSocket } from '../services/collaborationSocket'
import { deleteSequenceDiagram, generateSequenceDiagram, getSequenceDiagrams, regenerateSequenceDiagram } from '../api/sequenceApi'
import { apiErrorMessage } from '../api/errorMessage'
import Toast from '../components/Toast'
import { useAgentRuntimeContext } from '../context/AgentRuntimeContext'

const SAVE_DELAY_MS = 800
const LOCK_RENEW_MS = 10000
const AUTO_REANALYZE_DELAY_MS = 1500
const LOCK_COLOR = 'rgba(239, 68, 68, 0.32)'
const COLORS = ['#2563eb', '#dc2626', '#16a34a', '#9333ea', '#ea580c']
const SUGGESTION_LABELS = {
  ADD_CLASS: 'Clase', ADD_ATTRIBUTE: 'Atributo', ADD_METHOD: 'Método',
  ADD_RELATIONSHIP: 'Relación', CHANGE_RELATIONSHIP_TYPE: 'Relación',
  CHANGE_CARDINALITY: 'Cardinalidad', WARNING: 'Advertencia', IMPROVEMENT: 'Mejora',
}
const APPLICABLE_SUGGESTION_TYPES = new Set([
  'ADD_CLASS',
  'ADD_ATTRIBUTE',
  'ADD_METHOD',
  'ADD_RELATIONSHIP',
  'CHANGE_RELATIONSHIP_TYPE',
  'CHANGE_CARDINALITY',
])

function userColor(id = '') {
  const hash = [...id].reduce((total, character) => total + character.charCodeAt(0), 0)
  return COLORS[hash % COLORS.length]
}

function DiagramEditor() {
  const { projectId, versionId, diagramId } = useParams()
  const { updateRuntimeContext } = useAgentRuntimeContext()
  const navigate = useNavigate()
  const { user } = useAuth()
  const [initialModel, setInitialModel] = useState()
  const [role, setRole] = useState()
  const [diagramName, setDiagramName] = useState('Diagrama sin título')
  const [projectName, setProjectName] = useState('Proyecto')
  const [versionName, setVersionName] = useState('main')
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [saveStatus, setSaveStatus] = useState('Guardado')
  const [notice, setNotice] = useState('')
  const [renameName, setRenameName] = useState('')
  const [renaming, setRenaming] = useState(false)
  const [renameError, setRenameError] = useState('')
  const [sequenceOpen, setSequenceOpen] = useState(false)
  const [sequenceName, setSequenceName] = useState('')
  const [sequenceScenario, setSequenceScenario] = useState('')
  const [entryClass, setEntryClass] = useState('')
  const [entryMethod, setEntryMethod] = useState('')
  const [sequences, setSequences] = useState([])
  const [selectedSequence, setSelectedSequence] = useState()
  const [sequenceError, setSequenceError] = useState('')
  const [sequenceBusy, setSequenceBusy] = useState(false)
  const [selectionReadOnly, setSelectionReadOnly] = useState(false)
  const [lockStatus, setLockStatus] = useState('')
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [aiMessage, setAiMessage] = useState('')
  const [aiResult, setAiResult] = useState()
  const [aiError, setAiError] = useState('')
  const [isAnalyzing, setIsAnalyzing] = useState(false)
  const [isAutoAnalyzing, setIsAutoAnalyzing] = useState(false)
  const [pendingSuggestionId, setPendingSuggestionId] = useState()
  const [imageFile, setImageFile] = useState()
  const [imagePreview, setImagePreview] = useState('')
  const [imageInstruction, setImageInstruction] = useState('')
  const [imageExtraction, setImageExtraction] = useState()
  const [isExtractingImage, setIsExtractingImage] = useState(false)
  const [imageError, setImageError] = useState('')
  const [exportOpen, setExportOpen] = useState(false)
  const [exportFormat, setExportFormat] = useState('json')
  const [exportPreview, setExportPreview] = useState()
  const [exportError, setExportError] = useState('')
  const [isExporting, setIsExporting] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [importFile, setImportFile] = useState()
  const [importMode, setImportMode] = useState('NEW')
  const [importError, setImportError] = useState('')
  const [isImporting, setIsImporting] = useState(false)
  const [codegenOpen, setCodegenOpen] = useState(false)
  const [codegenValidation, setCodegenValidation] = useState()
  const [codegenError, setCodegenError] = useState('')
  const [isCodegenBusy, setIsCodegenBusy] = useState(false)
  const diagramIdRef = useRef(diagramId ?? null)
  const editorRef = useRef()
  const socketRef = useRef()
  const timeoutRef = useRef()
  const latestModelRef = useRef()
  const modelRevisionRef = useRef(0)
  const analyzingRef = useRef(false)
  const reanalysisTimerRef = useRef()
  const createdClassIdsRef = useRef(new Map())
  const lastSaveFailedRef = useRef(false)
  const pendingAiLockRef = useRef()
  const isSavingRef = useRef(false)
  const hasPendingSaveRef = useRef(false)
  const savingDisabledRef = useRef(false)
  const isMountedRef = useRef(false)
  const roleRef = useRef()
  const ownLockRef = useRef()
  const selectedElementRef = useRef()
  const diagramNameRef = useRef('Diagrama sin título')
  const imageInputRef = useRef()
  const visionRequestRef = useRef()
  const remoteLocksRef = useRef(new Map())
  const isViewer = role === 'VIEWER'
  const collaborationEnabled = Boolean(diagramId)
  const audioRecorder = useAudioRecorder((text) => setAiMessage((current) => {
    const separator = current.trim() && text.trim() ? ' ' : ''
    return `${current.trim()}${separator}${text.trim()}`
  }))

  useEffect(() => () => visionRequestRef.current?.abort(), [])

  useEffect(() => () => {
    if (imagePreview) URL.revokeObjectURL(imagePreview)
  }, [imagePreview])

  function selectImage(file) {
    if (imagePreview) URL.revokeObjectURL(imagePreview)
    setImageError('')
    setImageExtraction(undefined)
    if (!file) {
      setImageFile(undefined)
      setImagePreview('')
      return
    }
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
      setImageError('Usa una imagen PNG, JPEG o WebP.')
      return
    }
    if (file.size > 10 * 1024 * 1024) {
      setImageError('La imagen supera el limite de 10 MB.')
      return
    }
    setImageFile(file)
    setImagePreview(URL.createObjectURL(file))
  }

  async function analyzeImage() {
    if (!imageFile || !diagramId || isExtractingImage) return
    const controller = new AbortController()
    visionRequestRef.current = controller
    setIsExtractingImage(true)
    setImageError('')
    try {
      const response = await extractDiagramFromImage(
        diagramId, imageFile, imageInstruction, controller.signal,
      )
      setImageExtraction(response.data)
      const suggestions = revalidateSuggestions(response.data.suggestions.map((suggestion) => ({
        ...suggestion,
        clientId: crypto.randomUUID(),
        clientStatus: 'ready',
        selected: false,
      })))
      setAiResult({
        message: response.data.summary,
        provider: `${response.data.provider} (imagen)`,
        suggestions,
      })
    } catch (error) {
      if (error.name !== 'CanceledError' && error.name !== 'AbortError') {
        setImageError(error.response?.data?.message ?? 'No se pudo analizar la imagen.')
      }
    } finally {
      visionRequestRef.current = undefined
      setIsExtractingImage(false)
    }
  }

  function cancelImage() {
    visionRequestRef.current?.abort()
    if (imagePreview) URL.revokeObjectURL(imagePreview)
    setImageFile(undefined)
    setImagePreview('')
    setImageExtraction(undefined)
    setImageError('')
    if (imageInputRef.current) imageInputRef.current.value = ''
  }

  useEffect(() => {
    let active = true
    let redirecting = false
    const requests = [getProject(projectId), getVersions(projectId)]
    if (diagramId) requests.push(getDiagram(diagramId))
    Promise.all(requests)
      .then(async ([projectResponse, versionsResponse, diagramResponse]) => {
        if (!active) return
        roleRef.current = projectResponse.data.role
        setRole(projectResponse.data.role)
        setProjectName(projectResponse.data.name)
        const currentVersion = versionsResponse.data.find((item) => item.id === versionId)
          || versionsResponse.data.find((item) => item.mainVersion)
        setVersionName(currentVersion?.name || 'main')
        if (!diagramId && currentVersion) {
          redirecting = true
          const name = 'Nuevo diagrama'
          const model = { id: crypto.randomUUID(), version: '4.2.0', title: name, type: 'ClassDiagram', nodes: [], edges: [], assessments: [] }
          const created = await createVersionDiagram(projectId, currentVersion.id, { name, type: 'ClassDiagram', model })
          if (active) navigate(`/projects/${projectId}/versions/${currentVersion.id}/diagrams/${created.data.id}`, { replace: true })
          return
        }
        savingDisabledRef.current = projectResponse.data.role === 'VIEWER'
        if (diagramResponse) {
          setInitialModel(diagramResponse.data.model)
          setDiagramName(diagramResponse.data.name)
          setRenameName(diagramResponse.data.name)
          diagramNameRef.current = diagramResponse.data.name
          getSequenceDiagrams(diagramId).then((response) => active && setSequences(response.data)).catch(() => {})
        }
        updateRuntimeContext({ projectId, projectName: projectResponse.data.name, versionId: currentVersion?.id || null, versionName: currentVersion?.name || null, diagramId: diagramResponse?.data.id || null, diagramName: diagramResponse?.data.name || null, role: projectResponse.data.role })
      })
      .catch((error) => {
        redirecting = false
        console.error('No se pudo cargar el diagrama:', error)
        if (active) setLoadError(true)
      })
      .finally(() => active && !redirecting && setIsLoading(false))
    return () => { active = false }
  }, [diagramId, navigate, projectId, updateRuntimeContext, versionId])

  async function saveLatestModel() {
    if (!isMountedRef.current || savingDisabledRef.current) return
    if (isSavingRef.current) {
      hasPendingSaveRef.current = true
      return
    }
    const model = latestModelRef.current
    if (!model) return
    isSavingRef.current = true
    setSaveStatus('Guardando…')
    hasPendingSaveRef.current = false
    try {
      lastSaveFailedRef.current = false
      const name = diagramNameRef.current
      const data = { name, type: 'ClassDiagram', model: { ...model, title: name } }
      if (diagramIdRef.current) {
        await updateDiagram(diagramIdRef.current, data)
      } else {
        const response = versionId
          ? await createVersionDiagram(projectId, versionId, data)
          : await createProjectDiagram(projectId, data)
        diagramIdRef.current = response.data.id
        if (isMountedRef.current) {
          setIsLoading(true)
          navigate(versionId
            ? `/projects/${projectId}/versions/${versionId}/diagrams/${response.data.id}`
            : `/projects/${projectId}/diagrams/${response.data.id}`, { replace: true })
        }
      }
      setSaveStatus('Guardado')
    } catch (error) {
      lastSaveFailedRef.current = true
      setSaveStatus('Error al guardar')
      if (error.response?.status === 403) disableSaving()
      console.error('No se pudo guardar el diagrama:', error)
    } finally {
      isSavingRef.current = false
      if (isMountedRef.current && !savingDisabledRef.current &&
          (hasPendingSaveRef.current || latestModelRef.current !== model)) {
        hasPendingSaveRef.current = false
        saveLatestModel()
      }
    }
  }

  function scheduleSave(model) {
    if (savingDisabledRef.current) return
    latestModelRef.current = model
    clearTimeout(timeoutRef.current)
    timeoutRef.current = setTimeout(() => {
      timeoutRef.current = undefined
      saveLatestModel()
    }, SAVE_DELAY_MS)
  }

  function handleModelChange(model) {
    modelRevisionRef.current += 1
    scheduleSave(model)
    setAiResult((current) => current ? {
      ...current,
      suggestions: revalidateSuggestions(current.suggestions, model),
    } : current)
  }

  function revalidateSuggestions(suggestions, model = editorRef.current?.model) {
    if (!model) return suggestions
    const suggestedClassNames = new Set(suggestions
      .filter((item) => item.type === 'ADD_CLASS' && item.clientStatus !== 'applied')
      .map((item) => item.className?.trim().toLocaleLowerCase()))
    return suggestions.map((suggestion) => {
      if (suggestion.clientStatus === 'applied') return suggestion
      const validation = validateAiSuggestion({
        model,
        suggestion,
        createdClassIds: createdClassIdsRef.current,
        suggestedClassNames,
      })
      return {
        ...suggestion,
        clientStatus: validation.status === 'pending' ? 'ready' : validation.status,
        applicationMessage: validation.message || undefined,
      }
    })
  }

  async function flushPendingSave() {
    if (roleRef.current === 'VIEWER') return
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current)
      timeoutRef.current = undefined
      await saveLatestModel()
    }
    while (isSavingRef.current || hasPendingSaveRef.current) {
      await new Promise((resolve) => window.setTimeout(resolve, 50))
    }
    if (lastSaveFailedRef.current) throw new Error('No se pudo guardar el modelo antes de analizarlo.')
  }

  async function loadExportPreview(format) {
    setExportFormat(format)
    setExportPreview(undefined)
    setExportError('')
    if (format === 'json' || format === 'xmi') return
    setIsExporting(true)
    try {
      await flushPendingSave()
      const response = format === 'plantuml'
        ? await previewPlantUml(diagramId)
        : await previewSql(diagramId)
      setExportPreview(response.data)
    } catch (error) {
      setExportError(error.response?.data?.message ?? error.message ?? 'No se pudo generar la exportacion.')
    } finally {
      setIsExporting(false)
    }
  }

  function responseFilename(response, fallback) {
    const disposition = response.headers['content-disposition'] ?? ''
    const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
    const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
    try { return decodeURIComponent(encoded ?? plain ?? fallback) } catch { return fallback }
  }

  async function downloadExport() {
    setIsExporting(true)
    setExportError('')
    try {
      await flushPendingSave()
      const response = exportFormat === 'json' ? await exportJson(diagramId)
        : exportFormat === 'plantuml' ? await downloadPlantUml(diagramId)
          : exportFormat === 'xmi' ? await downloadXmi(diagramId) : await downloadSql(diagramId)
      const fallback = `diagrama.${exportFormat === 'plantuml' ? 'puml' : exportFormat}`
      const url = URL.createObjectURL(response.data)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = responseFilename(response, fallback)
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      setExportError(error.response?.data?.message ?? error.message ?? 'No se pudo descargar la exportacion.')
    } finally {
      setIsExporting(false)
    }
  }

  async function validateCodegen() {
    setIsCodegenBusy(true)
    setCodegenError('')
    try {
      await flushPendingSave()
      const response = await validateBackendGeneration(diagramId)
      setCodegenValidation(response.data)
    } catch (error) {
      setCodegenError(error.response?.data?.message ?? error.message ?? 'No se pudo validar la generación.')
    } finally {
      setIsCodegenBusy(false)
    }
  }

  async function downloadBackend() {
    setIsCodegenBusy(true)
    setCodegenError('')
    try {
      await flushPendingSave()
      const response = await downloadGeneratedBackend(diagramId)
      const url = URL.createObjectURL(response.data)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = responseFilename(response, 'generated-project.zip')
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      setCodegenError(error.response?.data?.message ?? error.message ?? 'No se pudo generar el backend.')
    } finally {
      setIsCodegenBusy(false)
    }
  }

  async function handleXmiImport(event) {
    event.preventDefault()
    if (!importFile || isImporting) return
    if (importMode === 'REPLACE' && !window.confirm('El modelo actual sera reemplazado. Esta accion no combina los diagramas.')) return
    setIsImporting(true)
    setImportError('')
    try {
      if (importMode === 'REPLACE') await flushPendingSave()
      const response = await importXmi(projectId, importFile, importMode, diagramId, versionId)
      if (response.data.warnings?.length) window.alert(response.data.warnings.join('\n'))
      if (importMode === 'NEW') navigate(versionId
        ? `/projects/${projectId}/versions/${versionId}/diagrams/${response.data.diagramId}`
        : `/projects/${projectId}/diagrams/${response.data.diagramId}`)
      else window.location.reload()
    } catch (error) {
      setImportError(error.response?.data?.message ?? 'No se pudo importar el archivo XMI.')
    } finally {
      setIsImporting(false)
    }
  }

  function disableSaving() {
    savingDisabledRef.current = true
    hasPendingSaveRef.current = false
    latestModelRef.current = undefined
    clearTimeout(timeoutRef.current)
  }

  function handleAuthenticationError() {
    localStorage.removeItem('collab-case-token')
    window.dispatchEvent(new Event('auth:unauthorized'))
    navigate('/login', { replace: true })
  }

  async function runAnalysis(automatic = false) {
    if (!diagramId || analyzingRef.current) return
    analyzingRef.current = true
    setIsAnalyzing(true)
    setIsAutoAnalyzing(automatic)
    setAiError('')
    try {
      await flushPendingSave()
      const response = await analyzeDiagram(diagramId, aiMessage.trim())
      const received = {
        ...response.data,
        suggestions: response.data.suggestions.map((suggestion) => ({
          ...suggestion,
          clientId: crypto.randomUUID(),
          clientStatus: 'ready',
          selected: false,
        })),
      }
      createdClassIdsRef.current = new Map()
      received.suggestions = revalidateSuggestions(received.suggestions)
      setAiResult(received)
    } catch (error) {
      setAiError(error.response?.data?.message ?? 'No se pudo analizar el diagrama.')
    } finally {
      analyzingRef.current = false
      setIsAnalyzing(false)
      setIsAutoAnalyzing(false)
    }
  }

  function handleAnalyze(event) {
    event.preventDefault()
    runAnalysis(false)
  }

  function scheduleAutomaticAnalysis() {
    window.clearTimeout(reanalysisTimerRef.current)
    reanalysisTimerRef.current = window.setTimeout(() => {
      if (analyzingRef.current) scheduleAutomaticAnalysis()
      else runAnalysis(true)
    }, AUTO_REANALYZE_DELAY_MS)
  }

  function settleAiLock(elementId, lockResult) {
    const pending = pendingAiLockRef.current
    if (!pending || pending.elementId !== elementId) return
    window.clearTimeout(pending.timer)
    pendingAiLockRef.current = undefined
    pending.resolve(lockResult)
  }

  function acquireElementLock(elementId) {
    if (roleRef.current === 'VIEWER') {
      return Promise.resolve({ status: 'invalid', message: 'Tu rol no permite modificar el diagrama.' })
    }
    if (ownLockRef.current === elementId) return Promise.resolve({ status: 'acquired' })
    const remote = remoteLocksRef.current.get(elementId)
    if (remote) {
      return Promise.resolve({ status: 'locked', message: `Este elemento está siendo editado por ${remote.userName}` })
    }
    if (!socketRef.current) {
      return Promise.resolve({ status: 'locked', message: 'No hay conexión para solicitar el bloqueo.' })
    }
    if (ownLockRef.current) socketRef.current.releaseLock(ownLockRef.current)
    ownLockRef.current = undefined
    return new Promise((resolve) => {
      const timer = window.setTimeout(() => {
        if (pendingAiLockRef.current?.elementId === elementId) pendingAiLockRef.current = undefined
        resolve({ status: 'locked', message: 'No se pudo adquirir el bloqueo del elemento.' })
      }, 3000)
      pendingAiLockRef.current = { elementId, resolve, timer }
      socketRef.current.requestLock(elementId)
    })
  }

  function releaseProgrammaticLock() {
    if (ownLockRef.current && ownLockRef.current !== selectedElementRef.current) {
      socketRef.current?.releaseLock(ownLockRef.current)
      ownLockRef.current = undefined
    }
  }

  async function applySuggestion(suggestion) {
    const applicationResult = await applyAiSuggestion({
      editor: editorRef.current,
      suggestion,
      canEdit: roleRef.current === 'OWNER' || roleRef.current === 'EDITOR',
      remoteLocks: remoteLocksRef.current,
      acquireLock: acquireElementLock,
      createdClassIds: createdClassIdsRef.current,
    })
    releaseProgrammaticLock()
    if (applicationResult.status === 'applied' && applicationResult.className) {
      createdClassIdsRef.current.set(
        applicationResult.className.trim().toLocaleLowerCase(),
        applicationResult.elementId,
      )
    }
    setAiResult((current) => ({
      ...current,
      suggestions: revalidateSuggestions(current.suggestions).map((item) => item.clientId === suggestion.clientId
        ? { ...item, selected: false, clientStatus: applicationResult.status,
            applicationMessage: applicationResult.message }
        : item),
    }))
    return applicationResult
  }

  async function confirmApplySuggestion() {
    const suggestion = aiResult?.suggestions.find((item) => item.clientId === pendingSuggestionId)
    setPendingSuggestionId(undefined)
    if (!suggestion || suggestion.clientStatus !== 'ready') return
    const applicationResult = await applySuggestion(suggestion)
    if (applicationResult.status === 'applied') scheduleAutomaticAnalysis()
  }

  function toggleSuggestion(clientId) {
    setAiResult((current) => ({
      ...current,
      suggestions: current.suggestions.map((suggestion) => suggestion.clientId === clientId
        ? { ...suggestion, selected: !suggestion.selected }
        : suggestion),
    }))
  }

  async function applySuggestionBatch(suggestions) {
    const selected = suggestions
      .sort((left, right) => (SUGGESTION_ORDER[left.type] ?? 99) - (SUGGESTION_ORDER[right.type] ?? 99)) ?? []
    if (!selected.length) return
    const summary = selected.map((suggestion) => `• ${suggestion.title}`).join('\n')
    if (!window.confirm(`¿Aplicar estas sugerencias al diagrama?\n\n${summary}`)) return

    let applied = false
    for (const original of selected) {
      const current = validateAiSuggestion({
        model: editorRef.current.model,
        suggestion: original,
        createdClassIds: createdClassIdsRef.current,
      })
      if (current.status !== 'pending') {
        setAiResult((resultState) => ({
          ...resultState,
          suggestions: resultState.suggestions.map((item) => item.clientId === original.clientId
            ? { ...item, selected: false, clientStatus: current.status,
                applicationMessage: current.message }
            : item),
        }))
        continue
      }
      const applicationResult = await applySuggestion({ ...original, ...current.suggestion })
      applied ||= applicationResult.status === 'applied'
    }
    if (applied) scheduleAutomaticAnalysis()
  }

  function applySelectedSuggestions() {
    const selected = aiResult?.suggestions.filter((suggestion) =>
      suggestion.selected && suggestion.clientStatus === 'ready' && APPLICABLE_SUGGESTION_TYPES.has(suggestion.type),
    ) ?? []
    return applySuggestionBatch(selected)
  }

  function applyAllSafeSuggestions() {
    const safe = aiResult?.suggestions.filter((suggestion) =>
      suggestion.clientStatus === 'ready' && APPLICABLE_SUGGESTION_TYPES.has(suggestion.type),
    ) ?? []
    return applySuggestionBatch(safe)
  }

  function discardSuggestion(index) {
    setPendingSuggestionId(undefined)
    setAiResult((current) => ({
      ...current,
      suggestions: current.suggestions.filter((_, suggestionIndex) => suggestionIndex !== index),
    }))
  }

  async function handleDiagramRename(event) {
    event.preventDefault()
    const name = renameName.trim()
    if (!name || name.length > 120) { setRenameError('El nombre debe tener entre 1 y 120 caracteres.'); return }
    try {
      await flushPendingSave()
      const response = await renameDiagram(diagramId, name)
      diagramNameRef.current = response.data.name
      setDiagramName(response.data.name); setRenameName(response.data.name); setRenaming(false); setRenameError('')
      latestModelRef.current = response.data.model
      setNotice('Diagrama renombrado correctamente.')
    } catch (error) { setRenameError(apiErrorMessage(error, 'No se pudo renombrar el diagrama.')) }
  }

  async function handleGenerateSequence(event) {
    event.preventDefault(); setSequenceBusy(true); setSequenceError('')
    try {
      await flushPendingSave()
      const response = await generateSequenceDiagram(diagramId, { name: sequenceName.trim(), scenario: sequenceScenario.trim(), entryClass: entryClass.trim() || null, entryMethod: entryMethod.trim() || null })
      setSequences((items) => [response.data, ...items.filter((item) => item.id !== response.data.id)])
      setSelectedSequence(response.data)
    } catch (error) { setSequenceError(apiErrorMessage(error, 'No se pudo generar la secuencia.')) }
    finally { setSequenceBusy(false) }
  }

  async function handleRegenerateSequence() {
    if (!selectedSequence) return
    setSequenceBusy(true); setSequenceError('')
    try { const response = await regenerateSequenceDiagram(selectedSequence.id); setSelectedSequence(response.data); setSequences((items) => items.map((item) => item.id === response.data.id ? response.data : item)) }
    catch (error) { setSequenceError(apiErrorMessage(error, 'No se pudo regenerar la secuencia.')) }
    finally { setSequenceBusy(false) }
  }

  async function handleDeleteSequence(item) {
    if (!window.confirm(`¿Eliminar "${item.name}"?`)) return
    try { await deleteSequenceDiagram(item.id); setSequences((items) => items.filter((value) => value.id !== item.id)); if (selectedSequence?.id === item.id) setSelectedSequence(undefined) }
    catch (error) { setSequenceError(apiErrorMessage(error, 'No se pudo eliminar la secuencia.')) }
  }

  async function handleDeleteDiagram() {
    try {
      await deleteDiagram(diagramId)
      navigate(versionId ? `/projects/${projectId}/versions/${versionId}` : `/projects/${projectId}`)
    } catch (error) {
      setRenameError(apiErrorMessage(error, 'No se pudo eliminar el diagrama.'))
    }
  }

  useEffect(() => {
    const uml = async (event) => {
      const action = event.detail
      const args = action.arguments || {}
      const type = action.name === 'SET_MULTIPLICITY' ? 'CHANGE_CARDINALITY' : action.name
      const suggestion = {
        ...args, type,
        className: args.className || args.name,
        targetClass: args.targetClass || args.className,
        attributeName: args.attributeName || args.name,
        dataType: args.dataType || args.typeName || 'String',
        methodName: args.methodName || args.name,
        returnType: args.returnType || 'void',
        targetClassName: args.targetClassName || args.targetClass,
        relationshipType: args.relationshipType || 'ClassBidirectional',
        targetRelationshipId: args.targetRelationshipId || args.relationshipId,
      }
      if (!editorRef.current) { setNotice('Abre el editor antes de aplicar la acción.'); return }
      const validation = validateAiSuggestion({ model: editorRef.current.model, suggestion, createdClassIds: createdClassIdsRef.current })
      if (validation.status !== 'pending') { setNotice(validation.message); return }
      const result = await applySuggestion(validation.suggestion)
      setNotice(result.message)
    }
    const openAi = () => document.querySelector('.ai-panel textarea')?.focus()
    const sequence = () => setSequenceOpen(true)
    const exportAction = (event) => { const format = event.detail.replace('EXPORT_', '').toLowerCase(); setExportOpen(true); loadExportPreview(format) }
    const codegen = () => { setCodegenOpen(true); setCodegenValidation(undefined); setCodegenError('') }
    const changed = () => diagramId && getDiagram(diagramId).then((response) => { setDiagramName(response.data.name); diagramNameRef.current = response.data.name })
    window.addEventListener('agent:uml-action', uml); window.addEventListener('agent:open-ai', openAi)
    window.addEventListener('agent:sequence', sequence); window.addEventListener('agent:export', exportAction)
    window.addEventListener('agent:codegen', codegen); window.addEventListener('agent:data-changed', changed)
    return () => { window.removeEventListener('agent:uml-action', uml); window.removeEventListener('agent:open-ai', openAi); window.removeEventListener('agent:sequence', sequence); window.removeEventListener('agent:export', exportAction); window.removeEventListener('agent:codegen', codegen); window.removeEventListener('agent:data-changed', changed) }
  })

  function downloadText(content, filename, type = 'text/plain') {
    const url = URL.createObjectURL(new Blob([content], { type }))
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click(); URL.revokeObjectURL(url)
  }

  if (isLoading) return <p>Cargando diagrama...</p>
  if (loadError) return <p>No se pudo cargar el diagrama.</p>

  return (
    <div className="diagram-editor-layout">
      <div className="diagram-canvas">
        <header className="diagram-file-actions editor-topbar">
          <button type="button" className="icon-button" title="Volver al proyecto" aria-label="Volver al proyecto" onClick={() => navigate(versionId ? `/projects/${projectId}/versions/${versionId}` : `/projects/${projectId}`)}><ArrowLeft size={18} /></button>
          <div className="editor-title-block"><span title={`${projectName} / ${versionName}`}>{projectName} / {versionName} /</span>
          {renaming ? (
            <form className="inline-rename" onSubmit={handleDiagramRename} onKeyDown={(event) => { if (event.key === 'Escape') { setRenaming(false); setRenameName(diagramName) } }}>
              <input value={renameName} maxLength={120} autoFocus onChange={(event) => setRenameName(event.target.value)} required />
              <button type="submit" className="icon-button primary-icon" title="Guardar" aria-label="Guardar nombre"><Check size={16} /></button><button type="button" className="icon-button" title="Cancelar" aria-label="Cancelar edición" onClick={() => { setRenaming(false); setRenameName(diagramName); setRenameError('') }}><X size={16} /></button>
            </form>
          ) : <strong>{diagramName}</strong>}{!isViewer && diagramId && !renaming && <button type="button" className="icon-button" title="Renombrar diagrama" aria-label="Renombrar diagrama" onClick={() => setRenaming(true)}><Pencil size={15} /></button>}<small className={`save-status ${saveStatus === 'Error al guardar' ? 'is-error' : ''}`}>{saveStatus}</small></div>
          <div className="editor-action-group"><button type="button" className="icon-button" title="Agente de ayuda" aria-label="Abrir el agente de ayuda" onClick={() => window.dispatchEvent(new Event('agent:open'))}><Bot size={16}/></button><button type="button" disabled={!diagramId}
            title={diagramId ? 'Exportar diagrama' : 'Guarda el diagrama antes de exportarlo'}
            onClick={() => setExportOpen(true)}><Download size={16} /> Exportar</button>
          <button type="button" disabled={!diagramId} className="primary-action" onClick={() => { setCodegenOpen(true); setCodegenValidation(undefined); setCodegenError('') }}><FileCode2 size={16} /> Generar código
          </button>
          {!isViewer && <button type="button" onClick={() => setImportOpen(true)}><Upload size={16} /> Importar</button>}
          {diagramId && <button type="button" onClick={() => { setSequenceOpen(true); setSequenceError('') }}><Network size={16} /> Secuencia</button>}
          {!isViewer && diagramId && <button type="button" className="icon-button danger-ghost" title="Eliminar diagrama" aria-label="Eliminar diagrama" onClick={() => setDeleteOpen(true)}><Trash2 size={16}/></button>}</div>
        </header>
        {renameError && <div className="editor-inline-error">{renameError}</div>}
        {lockStatus && (
          <div className="lock-chip" role="status"><Lock size={14} aria-hidden="true" />{lockStatus}</div>
        )}
        <Apollon
        key={diagramId ?? 'new'}
        defaultModel={initialModel}
        defaultType={UMLDiagramType.ClassDiagram}
        readonly={isViewer || selectionReadOnly}
        collaborationEnabled={collaborationEnabled}
        collaboration={collaborationEnabled ? {
          enabled: true,
          user: { id: user.id, name: user.name, color: userColor(user.id) },
          showPresence: true,
          showCursors: true,
          showSelectionHighlights: true,
          showFollow: false,
        } : undefined}
        onMount={(editor) => {
          isMountedRef.current = true
          editorRef.current = editor
          const modelSubscription = editor.subscribeToModelChange(handleModelChange)
          let selectionSubscription
          let socket

          const applyHighlights = () => {
            editor.setElementHighlights(Object.fromEntries(
              [...remoteLocksRef.current.keys()].map((elementId) => [elementId, LOCK_COLOR]),
            ))
          }

          const releaseOwnLock = () => {
            const elementId = ownLockRef.current
            if (!elementId) return
            ownLockRef.current = undefined
            socket?.releaseLock(elementId)
          }

          const requestSelectedLock = () => {
            const elementId = selectedElementRef.current
            if (!elementId || roleRef.current === 'VIEWER') return
            const remote = remoteLocksRef.current.get(elementId)
            if (remote && remote.userId !== user.id) {
              setSelectionReadOnly(true)
              setLockStatus(`Editando: ${remote.userName}`)
              return
            }
            setLockStatus('Solicitando bloqueo...')
            socket?.requestLock(elementId)
          }

          if (collaborationEnabled) {
            socket = createCollaborationSocket({
              diagramId,
              token: localStorage.getItem('collab-case-token'),
              onYjsMessage: (data) => editor.receiveBroadcastedMessage(data),
              onAuthenticated: (authenticatedRole) => {
                roleRef.current = authenticatedRole
                setRole((currentRole) => currentRole === authenticatedRole ? currentRole : authenticatedRole)
                savingDisabledRef.current = authenticatedRole === 'VIEWER'
                setSelectionReadOnly(false)
                editor.sendBroadcastMessage((data) => socket.sendYjs(data))
                socket.sendYjs(ApollonEditor.generateInitialSyncMessage())
                socket.sendYjs(ApollonEditor.generateInitialAwarenessSyncMessage())
                if (authenticatedRole !== 'VIEWER') editor.broadcastFullState()
                requestSelectedLock()
              },
              onRoleChanged: (newRole) => {
                roleRef.current = newRole
                setRole((currentRole) => currentRole === newRole ? currentRole : newRole)
                if (newRole === 'VIEWER') {
                  disableSaving()
                  releaseOwnLock()
                  setSelectionReadOnly(false)
                  setLockStatus('Modo lector')
                } else {
                  savingDisabledRef.current = false
                  setSelectionReadOnly(false)
                  setLockStatus('')
                  requestSelectedLock()
                }
              },
              onDiagramRenamed: (name) => {
                diagramNameRef.current = name
                setDiagramName(name)
                setRenameName(name)
                setInitialModel((current) => current ? { ...current, title: name } : current)
                if (latestModelRef.current) latestModelRef.current = { ...latestModelRef.current, title: name }
              },
              onAccessRevoked: () => {
                disableSaving()
                releaseOwnLock()
                navigate('/', { replace: true })
              },
              onLocksSnapshot: (locks) => {
                remoteLocksRef.current = new Map(locks
                  .filter((lock) => lock.userId !== user.id)
                  .map((lock) => [lock.elementId, lock]))
                applyHighlights()
              },
              onLockGranted: (lock) => {
                ownLockRef.current = lock.elementId
                remoteLocksRef.current.delete(lock.elementId)
                applyHighlights()
                setSelectionReadOnly(false)
                setLockStatus('')
                settleAiLock(lock.elementId, { status: 'acquired' })
              },
              onLockDenied: (lock) => {
                ownLockRef.current = undefined
                remoteLocksRef.current.set(lock.elementId, lock)
                applyHighlights()
                setSelectionReadOnly(true)
                setLockStatus(`Editando: ${lock.userName}`)
                settleAiLock(lock.elementId, {
                  status: 'locked',
                  message: `Este elemento está siendo editado por ${lock.userName}`,
                })
              },
              onLockChanged: (lock) => {
                if (lock.locked && lock.userId !== user.id) remoteLocksRef.current.set(lock.elementId, lock)
                else remoteLocksRef.current.delete(lock.elementId)
                applyHighlights()
                if (selectedElementRef.current === lock.elementId) {
                  if (lock.locked && lock.userId !== user.id) {
                    setSelectionReadOnly(true)
                    setLockStatus(`Editando: ${lock.userName}`)
                  } else if (!lock.locked && roleRef.current !== 'VIEWER') {
                    requestSelectedLock()
                  }
                }
              },
              onAuthenticationError: handleAuthenticationError,
            })
            socketRef.current = socket
            socket.connect()

            selectionSubscription = editor.subscribeToSelectionChange((selectedIds) => {
              const next = selectedIds.length === 1 ? selectedIds[0] : undefined
              if (ownLockRef.current && ownLockRef.current !== next) releaseOwnLock()
              selectedElementRef.current = next
              setLockStatus('')
              if (!next) {
                setSelectionReadOnly(false)
              } else {
                requestSelectedLock()
              }
            })
          }

          const renewTimer = window.setInterval(() => {
            if (ownLockRef.current) socket?.renewLock(ownLockRef.current)
          }, LOCK_RENEW_MS)

          return () => {
            isMountedRef.current = false
            editorRef.current = undefined
            socketRef.current = undefined
            if (pendingAiLockRef.current) {
              window.clearTimeout(pendingAiLockRef.current.timer)
              pendingAiLockRef.current.resolve({ status: 'locked', message: 'El editor se cerró.' })
              pendingAiLockRef.current = undefined
            }
            clearTimeout(timeoutRef.current)
            window.clearTimeout(reanalysisTimerRef.current)
            window.clearInterval(renewTimer)
            releaseOwnLock()
            socket?.close()
            editor.setElementHighlights(null)
            editor.sendBroadcastMessage(() => {})
            editor.unsubscribe(modelSubscription)
            if (selectionSubscription !== undefined) editor.unsubscribe(selectionSubscription)
          }
        }}
          style={{ width: '100%', height: '100%' }}
        />
      </div>

      <aside className="ai-panel" aria-label="Asistente de modelado">
        <header className="ai-panel-header"><Sparkles size={18} aria-hidden="true" /><div><h2>Asistente de modelado</h2><p>Revisa el diagrama y propone cambios que tú confirmas.</p></div></header>
        <section className="vision-import">
          <h3>Desde una foto o captura</h3>
          <input
            ref={imageInputRef}
            type="file"
            accept="image/png,image/jpeg,image/webp"
            hidden
            onChange={(event) => selectImage(event.target.files?.[0])}
          />
          {!imageFile && (
            <button type="button" className="dropzone-button" onClick={() => imageInputRef.current?.click()}>
              <ImagePlus size={18} aria-hidden="true" /><span>Elegir imagen<small>PNG, JPG o WebP, hasta 10 MB</small></span>
            </button>
          )}
          {imageFile && (
            <div className="vision-preview">
              <img src={imagePreview} alt="Vista previa del diagrama a importar" />
              <strong>{imageFile.name}</strong>
              <small>{(imageFile.size / 1024).toFixed(1)} KB</small>
              <input
                value={imageInstruction}
                onChange={(event) => setImageInstruction(event.target.value)}
                placeholder="Instrucción opcional, por ejemplo: ignora las notas"
                maxLength={500}
              />
              <div className="ai-suggestion-actions">
                <button type="button" disabled={isExtractingImage} onClick={analyzeImage}>
                  {isExtractingImage ? 'Extrayendo modelo...' : 'Analizar imagen'}
                </button>
                <button type="button" className="ai-discard" onClick={cancelImage}>Cancelar</button>
              </div>
            </div>
          )}
          {imageError && <p className="ai-error">{imageError}</p>}
          {imageExtraction && (
            <div className="vision-detected">
              <strong>Elementos detectados</strong>
              <p>Clases: {imageExtraction.classes.map((item) => item.name).join(', ') || 'ninguna'}</p>
              <p>Relaciones: {imageExtraction.relationships.map((item) =>
                `${item.sourceClass} ${item.sourceCardinality ?? ''} → ${item.targetCardinality ?? ''} ${item.targetClass}`
              ).join('; ') || 'ninguna'}</p>
              {imageExtraction.warnings.map((warning) => <p className="ai-warning" key={warning}>{warning}</p>)}
            </div>
          )}
        </section>
        <form onSubmit={handleAnalyze}>
          <label htmlFor="ai-message">Revisión del diagrama</label>
          <div className="ai-voice-input">
            <textarea
              id="ai-message"
              value={aiMessage}
              onChange={(event) => setAiMessage(event.target.value)}
              maxLength={2000}
              rows={4}
              placeholder="Opcional: revisa responsabilidades, relaciones o cardinalidades..."
            />
            <button
              type="button"
              className={`ai-microphone${audioRecorder.isRecording ? ' is-listening' : ''}`}
              disabled={!audioRecorder.isSupported || audioRecorder.isTranscribing}
              aria-label={audioRecorder.isRecording ? 'Detener grabación' : 'Iniciar grabación'}
              aria-pressed={audioRecorder.isRecording}
              title={audioRecorder.isSupported ? 'Grabar instrucción' : 'Grabación de audio no disponible'}
              onClick={() => audioRecorder.isRecording ? audioRecorder.stop() : audioRecorder.start()}
            >
              {audioRecorder.isRecording ? <Square size={16} aria-hidden="true" /> : <Mic size={18} aria-hidden="true" />}
            </button>
          </div>
          {audioRecorder.isRecording && <span className="ai-speech-status">Grabando…</span>}
          {audioRecorder.isTranscribing && <span className="ai-speech-status">Transcribiendo…</span>}
          {!audioRecorder.isSupported && (
            <span className="ai-note">La grabación de audio no está disponible en este navegador.</span>
          )}
          {audioRecorder.error && <span className="ai-error">{audioRecorder.error}</span>}
          <button type="submit" className="primary-button" disabled={!diagramId || isAnalyzing}>
            {isAnalyzing && <LoaderCircle className="spinner" size={16} aria-hidden="true" />}{isAutoAnalyzing ? 'Actualizando análisis...' : isAnalyzing ? 'Analizando...' : 'Analizar diagrama'}
          </button>
        </form>

        {!diagramId && <p className="ai-note">Guarda el diagrama antes de analizarlo.</p>}
        {aiError && <p className="ai-error">{aiError}</p>}
        {aiResult && (
          <div className="ai-result">
            <p>{aiResult.message}</p>
            <small className="ai-provider">Respuesta de {aiResult.provider === 'ollama' ? 'modelo local (Ollama)' : aiResult.provider}</small>
            {!isViewer && aiResult.suggestions.some((suggestion) => suggestion.clientStatus === 'ready'
              && APPLICABLE_SUGGESTION_TYPES.has(suggestion.type)) && (
              <div className="ai-batch-actions">
                <button type="button" onClick={applyAllSafeSuggestions}>Aplicar todas las seguras</button>
                {aiResult.suggestions.some((suggestion) => suggestion.selected
                  && suggestion.clientStatus === 'ready') && (
                  <button type="button" className="ai-apply-selected" onClick={applySelectedSuggestions}>
                    Aplicar seleccionadas
                  </button>
                )}
              </div>
            )}
            {aiResult.suggestions.map((suggestion, index) => (
              <article className="ai-suggestion" key={suggestion.clientId}>
                <div className="ai-suggestion-heading">
                  {!isViewer && APPLICABLE_SUGGESTION_TYPES.has(suggestion.type)
                    && suggestion.clientStatus === 'ready' && (
                    <input
                      type="checkbox"
                      checked={Boolean(suggestion.selected)}
                      onChange={() => toggleSuggestion(suggestion.clientId)}
                      aria-label={`Seleccionar ${suggestion.title}`}
                    />
                  )}
                  <span className="ai-suggestion-type">
                    {SUGGESTION_LABELS[suggestion.type] ?? suggestion.type}
                  </span>
                </div>
                <h3>{suggestion.title}</h3>
                <p>{suggestion.description}</p>
                <p><strong>Motivo:</strong> {suggestion.reason}</p>
                {suggestion.type === 'ADD_ATTRIBUTE' && (
                  <p><strong>Clase:</strong> {suggestion.targetClass} · <strong>Atributo:</strong> {suggestion.attributeName}{suggestion.attributeType ? `: ${suggestion.attributeType}` : ''}</p>
                )}
                {suggestion.type === 'ADD_METHOD' && (
                  <p><strong>Clase:</strong> {suggestion.targetClass} · <strong>Método:</strong> {suggestion.methodName}</p>
                )}
                {['ADD_RELATIONSHIP', 'CHANGE_RELATIONSHIP_TYPE', 'CHANGE_CARDINALITY'].includes(suggestion.type) && (
                  <div className="ai-relationship-summary">
                    <span>{suggestion.sourceClass ?? 'Origen'}</span>
                    <span>{suggestion.sourceCardinality ?? ''} → {suggestion.targetCardinality ?? ''}</span>
                    <span>{suggestion.targetClassName ?? 'Destino'}</span>
                    <small>Tipo: {suggestion.relationshipType ?? suggestion.currentRelationshipType ?? '—'}</small>
                    {suggestion.suggestedRelationshipType && <small>Sugerencia: {suggestion.suggestedRelationshipType}</small>}
                  </div>
                )}
                {suggestion.clientStatus === 'stale' && (
                  <p className="ai-warning">Esta sugerencia ya no puede comprobarse con seguridad contra el modelo actual.</p>
                )}
                {suggestion.applicationMessage && (
                  <p className={suggestion.clientStatus === 'applied' ? 'ai-success' : 'ai-warning'}>
                    {suggestion.applicationMessage}
                  </p>
                )}
                <div className="ai-suggestion-actions">
                  {!APPLICABLE_SUGGESTION_TYPES.has(suggestion.type) ? (
                    <span className="ai-note">Solo recomendación</span>
                  ) : suggestion.clientStatus !== 'ready' ? (
                    <span className={suggestion.clientStatus === 'applied' ? 'ai-success' : 'ai-warning'}>
                      {suggestion.clientStatus === 'applied'
                        ? 'Aplicada'
                        : `No se puede aplicar automáticamente${suggestion.applicationMessage ? `: ${suggestion.applicationMessage}` : '.'}`}
                    </span>
                  ) : (
                    <button
                      type="button"
                      disabled={isViewer}
                      onClick={() => setPendingSuggestionId(suggestion.clientId)}
                    >
                      Aceptar
                    </button>
                  )}
                  <button type="button" className="ai-discard" onClick={() => discardSuggestion(index)}>
                    Descartar
                  </button>
                </div>
                {pendingSuggestionId === suggestion.clientId && (
                  <div className="ai-confirmation">
                    <strong>¿Aplicar esta sugerencia al diagrama?</strong>
                    <p>{suggestion.title}: {suggestion.description}</p>
                    <div className="ai-suggestion-actions">
                      <button type="button" onClick={confirmApplySuggestion}>Aplicar</button>
                      <button type="button" className="ai-discard" onClick={() => setPendingSuggestionId(undefined)}>
                        Cancelar
                      </button>
                    </div>
                  </div>
                )}
              </article>
            ))}
          </div>
        )}
      </aside>
      {sequenceOpen && (
        <div className="export-backdrop" role="presentation" onMouseDown={() => setSequenceOpen(false)}>
          <section className="export-dialog sequence-dialog" role="dialog" aria-modal="true" aria-labelledby="sequence-title" onMouseDown={(event) => event.stopPropagation()}>
            <div className="modal-heading"><div><h2 id="sequence-title">Diagrama de secuencia</h2><p className="muted">Describe un escenario y se genera a partir de las clases de este diagrama.</p></div><button type="button" className="icon-button" aria-label="Cerrar" onClick={() => setSequenceOpen(false)}><X size={18} /></button></div>
            {!isViewer && !selectedSequence && (
              <form className="form" onSubmit={handleGenerateSequence}>
                <label>Nombre<input value={sequenceName} maxLength={120} onChange={(event) => setSequenceName(event.target.value)} required /></label>
                <label>Escenario<textarea value={sequenceScenario} maxLength={4000} onChange={(event) => setSequenceScenario(event.target.value)} required /></label>
                <label>Clase inicial (opcional)<input value={entryClass} maxLength={120} onChange={(event) => setEntryClass(event.target.value)} /></label>
                <label>Método inicial (opcional)<input value={entryMethod} maxLength={120} onChange={(event) => setEntryMethod(event.target.value)} /></label>
                <button type="submit" disabled={sequenceBusy || !sequenceName.trim() || !sequenceScenario.trim()}>{sequenceBusy ? <><LoaderCircle className="spinner" size={17} /> Generando diagrama...</> : <><Sparkles size={17} /> Generar</>}</button>
              </form>
            )}
            {sequenceError && <p className="error">{sequenceError}</p>}
            {selectedSequence ? (
              <div className="sequence-preview">
                <h3>{selectedSequence.name}</h3><p>{selectedSequence.scenario}</p>
                {selectedSequence.warnings?.map((warning) => <p className="ai-warning" key={warning}>{warning}</p>)}
                <img src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(selectedSequence.svg)}`} alt={`Secuencia ${selectedSequence.name}`} />
                <div className="export-actions">
                  {!isViewer && <button type="button" disabled={sequenceBusy} onClick={handleRegenerateSequence}>Regenerar</button>}
                  <button type="button" onClick={() => navigator.clipboard.writeText(selectedSequence.plantUml)}>Copiar PlantUML</button>
                  <button type="button" onClick={() => downloadText(selectedSequence.plantUml, `${selectedSequence.name}.puml`)}>Descargar .puml</button>
                  <button type="button" onClick={() => downloadText(selectedSequence.svg, `${selectedSequence.name}.svg`, 'image/svg+xml')}>Descargar SVG</button>
                  <button type="button" className="secondary-button" onClick={() => setSelectedSequence(undefined)}>Volver</button>
                </div>
              </div>
            ) : (
              <div className="sequence-list">
                {sequences.map((item) => <div className="card-header" key={item.id}><button type="button" className="link-button" onClick={() => setSelectedSequence(item)}>{item.name}</button>{!isViewer && <button type="button" className="secondary-button" onClick={() => handleDeleteSequence(item)}>Eliminar</button>}</div>)}
                {sequences.length === 0 && <p className="muted">Todavía no hay secuencias generadas.</p>}
              </div>
            )}
          </section>
        </div>
      )}
      <Toast message={notice} onClose={() => setNotice('')} />
      {deleteOpen && <div className="export-backdrop" onMouseDown={() => setDeleteOpen(false)}><section className="export-dialog compact-dialog" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}><div className="modal-heading"><h2>¿Eliminar {diagramName}?</h2><button className="icon-button" onClick={() => setDeleteOpen(false)}><X size={18}/></button></div><p>Solo se eliminará en la versión <strong>{versionName}</strong>. Las copias de otras versiones no se modificarán.</p><div className="modal-actions"><button className="secondary-button" onClick={() => setDeleteOpen(false)}>Cancelar</button><button className="danger-button" onClick={handleDeleteDiagram}>Eliminar diagrama</button></div></section></div>}
      {exportOpen && (
        <div className="export-backdrop" role="presentation" onMouseDown={() => setExportOpen(false)}>
          <section className="export-dialog" role="dialog" aria-modal="true" aria-labelledby="export-title"
            onMouseDown={(event) => event.stopPropagation()}>
            <div className="modal-heading"><div><h2 id="export-title">Exportar diagrama</h2><p className="muted">Elige un formato para ver la vista previa y descargarlo.</p></div><button type="button" className="icon-button" aria-label="Cerrar" onClick={() => setExportOpen(false)}><X size={18} /></button></div>
            <div className="export-tabs">
              {[['json', 'JSON'], ['plantuml', 'PlantUML'], ['sql', 'SQL PostgreSQL'], ['xmi', 'Enterprise Architect (XMI)']].map(([format, label]) => (
                <button type="button" key={format} className={exportFormat === format ? 'active' : ''} aria-pressed={exportFormat === format} onClick={() => loadExportPreview(format)}>{label}</button>
              ))}
            </div>
            {isExporting && <p>Generando exportacion...</p>}
            {exportError && <p className="ai-error">{exportError}</p>}
            {['json', 'xmi'].includes(exportFormat) ? (
              <p>{exportFormat === 'json' ? 'Descarga el UMLModel completo almacenado.' : 'Descarga un modelo UML en formato XMI 2.1.'}</p>
            ) : exportPreview && (
              <>
                <pre className="export-preview">{exportPreview.content}</pre>
                {exportPreview.warnings?.length > 0 && (
                  <div className="export-warnings"><strong>Advertencias</strong><ul>
                    {exportPreview.warnings.map((warning) => <li key={warning}>{warning}</li>)}
                  </ul></div>
                )}
              </>
            )}
            <div className="export-actions">
              {!['json', 'xmi'].includes(exportFormat) && exportPreview && (
                <button type="button" onClick={() => navigator.clipboard.writeText(exportPreview.content)}>Copiar</button>
              )}
              <button type="button" className="primary-button" disabled={isExporting} onClick={downloadExport}>
                Descargar {exportFormat === 'plantuml' ? '.puml' : `.${exportFormat}`}
              </button>
              <button type="button" className="ai-discard" onClick={() => setExportOpen(false)}>Cerrar</button>
            </div>
          </section>
        </div>
      )}
      {importOpen && (
        <div className="export-backdrop" role="presentation" onMouseDown={() => setImportOpen(false)}>
          <form className="export-dialog import-dialog" onSubmit={handleXmiImport}
            onMouseDown={(event) => event.stopPropagation()}>
            <div className="modal-heading"><div><h2>Importar desde Enterprise Architect</h2><p className="muted">Sube un archivo XMI exportado desde Enterprise Architect.</p></div><button type="button" className="icon-button" aria-label="Cerrar" onClick={() => setImportOpen(false)}><X size={18} /></button></div>
            <input type="file" accept=".xmi,.xml,application/xml,text/xml"
              onChange={(event) => setImportFile(event.target.files?.[0])} />
            <label><input type="radio" name="importMode" value="NEW" checked={importMode === 'NEW'}
              onChange={() => setImportMode('NEW')} /> Nuevo diagrama</label>
            <label><input type="radio" name="importMode" value="REPLACE" checked={importMode === 'REPLACE'}
              onChange={() => setImportMode('REPLACE')} /> Reemplazar diagrama actual</label>
            {importMode === 'REPLACE' && <p className="ai-warning"><strong>El modelo actual sera reemplazado.</strong></p>}
            {importError && <p className="ai-error">{importError}</p>}
            <div className="export-actions">
              <button type="submit" className="primary-button" disabled={!importFile || isImporting}>{isImporting ? 'Importando...' : 'Importar XMI'}</button>
              <button type="button" className="ai-discard" onClick={() => setImportOpen(false)}>Cancelar</button>
            </div>
          </form>
        </div>
      )}
      {codegenOpen && (
        <div className="export-backdrop" role="presentation" onMouseDown={() => setCodegenOpen(false)}>
          <section className="export-dialog" role="dialog" aria-modal="true" aria-labelledby="codegen-title"
            onMouseDown={(event) => event.stopPropagation()}>
            <div className="modal-heading"><div><h2 id="codegen-title">Generar código</h2><p className="muted">Backend Spring Boot (Java 21) y cliente móvil Flutter a partir de este diagrama.</p></div><button type="button" className="icon-button" aria-label="Cerrar" onClick={() => setCodegenOpen(false)}><X size={18} /></button></div>
            {codegenValidation && <dl className="codegen-stats"><div><dt>Entidades</dt><dd>{codegenValidation.entities}</dd></div><div><dt>Relaciones</dt><dd>{codegenValidation.relationships}</dd></div><div><dt>Estado</dt><dd className={codegenValidation.valid ? 'ok' : 'bad'}>{codegenValidation.valid ? 'Listo para generar' : 'Hay errores'}</dd></div></dl>}
            {codegenValidation?.errors?.length > 0 && <div className="ai-error"><strong>Errores</strong><ul>{codegenValidation.errors.map((item) => <li key={item}>{item}</li>)}</ul></div>}
            {codegenValidation?.warnings?.length > 0 && <div className="export-warnings"><strong>Advertencias</strong><ul>{codegenValidation.warnings.map((item) => <li key={item}>{item}</li>)}</ul></div>}
            {codegenError && <p className="ai-error">{codegenError}</p>}
            {isCodegenBusy && <p>Procesando...</p>}
            <div className="export-actions">
              <button type="button" className="secondary-button" disabled={isCodegenBusy} onClick={validateCodegen}>Validar diagrama</button>
              <button type="button" className="primary-button" disabled={isCodegenBusy || !codegenValidation?.valid} onClick={downloadBackend}><Download size={16} aria-hidden="true" /> Descargar proyecto</button>
              <button type="button" className="ai-discard" onClick={() => setCodegenOpen(false)}>Cerrar</button>
            </div>
          </section>
        </div>
      )}
    </div>
  )
}

export default DiagramEditor
