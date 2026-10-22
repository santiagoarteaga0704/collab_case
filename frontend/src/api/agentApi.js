import api from './api'
export const chatWithAgent = (message, context, history) => api.post('/agent/chat', { message, context, history })
export const getAgentStatus = () => api.get('/agent/status')
