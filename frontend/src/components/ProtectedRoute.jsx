import { Navigate, Outlet } from 'react-router-dom'

import { useAuth } from '../context/AuthContext'
import { AgentRuntimeProvider } from '../context/AgentRuntimeContext'
import CollabCaseAgent from './CollabCaseAgent'
import GlobalTopbar from './GlobalTopbar'

function ProtectedRoute() {
  const { loading, isAuthenticated } = useAuth()

  if (loading) return <p>Cargando sesión...</p>

  return isAuthenticated ? <AgentRuntimeProvider><GlobalTopbar/><Outlet/><CollabCaseAgent/></AgentRuntimeProvider> : <Navigate to="/login" replace />
}

export default ProtectedRoute
