import axios from 'axios'

/**
 * AI parsing can take several minutes: a large timetable is split into one call per
 * class. Only AI import requests opt into this; every other request keeps 30 seconds.
 */
export const AI_IMPORT_TIMEOUT_MS = 900000

export const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('paiban_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('paiban_token')
      if (location.pathname !== '/login') {
        location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)
