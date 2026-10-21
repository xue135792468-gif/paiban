import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, newContext } from './helpers'

/**
 * 场景：教师从飞书工作台进入 H5 微应用时自动免登。
 * `window.tt` 由飞书客户端注入，这里用 addInitScript 模拟；后端 e2e profile
 * 已启用飞书集成并使用确定性 fake 客户端（fake:EMP001 -> fs-001/ou-001/张三）。
 */
test('飞书免登：注入 window.tt 后自动登录并进入系统', async ({ page }) => {
  const api = await newContext()
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)
  const displayName = `飞书教师${suffix}`

  const teacher = await apiCall(api, token, 'post', '/teachers', {
    name: displayName,
    employeeNo: `FS-${suffix}`,
    feishuUserId: 'fs-001',
    feishuOpenId: 'ou-001',
  })
  await apiCall(api, token, 'post', '/users', {
    username: `feishu_${suffix}`,
    password: 'teacher123',
    displayName,
    roleCodes: ['teacher'],
    teacherId: teacher.id,
  })

  try {
    await page.addInitScript(() => {
      ;(window as any).tt = {
        requestAccess: ({ success }: { success: (result: { code: string }) => void }) =>
          success({ code: 'fake:EMP001' }),
      }
    })

    await page.goto('/login')
    await page.waitForURL((url) => !url.pathname.startsWith('/login'))
    await expect(page.getByText(displayName).first()).toBeVisible()
  } finally {
    await apiCall(api, token, 'delete', `/teachers/${teacher.id}`).catch(() => undefined)
    await api.dispose()
  }
})
