import { expect, test } from '@playwright/test'
import type { APIRequestContext, BrowserContext } from '@playwright/test'
import { apiCall, apiLogin, newContext } from './helpers'
import { capture } from './screenshots'

const DINGTALK_UA =
  'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 DingTalk/7.5.10'

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
 * 2. 已绑定钉钉 UID 的教师首次在钉钉内打开应用，经 `fake:EMP001` 免登自动开号进入系统。
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
    await page.goto('/login')

    const notice = page.getByTestId('dingtalk-login-notice')
    await expect(notice).toBeVisible()
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

    await page.getByPlaceholder('请输入用户名').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('admin123')
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL((url) => !url.pathname.startsWith('/login'))
    await capture(page, 'dingtalk-t03-password-fallback-logged-in')
  } finally {
    await context.close()
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', { enabled: false }).catch(
      () => undefined,
    )
    await api.dispose()
  }
})

test('已绑定钉钉 UID 的老师在钉钉 UA 下通过假免登码自动开号进入系统', async ({ browser }) => {
  const api: APIRequestContext = await newContext()
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)
  let seededTeacherId: number | null = null
  const existingUsersWithDd001: Array<{ id: number; name: string; employeeNo: string | null }> = []

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
    // 确保没有其他教师残留占用 dd-001
    const allTeachers = await apiCall(api, token, 'get', '/teachers')
    for (const teacher of allTeachers) {
      if (teacher.dingtalkUserId === 'dd-001') {
        existingUsersWithDd001.push({
          id: teacher.id,
          name: teacher.name,
          employeeNo: teacher.employeeNo ?? null,
        })
        await apiCall(api, token, 'put', `/teachers/${teacher.id}`, {
          name: teacher.name,
          employeeNo: teacher.employeeNo ?? null,
          dingtalkUserId: '',
        })
      }
    }

    const seeded = await apiCall(api, token, 'post', '/teachers', {
      name: `免登张老师${suffix}`,
      employeeNo: `DT-${suffix}`,
      dingtalkUserId: 'dd-001',
    })
    seededTeacherId = seeded.id

    await installFakeWindowDd(context, 'fake:EMP001')
    const page = await context.newPage()
    await page.goto('/login')
    await page.waitForURL((url) => !url.pathname.startsWith('/login'))
    await capture(page, 'dingtalk-t03-auto-provision-logged-in')
  } finally {
    await context.close()
    if (seededTeacherId != null) {
      await apiCall(api, token, 'put', `/teachers/${seededTeacherId}`, {
        name: `免登张老师${suffix}`,
        employeeNo: `DT-${suffix}`,
        dingtalkUserId: '',
      }).catch(() => undefined)
    }
    for (const prev of existingUsersWithDd001) {
      await apiCall(api, token, 'put', `/teachers/${prev.id}`, {
        name: prev.name,
        employeeNo: prev.employeeNo,
        dingtalkUserId: 'dd-001',
      }).catch(() => undefined)
    }
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', { enabled: false }).catch(
      () => undefined,
    )
    await api.dispose()
  }
})
