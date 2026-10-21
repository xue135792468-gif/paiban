import { expect, test, type Page } from '@playwright/test'

/**
 * Ticket 03 验收：复杂字段旁的问号图标悬停后显示配置化的说明文案。
 *
 * 不 seed 数据、不读数据库：设置页是静态表单，登录后直接进入即可。
 */
async function uiLogin(page: Page, username = 'admin', password = 'admin123') {
  // 教程覆盖层会挡住悬停，先把「已看过」写进去（与 01 的 e2e 助手同一组键）。
  await page.addInitScript(() => {
    localStorage.setItem('paiban.tour.seen', '1')
    localStorage.setItem('paiban.tour.advanced', '1')
  })
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

test('悬停「学校类型」旁的问号显示字段说明', async ({ page }) => {
  await uiLogin(page)
  await page.goto('/admin/settings')

  const icon = page
    .locator('.el-form-item')
    .filter({ hasText: '学校类型' })
    .getByTestId('info-tooltip')
    .first()

  await expect(icon).toBeVisible()
  await icon.hover()

  const tooltip = page.getByRole('tooltip')
  await expect(tooltip).toBeVisible()
  await expect(tooltip).toContainText('初中模式会隐藏权重、模板、多草稿对比等高级功能')
})
