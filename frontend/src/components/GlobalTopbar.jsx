import { Bot, Boxes, LogOut, UserRound } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'

export default function GlobalTopbar() {
  const location = useLocation(), navigate = useNavigate(), { user, logout } = useAuth()
  const inEditor = location.pathname.includes('/diagrams/')
  if (inEditor) return null
  return <header className="global-topbar"><Link className="brand" to="/"><Boxes size={21}/><strong>Collab CASE</strong></Link><nav><Link to="/">Proyectos</Link></nav><div className="global-account"><button onClick={() => window.dispatchEvent(new Event('agent:open'))}><Bot size={16}/> Agente</button><span><UserRound size={16}/>{user?.name}</span><button aria-label="Cerrar sesión" onClick={() => { logout(); navigate('/login', { replace: true }) }}><LogOut size={16}/></button></div></header>
}
