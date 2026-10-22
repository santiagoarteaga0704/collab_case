import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'

import AuthLayout from '../components/AuthLayout'
import { useAuth } from '../context/AuthContext'

function Login() {
  const navigate = useNavigate()
  const { login, isAuthenticated } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  if (isAuthenticated) return <Navigate to="/" replace />

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')
    setBusy(true)

    try {
      await login({ email, password })
      navigate('/', { replace: true })
    } catch (requestError) {
      console.error('No se pudo iniciar sesión:', requestError)
      setError('Email o contraseña incorrectos.')
      setBusy(false)
    }
  }

  return (
    <AuthLayout title="Iniciar sesión" intro="Entra para seguir con tus proyectos.">
      <form className="auth-form" onSubmit={handleSubmit}>
        <label>
          Email
          <input type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
        </label>
        <label>
          Contraseña
          <input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button type="submit" className="primary-button" disabled={busy}>{busy ? 'Entrando…' : 'Iniciar sesión'}</button>
      </form>
      <p className="auth-switch">¿No tienes cuenta? <Link to="/register">Crea una</Link></p>
    </AuthLayout>
  )
}

export default Login
