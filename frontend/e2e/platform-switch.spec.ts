import { expect, test } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { apiCall, apiLogin, newContext } from './helpers'

/**
 * Ticket 05 验收：教师自助绑定 + 钉钉切换到飞书后数据保留。
 *
 * 两个用例按顺序共享同一后端进程（e2e profile 使用确定性 fake 客户端）：
 * 1. 未绑定教师从飞书进入 -> /bind -> 输入姓名与工号 -> 进入系统。
 * 2. 管理员在设置页一键切换钉钉 -> 飞书，同步自动按工号绑定教师，切换前数据仍在。
 */
test.describe.configure({ mode: 'serial' })

let api: APIRequestContext
let token: string

test.beforeAll(async () => {
  api = await newContext()
  token = await apiLogin(api)
})

test.afterAll(async () => {
  try {
    await apiCall(api, token, 'put', '/integrations/platform', { platform: 'none' })
  } finally {
    await api.dispose()
  }
})

test('未绑定教师自助绑定', async ({ page }) => {
  const suffix = Math.random().toString(36).slice(2, 8)

  // 只写入飞书凭证与启用开关；当前平台由「启用开关派生」得出飞书（e2e profile 中钉钉已停用），
  // 不经过 /settings 写 active-platform —— 该键只能由平台切换端点修改。
  await apiCall(api, token, 'put', '/settings', {
    'integration.feishu.enabled': 'true',
    'integration.feishu.app-id': 'e2e-app-id',
    'integration.feishu.app-secret': 'e2e-app-secret',
  })
  const teacher = await apiCall(api, token, 'post', '/teachers', {
    name: '李四',
    employeeNo: 'LOCAL-002',
    phone: '13900000008',
  })
  await apiCall(api, token, 'post', '/users', {
    username: `bind_${suffix}`,
    password: 'teacher123',
    displayName: '李四',
    roleCodes: ['teacher'],
    teacherId: teacher.id,
  })

  await page.addInitScript(() => {
    ;(window as any).tt = {
      requestAccess: ({ success }: { success: (result: { code: string }) => void }) =>
        success({ code: 'fake:EMP002' }),
    }
  })

  await page.goto('/login')
  await page.waitForURL((url) => url.pathname.startsWith('/bind'))
  await expect(page.getByText('绑定飞书账号').first()).toBeVisible()

  await page.getByPlaceholder('请输入与学校档案一致的姓名').fill('李四')
  await page.getByPlaceholder('请输入工号（与手机号至少填一项）').fill('LOCAL-002')
  await page.getByRole('button', { name: '完成绑定' }).click()

  await page.waitForURL((url) => !url.pathname.startsWith('/bind') && !url.pathname.startsWith('/login'))
  expect(new URL(page.url()).pathname).toBe('/')
  await expect(page.getByText('李四').first()).toBeVisible()

  const teachers = await apiCall(api, token, 'get', '/teachers')
  const bound = teachers.find((item: any) => item.employeeNo === 'LOCAL-002')
  expect(bound?.feishuUserId).toBe('fs-002')
})

test('钉钉切换到飞书并保留数据', async ({ page }) => {
  const suffix = Math.random().toString(36).slice(2, 8)

  await apiCall(api, token, 'put', '/integrations/dingtalk/config', {
    enabled: true,
    appKey: 'e2e-app-key',
    appSecret: 'e2e-app-secret',
    agentId: '1000',
    syncStrategy: 'local',
  })
  await apiCall(api, token, 'put', '/integrations/feishu/config', {
    enabled: true,
    appId: 'e2e-app-id',
    appSecret: 'e2e-app-secret',
    syncStrategy: 'local',
  })

  // 先建教师再切换：钉钉同步会按工号匹配到它，而不是新建重复教师。
  const teacher = await apiCall(api, token, 'post', '/teachers', {
    name: '张三',
    employeeNo: 'EMP001',
  })
  await apiCall(api, token, 'post', '/users', {
    username: `switch_${suffix}`,
    password: 'teacher123',
    displayName: '张三',
    roleCodes: ['teacher'],
    teacherId: teacher.id,
  })

  const dingtalkStatus = await apiCall(api, token, 'put', '/integrations/platform', {
    platform: 'dingtalk',
  })
  expect(dingtalkStatus.activePlatform).toBe('dingtalk')

  // 管理员从设置页一键切换到飞书。
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill('admin')
  await page.getByPlaceholder('请输入密码').fill('admin123')
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))

  await page.goto('/admin/settings')
  const card = page.getByTestId('im-platform-card')
  await expect(card).toBeVisible()
  await expect(card.getByText('当前：钉钉')).toBeVisible()

  await card.locator('label').filter({ hasText: '飞书' }).click()
  await card.getByTestId('platform-switch-button').click()

  const confirmBox = page.locator('.el-message-box')
  await expect(confirmBox).toContainText('将停用钉钉，启用飞书。教职工数据保留，需重新绑定飞书账号。')
  await page.getByRole('button', { name: '确认切换' }).click()

  await expect(page.getByText('已切换到飞书')).toBeVisible()
  await expect(card.getByText('当前：飞书')).toBeVisible()

  // 切换后教师首次从飞书进入：目录同步已按工号绑定 fs-001，直接免登。
  await page.evaluate(() => localStorage.clear())
  await page.addInitScript(() => {
    ;(window as any).tt = {
      requestAccess: ({ success }: { success: (result: { code: string }) => void }) =>
        success({ code: 'fake:EMP001' }),
    }
  })
  await page.goto('/login')
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
  await expect(page.getByText('张三').first()).toBeVisible()

  // 切换前创建的教师仍然存在（未被同步删除或重建）。
  const teachers = await apiCall(api, token, 'get', '/teachers')
  const kept = teachers.find((item: any) => item.employeeNo === 'EMP001')
  expect(kept?.id).toBe(teacher.id)
  expect(kept?.feishuUserId).toBe('fs-001')
})
