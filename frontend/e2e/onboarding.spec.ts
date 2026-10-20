import { expect, test, type Page } from '@playwright/test'

async function uiLogin(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

test('首次登录进入初始化向导并可跳过', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await expect(page).toHaveURL(/\/onboarding/)
  await expect(page.getByRole('heading', { name: '欢迎使用排课系统' })).toBeVisible()
  await expect(page.getByText('学校信息').first()).toBeVisible()

  await page.getByRole('button', { name: '跳过向导' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('还差什么')).toBeVisible()
})
