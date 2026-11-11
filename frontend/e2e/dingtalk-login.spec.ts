import { expect, test } from '@playwright/test'
import type { APIRequestContext, BrowserContext, Page } from '@playwright/test'
import { API, apiCall, apiLogin, newContext, uiLogin } from './helpers'
import { capture } from './screenshots'

const DINGTALK_UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 DingTalk/7.5.10'

function trackBrowserErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  page.on('pageerror', (error) => errors.push(`pageerror: ${error}`))
  return errors
}

async function installFakeWindowDd(context: BrowserContext, authCode: string) {
  await context.addInitScript((code: string) => {
    localStorage.setItem('paiban.tour.seen', '1')
    localStorage.setItem('paiban.tour.advanced', '1')
    ;(window as any).dd = {
      ready: (cb: () => void) => cb(),
      runtime: {
        permission: {
          requestAuthCode: ({ onSuccess }: { onSuccess: (res: { code: string }) => void }) => {
            onSuccess({ code })
          },
        },
      },
    }
  }, authCode)
}

/**
 * 票 03 真实浏览器准备：
 * 1. 钉钉 UA 下免登失败展示结构化说明页，不静默退回密码表单；点击「我是管理员，用密码登录」
 *    进入 `/login?mode=password` 跳过自动免登，刷新不循环，并可用管理员密码登录。
 * 2. 已绑定钉钉 UID（`dd-001`）的教师在钉钉内打开应用，经 `fake:EMP001` 免登并取得同一账号。
 */
test('钉钉免登失败展示说明页，「我是管理员，用密码登录」带 ?mode=password 跳过循环并完成密码登录', async ({
  browser,
}) => {
  const api: APIRequestContext = await newContext()
  const token = await apiLogin(api)
  await apiCall(api, token, 'put', '/integrations/dingtalk/config', {
    enabled: true,
    appKey: 'e2e-app-key',
    appSecret: 'e2e-app-secret',
    agentId: '1000',
    corpId: 'ding-e2e-corp',
    syncStrategy: 'local',
  })

  const context = await browser.newContext({ userAgent: DINGTALK_UA })
  try {
    await installFakeWindowDd(context, 'fake:UNKNOWN_EMP')
    const page = await context.newPage()
    const browserErrors = trackBrowserErrors(page)
    const [dingtalkAuthResp] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' && response.url().endsWith('/api/auth/dingtalk'),
      ),
      page.goto('/login'),
    ])
    // 先钉住真实响应，再断页面文案：只看 fallback 展示可能是假绿。
    expect(dingtalkAuthResp.status()).toBe(401)
    expect((await dingtalkAuthResp.json()).code).toBe('DINGTALK_CALL_FAILED')

    const notice = page.getByTestId('dingtalk-login-notice')
    await expect(notice).toBeVisible()
    // 真实后端把未知员工身份映射成稳定原因码，页面据此渲染说明（不是任意 notice 就算过）
    await expect(notice).toHaveAttribute('data-reason-code', 'DINGTALK_CALL_FAILED')
    await expect(page.getByPlaceholder('请输入密码')).toHaveCount(0)
    await capture(page, 'dingtalk-t03-login-notice')

    await page.getByTestId('switch-password-login').click()
    await expect(page).toHaveURL(/mode=password/)
    await expect(notice).toHaveCount(0)
    await expect(page.getByPlaceholder('请输入密码')).toBeVisible()

    // 刷新页面不会再次触发自动免登循环
    await page.reload()
    await expect(page).toHaveURL(/mode=password/)
    await expect(notice).toHaveCount(0)
    await expect(page.getByPlaceholder('请输入用户名')).toBeVisible()

    await uiLogin(page, 'admin', 'admin123', { loginPath: '/login?mode=password' })
    await capture(page, 'dingtalk-t03-password-fallback-logged-in')
    expect(browserErrors.filter((msg) => !msg.includes('status of 401'))).toEqual([])
  } finally {
    await context.close()
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', { enabled: false })
    await api.dispose()
  }
})

test('已绑定UID老师免登并取得同一账号', async ({ browser }) => {
  const api: APIRequestContext = await newContext()
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)
  let createdOwnTeacher: { id: number; name: string; employeeNo: string } | null = null

  await apiCall(api, token, 'put', '/integrations/dingtalk/config', {
    enabled: true,
    appKey: 'e2e-app-key',
    appSecret: 'e2e-app-secret',
    agentId: '1000',
    corpId: 'ding-e2e-corp',
    syncStrategy: 'local',
  })

  const context = await browser.newContext({ userAgent: DINGTALK_UA })
  try {
    // 只读查找当前已绑定 dd-001 的教师，不修改其它 spec 的教师数据
    const allTeachers = await apiCall(api, token, 'get', '/teachers')
    const boundTeachers = allTeachers.filter((teacher: any) => teacher.dingtalkUserId === 'dd-001')
    if (boundTeachers.length > 1) {
      throw new Error(`precondition failed: multiple teachers bound to dd-001 (${boundTeachers.map((t: any) => t.id).join(',')})`)
    }

    let targetTeacherId: number
    if (boundTeachers.length === 1) {
      targetTeacherId = boundTeachers[0].id
    } else {
      const seeded = await apiCall(api, token, 'post', '/teachers', {
        name: `免登张老师${suffix}`,
        employeeNo: `DT-${suffix}`,
        dingtalkUserId: 'dd-001',
      })
      createdOwnTeacher = { id: seeded.id, name: seeded.name, employeeNo: seeded.employeeNo }
      targetTeacherId = seeded.id
    }

    const usersBefore: Array<{
      id: number
      teacherId?: number | null
      enabled: boolean
      loginType?: string
    }> = await apiCall(api, token, 'get', '/users')
    const linkedBefore = usersBefore.filter((u) => u.teacherId === targetTeacherId)
    const enabledBefore = linkedBefore
      .filter((u) => u.enabled)
      .sort((a, b) => a.id - b.id)
    if (linkedBefore.length > 0 && enabledBefore.length === 0) {
      throw new Error(
        `precondition failed: teacher #${targetTeacherId} bound to dd-001 only has disabled accounts; refusing to mutate foreign fixture`,
      )
    }
    const expectedExistingAccountId = enabledBefore.length > 0 ? enabledBefore[0].id : null

    await installFakeWindowDd(context, 'fake:EMP001')
    const page = await context.newPage()
    const browserErrors = trackBrowserErrors(page)

    const [dingtalkAuthResp] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' && response.url().endsWith('/api/auth/dingtalk'),
      ),
      page.goto('/login'),
    ])
    expect(dingtalkAuthResp.status()).toBe(200)
    const authBody = await dingtalkAuthResp.json()
    expect(authBody.token).toBeTruthy()
    expect(authBody.user?.teacherId).toBe(targetTeacherId)
    if (expectedExistingAccountId != null) {
      expect(authBody.user?.id).toBe(expectedExistingAccountId)
    } else {
      expect(authBody.user?.loginType).toBe('DINGTALK')
    }

    const me = await apiCall(api, authBody.token, 'get', '/auth/me')
    expect(me.id).toBe(authBody.user.id)
    expect(me.teacherId).toBe(targetTeacherId)
    if (expectedExistingAccountId == null) {
      expect(me.roles).toEqual(['teacher'])
      expect(me.permissions).not.toContain('settings:manage')
      expect(me.permissions).not.toContain('constraint:manage')
    }

    await page.waitForURL((url) => !url.pathname.startsWith('/login'))
    if (expectedExistingAccountId == null) {
      await expect(page).toHaveURL(/\/workbench\/timetable$/)
    }
    const menu = page.getByTestId('layout-menu')
    if (!me.permissions.includes('settings:manage')) {
      await expect(menu).not.toContainText('设置')
      await expect(menu).not.toContainText('AI 助手')
    }
    if (!me.permissions.includes('constraint:manage')) {
      await expect(menu).not.toContainText('排课规则')
    }
    await capture(page, 'dingtalk-t03-auto-provision-logged-in')
    const semesterResponse = await api.get(`${API}/semesters/current`, {
      headers: { Authorization: `Bearer ${authBody.token}` },
    })
    expect([200, 204]).toContain(semesterResponse.status())
    if (semesterResponse.status() === 204) {
      // 隔离启动时没有学期：真实后端返回204，不能用拦截或预造学期掩盖页面错误。
      await page.goto('/substitution/requests')
      await expect(page).toHaveURL(/\/substitution\/requests$/)
      await expect(page.getByTestId('substitution-no-semester')).toBeVisible()
      await expect(page.getByRole('button', { name: '提交申请' })).toHaveCount(0)
      await capture(page, 'dingtalk-t05-no-semester-requests')
    }
    expect(browserErrors).toEqual([])
  } finally {
    await context.close()
    if (createdOwnTeacher != null) {
      await apiCall(api, token, 'put', `/teachers/${createdOwnTeacher.id}`, {
        name: createdOwnTeacher.name,
        employeeNo: createdOwnTeacher.employeeNo,
        dingtalkUserId: '',
      })
    }
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', { enabled: false })
    await api.dispose()
  }
})
