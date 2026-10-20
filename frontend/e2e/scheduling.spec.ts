import { expect, test, type Page } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool } from './helpers'

async function uiLogin(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

test('场景 1：录入数据 → 自动排课 → 发布 → 查看课表', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)

  await uiLogin(page, 'admin', 'admin123')

  await page.goto('/admin/scheduling')
  await page.getByRole('button', { name: '开始排课' }).click()
  await expect(page.getByText('已完成').first()).toBeVisible({ timeout: 150_000 })

  await page.getByRole('button', { name: '打开草稿' }).first().click()
  await expect(page).toHaveURL(/\/admin\/drafts\/\d+/)
  await expect(page.locator('.lesson').first()).toBeVisible()

  await page.getByRole('button', { name: '发布课表' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText(/课表已发布/)).toBeVisible({ timeout: 30_000 })

  await page.goto('/timetable')
  await expect(page.locator('.lesson').first()).toBeVisible()
})

test('场景 3：未启用钉钉时系统完整可用', async ({ page }) => {
  const api = await newContext()
  const token = await apiLogin(api)
  const config = await apiCall(api, token, 'get', '/integrations/dingtalk/config')
  expect(config.enabled).toBe(false)

  await seedSchool(api)
  await uiLogin(page, 'admin', 'admin123')

  await page.goto('/timetable')
  await expect(page.getByRole('heading', { name: '课表' })).toBeVisible()

  await page.goto('/notifications')
  await expect(page.getByRole('heading', { name: '通知中心' })).toBeVisible()

  await page.goto('/admin/settings')
  await expect(page.getByText('钉钉集成')).toBeVisible()
})
