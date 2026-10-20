import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/api/http'

export interface CurrentUser {
  id: number
  username: string
  displayName: string
  enabled: boolean
  teacherId?: number | null
  roles: string[]
  permissions: string[]
}

const TOKEN_KEY = 'paiban_token'
const USER_KEY = 'paiban_user'

function readUser(): CurrentUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as CurrentUser
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<CurrentUser | null>(readUser())

  const isLoggedIn = computed(() => Boolean(token.value))
  const displayName = computed(() => user.value?.displayName ?? '')
  const roles = computed(() => user.value?.roles ?? [])

  function persist() {
    if (token.value) localStorage.setItem(TOKEN_KEY, token.value)
    else localStorage.removeItem(TOKEN_KEY)
    if (user.value) localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    else localStorage.removeItem(USER_KEY)
  }

  async function login(username: string, password: string) {
    const { data } = await http.post('/auth/login', { username, password })
    token.value = data.token
    user.value = data.user
    persist()
  }

  async function refresh() {
    if (!token.value) return
    const { data } = await http.get('/auth/me')
    user.value = data
    persist()
  }

  function logout() {
    token.value = ''
    user.value = null
    persist()
  }

  function hasPermission(code: string) {
    return user.value?.permissions.includes(code) ?? false
  }

  function hasAnyPermission(codes: string[]) {
    return codes.some((code) => hasPermission(code))
  }

  function hasRole(code: string) {
    return user.value?.roles.includes(code) ?? false
  }

  return {
    token,
    user,
    isLoggedIn,
    displayName,
    roles,
    login,
    logout,
    refresh,
    hasPermission,
    hasAnyPermission,
    hasRole,
  }
})
