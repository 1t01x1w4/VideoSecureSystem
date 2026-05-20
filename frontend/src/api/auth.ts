import api from './index'

export const authApi = {
  sendCode(email: string, purpose: 'register' | 'login') {
    return api.post('/auth/code', { email, purpose })
  },
  register(email: string, password: string, code: string) {
    return api.post('/auth/register', { email, password, code })
  },
  login(email: string, password: string, code: string) {
    return api.post('/auth/login', { email, password, code })
  },
  logout() {
    return api.post('/auth/logout')
  },
}
