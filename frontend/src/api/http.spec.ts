import { beforeEach, describe, expect, it } from 'vitest'
import { http } from './http'

describe('http response 401 interceptor', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('does not wipe session or force navigation on /auth/dingtalk 401 so structured reason reaches LoginView', async () => {
    localStorage.setItem('paiban_token', 'existing-token')
    const handlers = (http.interceptors.response as any).handlers
    const rejected = handlers[0].rejected as (err: any) => Promise<never>

    const err = {
      config: { url: '/auth/dingtalk' },
      response: {
        status: 401,
        data: { code: 'NOT_PROVISIONED', message: '未开通' },
      },
    }

    await expect(rejected(err)).rejects.toBe(err)
    expect(localStorage.getItem('paiban_token')).toBe('existing-token')
  })

  it('clears paiban_token when an authenticated business request returns 401', async () => {
    localStorage.setItem('paiban_token', 'expired-token')
    const handlers = (http.interceptors.response as any).handlers
    const rejected = handlers[0].rejected as (err: any) => Promise<never>

    const err = {
      config: { url: '/teachers' },
      response: { status: 401 },
    }

    await expect(rejected(err)).rejects.toBe(err)
    expect(localStorage.getItem('paiban_token')).toBeNull()
  })
})
