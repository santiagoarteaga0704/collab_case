import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'

import { useAuth } from '../context/AuthContext'

function Login() {
  const navigate = useNavigate()
  const { login, isAuthenticated } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')

  if (isAuthenticated) return <Navigate to="/" replace />

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')

    try {
      await login({ email, password })
      navigate('/', { replace: true })
    } catch (requestError) {
      console.error('No se pudo iniciar sesión:', requestError)
      setError('Email o contraseña incorrectos.')
    }
  }

  return (
    <main className="auth-page">
      <form className="form auth-form" onSubmit={handleSubmit}>
        <h1>Iniciar sesión</h1>
        <label>
          Email
          <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
        </label>
        <label>
          Contraseña
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit">Iniciar sesión</button>
        <p>¿No tienes cuenta? <Link to="/register">Regístrate</Link></p>
      </form>
    </main>
  )
}

export default Login
