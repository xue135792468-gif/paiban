import axios from 'axios'

/**
 * AI parsing can take several minutes: a large timetable is split into one call per
 * class. Only AI import requests opt into this; every other request keeps 30 seconds.
 */
export const AI_IMPORT_TIMEOUT_MS = 900000

/**
 * DingTalk walks every department with two round trips each: a real tenant took 54
 * seconds, so the 30-second default aborted the request while the backend kept working
 * and the screen cried "预演失败". Preview and confirm opt into this budget instead.
 */
export const DINGTALK_SYNC_TIMEOUT_MS = 180000

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
    const requestUrl = String(error.config?.url ?? '')
    const isDingTalkAuth = /(^|\/)auth\/dingtalk(\/|$|\?)/.test(requestUrl)
    if (error.response?.status === 401 && !isDingTalkAuth) {
      localStorage.removeItem('paiban_token')
      if (location.pathname !== '/login') {
        location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)
