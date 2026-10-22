export function apiErrorMessage(error, fallback) {
  if (!error.response) return error.request ? 'No se pudo conectar con el servidor.' : error.message || fallback
  const detail = error.response.data?.detail || error.response.data?.message
  if (detail) return detail
  return ({
    400: 'La solicitud contiene datos inválidos.',
    401: 'Tu sesión expiró. Inicia sesión nuevamente.',
    403: 'No tienes permisos para realizar esta acción.',
    404: 'No se encontró el recurso solicitado.',
    409: 'La operación entra en conflicto con el estado actual.',
  })[error.response.status] || (error.response.status >= 500 ? 'El servidor no pudo completar la operación.' : fallback)
}
