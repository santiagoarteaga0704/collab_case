import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'

import { useAuth } from '../context/AuthContext'

function Register() {
  const navigate = useNavigate()
  const { register, isAuthenticated } = useAuth()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState('')

  if (isAuthenticated) return <Navigate to="/" replace />

  async function handleSubmit(event) {
    event.preventDefault()
    setError('')

    if (password !== confirmation) {
      setError('Las contraseñas no coinciden.')
      return
    }

    try {
      await register({ name: name.trim(), email, password })
      navigate('/', { replace: true })
    } catch (requestError) {
      console.error('No se pudo registrar el usuario:', requestError)
      setError(requestError.response?.status === 409
        ? 'El email ya está registrado.'
        : 'No se pudo crear la cuenta.')
    }
  }

  return (
    <main className="auth-page">
      <form className="form auth-form" onSubmit={handleSubmit}>
        <h1>Crear cuenta</h1>
        <label>
          Nombre
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        <label>
          Email
          <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
        </label>
        <label>
          Contraseña
          <input type="password" minLength="8" value={password} onChange={(event) => setPassword(event.target.value)} required />
        </label>
        <label>
          Confirmar contraseña
          <input type="password" minLength="8" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} required />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit">Registrarse</button>
        <p>¿Ya tienes cuenta? <Link to="/login">Inicia sesión</Link></p>
      </form>
    </main>
  )
}

export default Register
