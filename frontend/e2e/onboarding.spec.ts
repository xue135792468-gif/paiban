import { expect, test } from '@playwright/test'
import { uiLogin } from './helpers'

// Depends on an empty e2e database. Specs that call seedSchool run later
// (zz-junior-mode.spec.ts), so this file sees the "first login" condition.

test('首次登录进入初始化向导并可跳过', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await expect(page).toHaveURL(/\/onboarding/)
  await expect(page.getByRole('heading', { name: '欢迎使用排课系统' })).toBeVisible()
  await expect(page.getByText('学校信息').first()).toBeVisible()

  await page.getByRole('button', { name: '跳过向导' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('还差什么')).toBeVisible()
})
