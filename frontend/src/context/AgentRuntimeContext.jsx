import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

const AgentRuntimeContext = createContext(null)
const UUID_SEGMENT = '[^/]+'

function idsFromPath(pathname) {
  const editor = pathname.match(new RegExp(`^/projects/(${UUID_SEGMENT})/versions/(${UUID_SEGMENT})/diagrams/(${UUID_SEGMENT})`))
  if (editor) return { projectId: editor[1], versionId: editor[2], diagramId: editor[3] === 'new' ? null : editor[3] }
  const version = pathname.match(new RegExp(`^/projects/(${UUID_SEGMENT})/versions/(${UUID_SEGMENT})`))
  if (version) return { projectId: version[1], versionId: version[2], diagramId: null }
  const project = pathname.match(new RegExp(`^/projects/(${UUID_SEGMENT})`))
  return project ? { projectId: project[1], versionId: null, diagramId: null } : { projectId: null, versionId: null, diagramId: null }
}

export function AgentRuntimeProvider({ children }) {
  const location = useLocation()
  const { user } = useAuth()
  const routeIds = useMemo(() => idsFromPath(location.pathname), [location.pathname])
  const [pageContext, setPageContext] = useState({ pathname: location.pathname, data: {} })
  const updateRuntimeContext = useCallback((patch) => setPageContext((current) => ({
    pathname: location.pathname,
    data: current.pathname === location.pathname ? { ...current.data, ...patch } : patch,
  })), [location.pathname])
  const currentPageContext = useMemo(() => pageContext.pathname === location.pathname ? pageContext.data : {}, [location.pathname, pageContext])

  const value = useMemo(() => ({
    route: location.pathname,
    ...routeIds,
    projectName: null,
    versionName: null,
    diagramName: null,
    role: null,
    selectedElementId: null,
    selectedElementType: null,
    userId: user?.id || null,
    ...currentPageContext,
    updateRuntimeContext,
  }), [location.pathname, routeIds, currentPageContext, updateRuntimeContext, user?.id])

  return <AgentRuntimeContext.Provider value={value}>{children}</AgentRuntimeContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAgentRuntimeContext() { return useContext(AgentRuntimeContext) }
