import { useEffect, useRef, useState } from 'react'
import { Bot, CheckCircle2, LoaderCircle, Send, X } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { chatWithAgent, getAgentStatus } from '../api/agentApi'
import { apiErrorMessage } from '../api/errorMessage'
import { useAgentRuntimeContext } from '../context/AgentRuntimeContext'
import { executeAgentAction } from '../services/AgentActionExecutor'

const QUICK = ['Crear un diagrama', '¿Cómo agrego una clase?', '¿Cómo hago una relación N:M?', '¿Cómo genero código?']

export default function CollabCaseAgent() {
  const [open, setOpen] = useState(false), [message, setMessage] = useState(''), [messages, setMessages] = useState([])
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [status, setStatus] = useState()
  const runtimeContext = useAgentRuntimeContext(), navigate = useNavigate(), historyRef = useRef()

  useEffect(() => { const show = () => setOpen(true); window.addEventListener('agent:open', show); return () => window.removeEventListener('agent:open', show) }, [])
  useEffect(() => { if (open && !status) getAgentStatus().then((response) => setStatus(response.data)).catch(() => setStatus({ available: false })) }, [open, status])
  useEffect(() => { historyRef.current?.scrollTo({ top: historyRef.current.scrollHeight, behavior: 'smooth' }) }, [messages, busy])

  async function execute(action, messageIndex, originalMessage = '') {
    const result = await executeAgentAction(action, runtimeContext, navigate, {}, originalMessage)
    setMessages((items) => items.map((item, index) => index === messageIndex
      ? { ...item, executed: { ...(item.executed || {}), [action.name]: result } } : item))
    setMessages((items) => [...items, { role: 'AGENT', text: result.success ? `✓ ${result.message}` : result.errorCode === 'NEED_ARGUMENT' || result.errorCode === 'NEED_CONTEXT' ? result.message : `No pude completar la acción: ${result.message}`, result }])
    return result
  }

  async function send(text = message) {
    const value = text.trim()
    if (!value || busy) return
    setMessage(''); setError(''); setMessages((items) => [...items, { role: 'USER', text: value }]); setBusy(true)
    const contextPayload = {
      route: runtimeContext.route, projectId: runtimeContext.projectId, projectName: runtimeContext.projectName,
      versionId: runtimeContext.versionId, versionName: runtimeContext.versionName,
      diagramId: runtimeContext.diagramId, diagramName: runtimeContext.diagramName, role: runtimeContext.role,
      selectedElement: runtimeContext.selectedElementId || null,
    }
    console.debug('[AGENT] message=', value); console.debug('[AGENT] runtimeContext=', contextPayload)
    try {
      const history = messages.slice(-6).map((item) => ({ role: item.role, content: item.text }))
      const response = await chatWithAgent(value, contextPayload, history)
      console.debug('[AGENT] response=', response.data)
      const index = messages.length + 1
      setMessages((items) => [...items, { role: 'AGENT', text: response.data.message, actions: response.data.actions, sourceMessage: value }])
      for (const action of response.data.actions || []) if (!action.confirmationRequired) await execute(action, index, value)
    } catch (requestError) { setError(apiErrorMessage(requestError, 'No se pudo contactar con el agente.')) }
    finally { setBusy(false) }
  }

  const contextLabel = runtimeContext.projectName
    ? `${runtimeContext.projectName}${runtimeContext.versionName ? ` / ${runtimeContext.versionName}` : ''}${runtimeContext.diagramName ? ` / ${runtimeContext.diagramName}` : ''}`
    : 'Todos los proyectos'

  return <>
    <button className="agent-fab" type="button" onClick={() => setOpen(true)}><Bot size={19}/> Agente</button>
    {open && <aside className="agent-drawer" aria-label="Agente de ayuda">
      <header><div><span className="eyebrow">Agente Collab CASE</span><h2>¿En qué puedo ayudarte?</h2><span className={`agent-provider ${status?.available ? 'available' : ''}`}>● {status?.available ? (status.provider === 'ollama' ? 'Local' : 'Online') : 'No disponible'}</span></div><button className="icon-button" onClick={() => setOpen(false)} aria-label="Cerrar agente"><X size={18}/></button></header>
      <div className="agent-context"><small>Contexto actual</small><strong>{contextLabel}</strong></div>
      {!messages.length && <div className="agent-welcome"><p>Puedes preguntarme...</p><div className="agent-quick">{QUICK.map((item) => <button key={item} onClick={() => send(item)}>{item}</button>)}</div></div>}
      <div className="agent-history" ref={historyRef}>{messages.map((item, index) => <article className={`agent-message ${item.role.toLowerCase()}`} key={`${index}-${item.text}`}><small>{item.role === 'USER' ? 'Tú' : 'Agente'}</small><p>{item.text}</p>{item.actions?.map((action) => { const result = item.executed?.[action.name]; return <div className="agent-action" key={action.name}><div><strong>{action.name.replaceAll('_', ' ')}</strong>{result?.data?.name && <small>{result.data.name}</small>}</div>{result ? <CheckCircle2 className={result.success ? 'success-icon' : 'error'} size={18}/> : <button disabled={busy} onClick={() => execute(action, index, item.sourceMessage)}>{action.confirmationRequired ? 'Confirmar' : 'Ejecutar'}</button>}</div>})}{item.result?.options?.length > 0 && <div className="agent-options">{item.result.options.map((option) => <button key={option.id} onClick={() => navigate(`/projects/${option.id}`)}>{option.name}</button>)}</div>}</article>)}{busy && <p className="agent-thinking"><LoaderCircle className="spinner" size={16}/> Pensando...</p>}</div>
      {error && <p className="agent-panel-error">{error}</p>}
      <form onSubmit={(event) => { event.preventDefault(); send() }}><textarea value={message} onChange={(event) => setMessage(event.target.value)} placeholder="Escribe tu pregunta..."/><button disabled={busy || !message.trim()} aria-label="Enviar"><Send size={17}/></button></form>
    </aside>}
  </>
}
