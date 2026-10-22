import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'

import AuthLayout from '../components/AuthLayout'
import { useAuth } from '../context/AuthContext'

function Register() {
  const navigate = useNavigate()
  const { register, isAuthenticated } = useAuth()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  if (isAuthenticated) return <Navigate to="/" replace />

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')

    if (password !== confirmation) {
      setError('Las contraseñas no coinciden.')
      return
    }

    setBusy(true)
    try {
      await register({ name: name.trim(), email, password })
      navigate('/', { replace: true })
    } catch (requestError) {
      console.error('No se pudo registrar el usuario:', requestError)
      setError(requestError.response?.status === 409
        ? 'Ese email ya tiene una cuenta. Inicia sesión con él.'
        : 'No se pudo crear la cuenta. Intenta de nuevo.')
      setBusy(false)
    }
  }

  return (
    <AuthLayout title="Crear cuenta" intro="Tu cuenta te permite crear proyectos e invitar a tu equipo.">
      <form className="auth-form" onSubmit={handleSubmit}>
        <label>
          Nombre
          <input autoComplete="name" value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        <label>
          Email
          <input type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
        </label>
        <div className="form-pair">
          <label>
            Contraseña
            <input type="password" autoComplete="new-password" minLength="8" value={password} onChange={(event) => setPassword(event.target.value)} required />
          </label>
          <label>
            Repetir contraseña
            <input type="password" autoComplete="new-password" minLength="8" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} required />
          </label>
        </div>
        <small className="field-hint">Mínimo 8 caracteres.</small>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button type="submit" className="primary-button" disabled={busy}>{busy ? 'Creando cuenta…' : 'Crear cuenta'}</button>
      </form>
      <p className="auth-switch">¿Ya tienes cuenta? <Link to="/login">Inicia sesión</Link></p>
    </AuthLayout>
  )
}

export default Register
