import { expect, test, type Page } from '@playwright/test'
import { uiLogin } from './helpers'

/**
 * 票据 01 验收：首次进入首页弹出新手教程，可逐步前进、可跳过，设置页可重开。
 * `uiLogin(..., { tour: true })` 不写 localStorage 标记，模拟新用户首次登录。
 */

async function skipWizardIfShown(page: Page) {
  if (!new URL(page.url()).pathname.startsWith('/onboarding')) return
  await page.getByRole('button', { name: '跳过向导' }).click()

  // 票据 05 会给跳过加上确认弹窗；有就点确定，没有就直接继续。
  const confirm = page.locator('.el-message-box')
  const appeared = await confirm
    .waitFor({ state: 'visible', timeout: 2_000 })
    .then(() => true)
    .catch(() => false)
  if (appeared) {
    await confirm.getByRole('button', { name: /确定|确认/ }).first().click()
  }
  await page.waitForURL((url) => url.pathname === '/')
}

function popoverTitle(page: Page) {
  return page.locator('.driver-popover-title')
}

test('首次进入首页弹出教程，可逐步下一步直到完成', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123', { tour: true })
  await skipWizardIfShown(page)

  await expect(popoverTitle(page)).toHaveText('欢迎使用排课系统')

  const steps = ['基础数据', '自动排课', '调代课', '课表查看', '开始使用']
  for (const title of steps) {
    await page.getByRole('button', { name: '下一步' }).click()
    await expect(popoverTitle(page)).toHaveText(title)
  }

  await page.getByRole('button', { name: '开始使用' }).click()
  await expect(page.locator('.driver-popover')).toBeHidden()
  await expect
    .poll(() => page.evaluate(() => localStorage.getItem('paiban.tour.seen')))
    .toBe('1')
})

test('跳过教程后不再自动弹出，设置页可以重新打开', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123', { tour: true })
  await skipWizardIfShown(page)

  await expect(popoverTitle(page)).toHaveText('欢迎使用排课系统')
  await page.getByRole('button', { name: '跳过教程' }).click()
  await expect(page.locator('.driver-popover')).toBeHidden()
  // driver.js 在销毁回调里落盘标记；等它写完再刷新，避免与 reload 抢时序。
  await expect
    .poll(() => page.evaluate(() => localStorage.getItem('paiban.tour.seen')))
    .toBe('1')

  // 标记已看过：重新加载首页不会再弹。
  await page.reload()
  await page.waitForTimeout(1_500)
  await expect(page.locator('.driver-popover')).toBeHidden()

  // 设置中心可以重开。
  await page.goto('/admin/settings')
  await page.getByRole('button', { name: '重新查看新手教程' }).click()
  await expect(popoverTitle(page)).toHaveText('欢迎使用排课系统')

  await page.getByRole('button', { name: '跳过教程' }).click()
  await expect(page.locator('.driver-popover')).toBeHidden()
})
