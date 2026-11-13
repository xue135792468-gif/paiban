import { expect, test } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  createAccount,
  newContext,
  seedSchool,
  seedWorkbenchScenario,
  uiLogin,
} from './helpers'
import { capture } from './screenshots'

const SUPER_ADMIN_PASSWORD = 'admin123'
const TEST_PASSWORD = 'teacher123'
let browserErrors: string[] = []

test.beforeEach(({ page }) => {
  browserErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text())
  })
  page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error}`))
})

test.afterEach(() => {
  expect(browserErrors).toEqual([])
})

test('教务管理员在设置页查询真实服务产生的 local 发送记录', async ({ page }) => {
  const api = await newContext()
  try {
    const seed = await seedWorkbenchScenario(api)
    const token = await apiLogin(api)
    const suffix = Math.random().toString(36).slice(2, 8)
    await createAccount(api, {
      username: `cover_target_${suffix}`,
      displayName: seed.substituteTeacherName,
      password: TEST_PASSWORD,
      roles: ['teacher'],
      teacherId: seed.substituteTeacherId,
      token,
    })

    const preview = await apiCall(api, token, 'post', '/substitution-workbench/previews', {
      entryId: seed.entryId,
      lessonDate: seed.lessonDate,
      substituteTeacherId: seed.substituteTeacherId,
      reason: '通知发送记录真实链路',
    })
    expect(preview.canConfirm).toBe(true)
    await apiCall(
      api,
      token,
      'post',
      `/substitution-workbench/previews/${preview.voucherId}/confirm`,
      {},
    )

    await expect
      .poll(
        async () => {
          const logs = await apiCall(
            api,
            token,
            'get',
            '/notification-delivery-logs',
            undefined,
            { page: 0, size: 20 },
          )
          return logs.items.some(
            (item: { channel: string; recipientLabel: string; success: boolean }) =>
              item.channel === 'local' &&
              item.recipientLabel === seed.substituteTeacherName &&
              item.success,
          )
        },
        { timeout: 20_000 },
      )
      .toBe(true)

    const academicUsername = `notification_admin_${suffix}`
    await createAccount(api, {
      username: academicUsername,
      displayName: `通知记录教务${suffix}`,
      password: TEST_PASSWORD,
      roles: ['admin'],
      token,
    })
    await uiLogin(page, academicUsername, TEST_PASSWORD)
    await page.goto('/settings/general')

    const policyCard = page.locator('.el-card').filter({ hasText: '调代与通知' }).first()
    await expect(policyCard.locator('.el-form-item')).toHaveCount(3)
    await expect(policyCard.locator('.el-form-item').nth(0)).toContainText('调课搜索窗口')
    await expect(policyCard.locator('.el-form-item').nth(1)).toContainText('最多变动几节课')
    await expect(policyCard.locator('.el-form-item').nth(2)).toContainText('方案排序')
    await expect(policyCard).not.toContainText('通知范围')
    await expect(page.getByText('调代通知收件人', { exact: true })).toHaveCount(0)

    const logsCard = page.locator('.el-card').filter({ hasText: '通知发送记录' }).first()
    await expect(logsCard).toBeVisible()
    const query = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        response.url().includes('/api/notification-delivery-logs?page=0&size=20'),
    )
    await logsCard.getByText('查看发送结果', { exact: true }).click()
    expect((await query).status()).toBe(200)

    const row = logsCard.locator('.el-table__row').filter({ hasText: seed.substituteTeacherName })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('系统已保存')
    await row.scrollIntoViewIfNeeded()
    await capture(page, 'dingtalk-t06-academic-notification-logs')
  } finally {
    await api.dispose()
  }
})

test('老师直接打开设置页被拒，且不会请求发送记录', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  await api.dispose()

  const deliveryRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/notification-delivery-logs')) {
      deliveryRequests.push(request.url())
    }
  })

  await uiLogin(page, seed.username, seed.password)
  await page.goto('/settings/general')

  await expect(page).toHaveURL(/\/403$/)
  expect(deliveryRequests).toEqual([])
  await expect(page.getByText('通知发送记录', { exact: true })).toHaveCount(0)
})

test('超级管理员保持默认钥匙登录并可进入设置页', async ({ page }) => {
  await uiLogin(page, 'admin', SUPER_ADMIN_PASSWORD)
  await page.goto('/settings/general')

  await expect(page.getByText('通知发送记录', { exact: true })).toBeVisible()
  await expect(page.locator('.el-card').filter({ hasText: '调代与通知' }).first()).not.toContainText(
    '通知范围',
  )
})
