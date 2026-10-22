import { CheckCircle2, CircleAlert, X } from 'lucide-react'

function Toast({ message, tone = 'success', onClose }) {
  if (!message) return null
  const Icon = tone === 'error' ? CircleAlert : CheckCircle2
  return (
    <div className={`toast toast-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <Icon size={18} aria-hidden="true" />
      <span>{message}</span>
      <button type="button" className="icon-button" aria-label="Cerrar mensaje" onClick={onClose}><X size={16} /></button>
    </div>
  )
}

export default Toast
