import { expect, test, type Page } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool, solveAndPublish } from './helpers'

async function uiLogin(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

async function setSchoolType(type: 'junior' | 'senior') {
  const api = await newContext()
  try {
    const token = await apiLogin(api)
    await apiCall(api, token, 'put', '/settings', { 'school.type': type })
  } finally {
    await api.dispose()
  }
}

test.describe('初中模式', () => {
  test.afterAll(async () => {
    await setSchoolType('junior').catch(() => undefined)
  })

  test('切换到初中模式后隐藏高级功能', async ({ page }) => {
    const api = await newContext()
    const seed = await seedSchool(api)
    const draftId = await solveAndPublish(api, seed)
    await api.dispose()

    // Start from senior so the UI switch is a real change.
    await setSchoolType('senior')

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/settings')
    await expect(page.getByText('学校类型')).toBeVisible()

    const juniorRadio = page.locator('.el-radio-button', { hasText: '初中' })
    const seniorRadio = page.locator('.el-radio-button', { hasText: '高中' })
    await expect(seniorRadio.locator('input')).toBeChecked()

    await juniorRadio.click()
    await page.getByRole('button', { name: '保存设置' }).click()
    await expect(page.getByText('切换后将刷新页面，初中模式将隐藏高级功能')).toBeVisible()
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'domcontentloaded' }),
      page.getByRole('button', { name: '确定' }).click(),
    ])
    await expect(page.getByText('学校类型')).toBeVisible()

    // 设置中心：同步策略表单项隐藏，学校类型保持初中。
    await expect(page.getByText('同步策略')).toHaveCount(0)
    await expect(juniorRadio.locator('input')).toBeChecked()

    // 约束配置：无权重、无模板区、无教研活动禁排，改为必须/尽量两档。
    await page.goto('/admin/constraints')
    await expect(page.locator('.constraint-card').first()).toBeVisible()
    await expect(page.locator('.constraint-controls').getByText('必须').first()).toBeVisible()
    await expect(page.locator('.constraint-controls').getByText('尽量').first()).toBeVisible()
    await expect(page.getByText('权重')).toHaveCount(0)
    await expect(page.getByText('存为模板')).toHaveCount(0)
    await expect(page.getByText('应用模板')).toHaveCount(0)
    await expect(page.getByText('教研活动禁排')).toHaveCount(0)

    // 课表：全校视角隐藏。
    await page.goto('/timetable')
    await expect(page.getByText('全校', { exact: true })).toHaveCount(0)
    await expect(page.locator('.actions .el-radio-button', { hasText: '行政班' })).toBeVisible()

    // 草稿编辑：比较草稿入口隐藏。
    await page.goto(`/admin/drafts/${draftId}`)
    await expect(page.getByRole('button', { name: '刷新' })).toBeVisible()
    await expect(page.getByRole('button', { name: '比较草稿' })).toHaveCount(0)
    await expect(page.getByText('并排比较草稿')).toHaveCount(0)
  })
})
