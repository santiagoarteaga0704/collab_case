import { createContext, useContext, useEffect, useState } from 'react'

import {
  getCurrentUser,
  login as loginRequest,
  register as registerRequest,
} from '../api/authApi'

const TOKEN_KEY = 'collab-case-token'
const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(() => Boolean(localStorage.getItem(TOKEN_KEY)))

  useEffect(() => {
    let active = true

    function clearAuthentication() {
      if (active) setUser(null)
    }

    window.addEventListener('auth:unauthorized', clearAuthentication)

    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      getCurrentUser()
        .then((response) => {
          if (active) setUser(response.data)
        })
        .catch(() => {
          localStorage.removeItem(TOKEN_KEY)
          if (active) setUser(null)
        })
        .finally(() => {
          if (active) setLoading(false)
        })
    }

    return () => {
      active = false
      window.removeEventListener('auth:unauthorized', clearAuthentication)
    }
  }, [])

  async function login(credentials) {
    const response = await loginRequest(credentials)
    localStorage.setItem(TOKEN_KEY, response.data.token)
    setUser(response.data.user)
  }

  async function register(data) {
    const response = await registerRequest(data)
    localStorage.setItem(TOKEN_KEY, response.data.token)
    setUser(response.data.user)
  }

  function logout() {
    localStorage.removeItem(TOKEN_KEY)
    setUser(null)
  }

  return (
    <AuthContext.Provider
      value={{ user, loading, isAuthenticated: Boolean(user), login, register, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  return useContext(AuthContext)
}
