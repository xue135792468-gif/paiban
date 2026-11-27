import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import {
  API,
  SUPER_ADMIN,
  apiCall,
  apiLogin,
  createAccount,
  newContext,
  trackBrowserErrors,
  uiLogin,
} from './helpers'
import { capture } from './screenshots'

const ACCOUNT_PASSWORD = 'teacher123'
const PROBE_PATH = '/integrations/dingtalk/test-connection'

function trackProbeRequests(page: Page): string[] {
  const requests: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes(`/api${PROBE_PATH}`)) {
      requests.push(request.url())
    }
  })
  return requests
}

test('超级管理员用未保存参数测试，看到精确成功结果且配置未落库', async ({ page }) => {
  const api = await newContext()
  const token = await apiLogin(api)
  const browserErrors = trackBrowserErrors(page)

  try {
    // e2e profile 使用确定性 Fake DingTalk；测试仍走真实前端请求、真实控制器和持久化边界。
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', {
      enabled: false,
      appKey: 'saved-e2e-key',
      appSecret: 'saved-e2e-secret',
      agentId: '1000',
      corpId: 'e2e-corp',
      syncStrategy: 'dingtalk',
    })

    await uiLogin(page, SUPER_ADMIN.username, SUPER_ADMIN.password)
    await page.goto('/settings/general')

    const card = page.locator('#dingtalk-integration')
    await expect(card).toBeVisible()
    await expect(card.getByText('未启用')).toBeVisible()

    await card.getByTestId('dingtalk-probe-app-key').fill('unsaved-e2e-key')
    await card.getByTestId('dingtalk-probe-app-secret').fill('e2e-app-secret')
    const [probeResponse] = await Promise.all([
      page.waitForResponse(
        (response) => response.request().method() === 'POST' && response.url().includes(`/api${PROBE_PATH}`),
      ),
      card.getByTestId('dingtalk-probe').click(),
    ])
    expect(probeResponse.status()).toBe(200)
    expect(await probeResponse.json()).toMatchObject({ success: true, code: 'OK' })
    expect(probeResponse.request().postDataJSON()).toEqual({
      appKey: 'unsaved-e2e-key',
      appSecret: 'e2e-app-secret',
      useSavedSecret: false,
    })

    const result = card.getByTestId('dingtalk-probe-result')
    await expect(result).toContainText('访问令牌')
    await expect(result).toContainText('根部门')
    await expect(result).not.toContainText('通知')
    await expect(result).not.toContainText('手机')
    await expect(result).not.toContainText('CorpId')
    await expect(result).not.toContainText('AgentId')
    await result.scrollIntoViewIfNeeded()
    await capture(page, 't07-connection-unsaved')

    // 探测不保存：表单仍显示本次未保存值，服务端也仍保留原 AppKey 和 Secret。
    await expect(card.getByTestId('dingtalk-probe-app-key')).toHaveValue('unsaved-e2e-key')
    await expect(card.getByTestId('dingtalk-probe-app-secret')).toHaveValue('e2e-app-secret')
    const configAfter = await apiCall(api, token, 'get', '/integrations/dingtalk/config')
    expect(configAfter.appKey).toBe('saved-e2e-key')
    expect(configAfter.hasSecret).toBe(true)

    await page.reload()
    const reloadedCard = page.locator('#dingtalk-integration')
    await expect(reloadedCard).toContainText('留空将复用已保存的 AppSecret')
    const [savedSecretResponse] = await Promise.all([
      page.waitForResponse(
        (response) => response.request().method() === 'POST' && response.url().includes(`/api${PROBE_PATH}`),
      ),
      reloadedCard.getByTestId('dingtalk-probe').click(),
    ])
    expect(savedSecretResponse.request().postDataJSON()).toEqual({
      appKey: 'saved-e2e-key',
      appSecret: '',
      useSavedSecret: true,
    })
    expect(savedSecretResponse.status()).toBe(200)
    expect(await savedSecretResponse.json()).toMatchObject({ success: true, code: 'OK' })

    await reloadedCard.getByTestId('dingtalk-probe-app-key').fill('changed-e2e-key')
    await expect(reloadedCard).toContainText('请为新 AppKey 输入对应的 AppSecret')
    await expect(reloadedCard.getByTestId('dingtalk-probe')).toBeDisabled()
    await expect(reloadedCard.getByTestId('dingtalk-probe-result')).toHaveCount(0)

    // Fake-only outcome selection still passes through the real controller and UI.
    await reloadedCard.getByTestId('dingtalk-probe-app-secret').fill('fake-probe:TOKEN_REJECTED')
    const [rejectedProbe] = await Promise.all([
      page.waitForResponse(
        (response) => response.request().method() === 'POST' && response.url().includes(`/api${PROBE_PATH}`),
      ),
      reloadedCard.getByTestId('dingtalk-probe').click(),
    ])
    expect(rejectedProbe.status()).toBe(200)
    expect(await rejectedProbe.json()).toMatchObject({ success: false, code: 'TOKEN_REJECTED' })
    const rejectedResult = reloadedCard.getByTestId('dingtalk-probe-result')
    await expect(rejectedResult).toContainText('获取访问令牌')
    await expect(rejectedResult).not.toContainText('fake-probe:')
    await rejectedResult.scrollIntoViewIfNeeded()
    await capture(page, 't07-connection-rejected')
    expect((await apiCall(api, token, 'get', '/integrations/dingtalk/config')).appKey).toBe('saved-e2e-key')
    expect(browserErrors).toEqual([])
  } finally {
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', {
      enabled: false,
    }).catch(() => undefined)
    await api.dispose()
  }
})

test('教务管理员与老师看不到测试连接，也不会从页面发起探测请求', async ({ page, browser }) => {
  const api = await newContext()
  const suffix = Math.random().toString(36).slice(2, 8)
  const academic = `probe_jw_${suffix}`
  const browserErrors = trackBrowserErrors(page)

  try {
    const token = await apiLogin(api)
    await createAccount(api, {
      username: academic,
      displayName: `探测教务${suffix}`,
      password: ACCOUNT_PASSWORD,
      roles: ['admin'],
      token,
    })

    const academicProbeRequests = trackProbeRequests(page)
    await uiLogin(page, academic, ACCOUNT_PASSWORD)
    await page.goto('/settings/general')
    await expect(page.locator('#dingtalk-integration')).toHaveCount(0)
    await expect(page.getByTestId('dingtalk-probe')).toHaveCount(0)
    expect(academicProbeRequests).toEqual([])

    const academicToken = await apiLogin(api, academic, ACCOUNT_PASSWORD)
    const denied = await api.post(`${API}${PROBE_PATH}`, {
      headers: { Authorization: `Bearer ${academicToken}` },
      data: { appKey: 'denied-key', appSecret: 'e2e-app-secret', useSavedSecret: false },
    })
    expect(denied.status()).toBe(403)
    expect(browserErrors).toEqual([])

    const teacherContext = await browser.newContext()
    try {
      const teacherPage = await teacherContext.newPage()
      const teacherProbeRequests = trackProbeRequests(teacherPage)
      const teacherBrowserErrors = trackBrowserErrors(teacherPage)
      const teacher = await apiCall(api, token, 'post', '/teachers', {
        name: `探测老师${suffix}`,
        employeeNo: `P-${suffix}`,
      })
      const teacherUsername = `probe_teacher_${suffix}`
      await createAccount(api, {
        username: teacherUsername,
        displayName: teacher.name,
        password: ACCOUNT_PASSWORD,
        roles: ['teacher'],
        teacherId: teacher.id,
        token,
      })

      await uiLogin(teacherPage, teacherUsername, ACCOUNT_PASSWORD)
      await teacherPage.goto('/settings/general')
      await expect(teacherPage).toHaveURL(/\/403$/)
      await expect(teacherPage.getByTestId('dingtalk-probe')).toHaveCount(0)
      expect(teacherProbeRequests).toEqual([])
      expect(teacherBrowserErrors).toEqual([])
    } finally {
      await teacherContext.close()
    }
  } finally {
    await api.dispose()
  }
})
