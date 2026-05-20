import { ref } from 'vue'

const loggedIn = ref(localStorage.getItem('loggedIn') === 'true')
const userEmail = ref(localStorage.getItem('userEmail') || '')

export function useAuth() {
  function setSession(email: string) {
    loggedIn.value = true
    userEmail.value = email
    localStorage.setItem('loggedIn', 'true')
    localStorage.setItem('userEmail', email)
  }

  function clearSession() {
    loggedIn.value = false
    userEmail.value = ''
    localStorage.removeItem('loggedIn')
    localStorage.removeItem('userEmail')
  }

  function isLoggedIn() {
    return loggedIn.value
  }

  return { userEmail, setSession, clearSession, isLoggedIn }
}
