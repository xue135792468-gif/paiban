import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { apiCall, apiLogin, uiLogin } from './helpers'

// Depends on an empty e2e database. Specs that call seedSchool run later
// (zz-junior-mode.spec.ts), so this file sees the "first login" condition.
// Each test resets the admin's wizard progress so the order of tests and of
// earlier runs cannot change whether the wizard is shown.

async function resetWizard(request: APIRequestContext) {
  const token = await apiLogin(request)
  await apiCall(request, token, 'post', '/onboarding/reset')
}

/** Clicks a wizard action and waits for its progress write to land before the next one. */
async function clickAndPersist(page: Page, name: string) {
  const saved = page.waitForResponse(
    (response) =>
      response.url().includes('/api/onboarding') && response.request().method() === 'PUT',
  )
  await page.getByRole('button', { name }).click()
  await saved
}

async function answerSkipDialog(page: Page, confirm: boolean) {
  await page.getByRole('button', { name: '跳过向导' }).click()
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('确定跳过初始化向导吗？您可以随时在设置中重新进入')
  await dialog.getByRole('button', { name: confirm ? '确定' : '取消' }).click()
  // Element Plus keeps the box in the DOM during its close transition.
  await expect(dialog).toBeHidden()
}

test('首次登录进入初始化向导，显示进度与帮助链接，可跳过', async ({ page, request }) => {
  await resetWizard(request)
  await uiLogin(page, 'admin', 'admin123')
  await expect(page).toHaveURL(/\/onboarding/)
  await expect(page.getByRole('heading', { name: '欢迎使用排课系统' })).toBeVisible()

  // 进度按真实步数（8 步）显示，不是票据示意里的 6 步。
  await expect(page.getByTestId('wizard-progress')).toHaveText('第 1/8 步：学校信息')
  await expect(page.getByRole('link', { name: '查看帮助' })).toHaveAttribute(
    'href',
    '/help/flow-term-start#school',
  )

  await clickAndPersist(page, '标记完成并继续')
  await expect(page.getByTestId('wizard-progress')).toHaveText('第 2/8 步：学期与作息表')
  await expect(page.getByRole('link', { name: '查看帮助' })).toHaveAttribute(
    'href',
    '/help/flow-term-start#periods',
  )

  // 取消确认框时留在向导内，不落盘跳过。
  await answerSkipDialog(page, false)
  await expect(page).toHaveURL(/\/onboarding/)

  await answerSkipDialog(page, true)
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('还差什么')).toBeVisible()
})

test('完成向导后显示庆祝页，点开始使用进入首页', async ({ page, request }) => {
  await resetWizard(request)
  await uiLogin(page, 'admin', 'admin123')
  await expect(page).toHaveURL(/\/onboarding/)

  for (let step = 1; step < 8; step += 1) {
    await clickAndPersist(page, '标记完成并继续')
  }
  await expect(page.getByTestId('wizard-progress')).toHaveText('第 8/8 步：约束规则')
  await expect(page.getByRole('link', { name: '查看帮助' })).toHaveAttribute(
    'href',
    '/help/flow-term-start#constraints',
  )

  await clickAndPersist(page, '完成向导')

  // 庆祝页在同一路由内出现，不立刻跳转。
  await expect(page).toHaveURL(/\/onboarding/)
  await expect(page.getByRole('heading', { name: '初始化完成！' })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始使用' })).toBeVisible()

  await page.getByRole('button', { name: '开始使用' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('还差什么')).toBeVisible()

  // 完成标记已持久化：刷新不会再被送回向导。
  await page.reload()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByText('还差什么')).toBeVisible()
})

test('设置页可以重新进入初始化向导', async ({ page, request }) => {
  await resetWizard(request)
  await uiLogin(page, 'admin', 'admin123')
  await expect(page).toHaveURL(/\/onboarding/)

  await page.goto('/admin/settings')
  const card = page.locator('#wizard-reenter')
  await expect(card).toBeVisible()
  await expect(card).toContainText('初始化向导')
  await card.getByRole('button', { name: '重新进入初始化向导' }).click()

  await expect(page).toHaveURL(/\/onboarding/)
  await expect(page.getByRole('heading', { name: '欢迎使用排课系统' })).toBeVisible()
  await expect(page.getByTestId('wizard-progress')).toHaveText('第 1/8 步：学校信息')
})
