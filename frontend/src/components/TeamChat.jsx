import { useEffect, useReducer, useRef, useState } from 'react'
import { Bot, Lock, MessageSquare, Send, X } from 'lucide-react'
import { getChatHistory, getChatStatus, setHelpLevel } from '../api/chatApi'
import { useAuth } from '../context/AuthContext'
import { aiStatusLabel, chatReducer, initialChatState, isApplying, markApplying } from '../services/chatState'
import { createChatSocket } from '../services/chatSocket'
import './TeamChat.css'

const LEVEL_LABELS = { EXPLAIN: 'Explicar', SUGGEST: 'Sugerir', DRAW: 'Dibujar' }
const LEVELS = Object.keys(LEVEL_LABELS)

function proposalNames(proposal) {
  return (proposal ?? [])
    .map((s) => s.className ?? s.attributeName ?? s.methodName ?? s.description ?? s.title)
    .filter(Boolean)
}

function formatTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' })
}

function TeamChat({ projectId, diagramId, role, isOwner = false, onApplyChanges, variant = 'column' }) {
  const { user } = useAuth()
  const [state, dispatch] = useReducer(chatReducer, initialChatState)
  const [text, setText] = useState('')
  const [isPrivate, setIsPrivate] = useState(false)
  const [open, setOpen] = useState(variant === 'column')
  const [error, setError] = useState('')
  const [connected, setConnected] = useState(false)
  const [applying, setApplying] = useState({})
  const socketRef = useRef()
  const listRef = useRef()
  const applyRef = useRef(onApplyChanges)
  useEffect(() => { applyRef.current = onApplyChanges }, [onApplyChanges])

  useEffect(() => {
    let active = true
    let socket
    async function start() {
      try {
        // The reducer's `history` case merges by id, so live events that arrive first are kept.
        const [history, status] = await Promise.all([getChatHistory(projectId), getChatStatus(projectId)])
        if (!active) return
        dispatch({ kind: 'history', messages: history.data })
        dispatch({ kind: 'ai_status', connected: status.data.aiConnected, queued: status.data.queued })
        dispatch({ kind: 'help_level', level: status.data.helpLevel })
      } catch {
        if (active) setError('No se pudo cargar el chat.')
      }
      if (!active) return
      socket = createChatSocket({
        projectId,
        diagramId,
        token: localStorage.getItem('collab-case-token'),
        onDisconnect: () => { if (active) setConnected(false) },
        onReconnect: async () => {
          // catch up with what was published while the socket was down
          try {
            const history = await getChatHistory(projectId)
            if (active) dispatch({ kind: 'history', messages: history.data })
          } catch {
            if (active) setError('No se pudieron recuperar los mensajes nuevos del chat.')
          }
        },
        onEvent: async (event) => {
          if (event.kind === 'auth_ok') { setConnected(true); setError(''); return }
          if (event.kind === 'auth_error') { setConnected(false); setError('No se pudo conectar al chat.'); return }
          if (event.kind === 'error') {
            setApplying({})
            setError(event.message || 'No se pudo enviar el mensaje.')
            return
          }
          if (event.kind === 'apply_changes') {
            if (!applyRef.current) return
            let result
            try {
              result = await applyRef.current(event.suggestions)
            } catch {
              result = { applied: [], failed: [{ name: '', reason: 'No se pudo aplicar el cambio.' }] }
            }
            socket.reportApplied(event.messageId, result.applied, result.failed)
            return
          }
          dispatch(event)
        },
      })
      socketRef.current = socket
    }
    start()
    return () => { active = false; socket?.close(); socketRef.current = undefined }
  }, [projectId, diagramId])

  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [state.messages, open])

  function submit(event) {
    event?.preventDefault()
    const content = text.trim()
    if (!content) return
    if (socketRef.current?.send(content, isPrivate)) { setText(''); setError('') }
    else setError('Sin conexión con el chat. Inténtalo de nuevo en un momento.')
  }

  async function changeLevel(level) {
    try { await setHelpLevel(projectId, level); dispatch({ kind: 'help_level', level }) }
    catch { setError('No se pudo cambiar el nivel de ayuda.') }
  }

  const canApply = Boolean(onApplyChanges) && (role === 'EDITOR' || role === 'OWNER')
  const aiLabel = !state.aiConnected ? 'IA desconectada'
    : state.queued > 0 ? `IA conectada · ${state.queued} en cola` : 'IA conectada'
  const hidden = variant === 'drawer' && !open
  const inputId = `team-chat-input-${variant}`

  const panel = (
    <section className={`team-chat team-chat-${variant}${open ? ' is-open' : ''}`} aria-label="Chat del equipo"
      aria-hidden={hidden ? true : undefined} inert={hidden ? true : undefined}>
      <header className="team-chat-header">
        <MessageSquare size={17} aria-hidden="true" />
        <h2>Chat del equipo</h2>
        {variant === 'drawer' && (
          <button type="button" className="icon-button" aria-label="Cerrar el chat" onClick={() => setOpen(false)}><X size={16} /></button>
        )}
      </header>
      <div className="team-chat-status">
        <span className={`team-chat-ai ${state.aiConnected ? 'is-on' : 'is-off'}`}><Bot size={14} aria-hidden="true" /> {aiLabel}</span>
        <label className="team-chat-level">Ayuda
          {isOwner
            ? <select value={state.helpLevel} onChange={(e) => changeLevel(e.target.value)}>{LEVELS.map((l) => <option key={l} value={l}>{LEVEL_LABELS[l]}</option>)}</select>
            : <strong>{LEVEL_LABELS[state.helpLevel] ?? state.helpLevel}</strong>}
        </label>
      </div>
      <ol className="team-chat-list" ref={listRef} aria-live="polite">
        {state.messages.length === 0 && <li className="team-chat-empty">Aún no hay mensajes. Escribe @IA para preguntarle a la IA.</li>}
        {state.messages.map((m) => {
          if (m.kind === 'NOTICE') return <li key={m.id} className="team-chat-notice">{m.content}</li>
          const priv = m.isPrivate ?? m.private
          const rawLabel = aiStatusLabel(state, m)
          const label = rawLabel === (m.content ?? '').trim() ? '' : rawLabel
          const names = proposalNames(m.proposal)
          const mine = m.authorId && m.authorId === user?.id
          return (
            <li key={m.id} className={`team-chat-message kind-${(m.kind ?? 'PERSON').toLowerCase()}${mine ? ' is-mine' : ''}${m.status === 'FAILED' ? ' is-failed' : ''}`}>
              <div className="team-chat-meta">
                <strong>{m.kind === 'AI' ? 'IA' : m.authorName}</strong>
                {m.kind === 'AI' && m.requestedByName && <span>para {m.requestedByName}</span>}
                <time dateTime={m.createdAt}>{formatTime(m.createdAt)}</time>
                {priv && <span className="team-chat-private"><Lock size={11} aria-hidden="true" /> privado</span>}
              </div>
              {m.content ? <p>{m.content}</p> : null}
              {m.kind === 'AI' && label && <small className="team-chat-state">{label}</small>}
              {names.length > 0 && (
                <div className="team-chat-proposal">
                  <span>{m.proposalState === 'APPLIED' ? 'La IA agregó:' : 'La IA propone agregar:'}</span>
                  <ul>{names.map((n, i) => <li key={`${n}-${i}`}>{n}</li>)}</ul>
                  {m.proposalState === 'PENDING' && canApply && (
                    <button type="button" className="primary-button" disabled={isApplying(applying, m)}
                      onClick={() => { if (socketRef.current?.applyProposal(m.id)) setApplying((a) => markApplying(a, m)) }}>Aplicar</button>
                  )}
                  {m.proposalState === 'NONE' && <small className="team-chat-state">Aplicando…</small>}
                  {m.proposalState === 'APPLIED' && <small className="team-chat-state">Aplicado</small>}
                </div>
              )}
            </li>
          )
        })}
      </ol>
      {error && <p className="team-chat-error" role="alert">{error}</p>}
      <form className="team-chat-form" onSubmit={submit}>
        <label className="sr-only" htmlFor={inputId}>Mensaje</label>
        <textarea id={inputId} rows={2} value={text} maxLength={2000}
          placeholder="Escribe @IA para preguntarle a la IA"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); submit() }
          }} />
        <div className="team-chat-actions">
          <label className="team-chat-private-toggle"><input type="checkbox" checked={isPrivate} onChange={(e) => setIsPrivate(e.target.checked)} /> Privado (solo tú y la IA)</label>
          <button type="submit" className="primary-button" disabled={!text.trim() || !connected}><Send size={15} aria-hidden="true" /> Enviar</button>
        </div>
      </form>
    </section>
  )

  if (variant !== 'drawer') return panel
  return (
    <>
      {!open && <button type="button" className="team-chat-toggle" onClick={() => setOpen(true)} aria-label="Abrir el chat del equipo"><MessageSquare size={17} aria-hidden="true" /> Chat</button>}
      {panel}
    </>
  )
}

export default TeamChat
