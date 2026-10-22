import { Bot, LogOut } from 'lucide-react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { initials } from '../utils/format'
import BrandMark from './BrandMark'

export default function GlobalTopbar() {
  const location = useLocation(), navigate = useNavigate(), { user, logout } = useAuth()
  const inEditor = location.pathname.includes('/diagrams/')
  if (inEditor) return null
  return <header className="global-topbar">
    <Link className="brand" to="/"><BrandMark /><strong>Collab CASE</strong></Link>
    <nav aria-label="Principal"><NavLink to="/" end>Proyectos</NavLink></nav>
    <div className="global-account">
      <button type="button" className="ghost-button" onClick={() => window.dispatchEvent(new Event('agent:open'))}><Bot size={16}/> Agente de ayuda</button>
      <span className="account-chip" title={user?.email}><span className="avatar" aria-hidden="true">{initials(user?.name)}</span>{user?.name}</span>
      <button type="button" className="icon-button" title="Cerrar sesión" aria-label="Cerrar sesión" onClick={() => { logout(); navigate('/login', { replace: true }) }}><LogOut size={16}/></button>
    </div>
  </header>
}
