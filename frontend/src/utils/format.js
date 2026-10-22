const relative = new Intl.RelativeTimeFormat('es', { numeric: 'auto' })
const UNITS = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]]

export function timeAgo(value) {
  if (!value) return ''
  const seconds = (new Date(value).getTime() - Date.now()) / 1000
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit)
  }
  return 'hace un momento'
}

export function fullDate(value) {
  return value ? new Date(value).toLocaleString('es', { dateStyle: 'long', timeStyle: 'short' }) : ''
}

export function initials(name = '') {
  const words = name.trim().split(/\s+/).filter(Boolean)
  return (words.length > 1 ? words[0][0] + words[1][0] : (words[0] || '?').slice(0, 2)).toUpperCase()
}
