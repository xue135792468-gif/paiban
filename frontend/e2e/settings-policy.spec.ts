import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, uiLogin } from './helpers'

/**
 * Ticket 03 acceptance: the renamed "最多变动几节课" setting is still the same
 * persisted legacy key, survives a reload and is visible through the API.
 *
 * The settings page is a static form and the suite runs with one worker, so
 * changing a global value is safe here; the original value is restored through
 * the API in `finally` so later specs see the same defaults.
 */

const KEY = 'substitution.max-plan-teachers'

test('修改最多变动几节课后刷新仍保持，并通过 API 持久化', async ({ page, request }) => {
  const token = await apiLogin(request)
  const before = String((await apiCall(request, token, 'get', '/settings'))[KEY])
  const target = before === '5' ? '4' : '5'

  try {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/settings/general')

    const item = page.locator('.el-form-item').filter({ hasText: '最多变动几节课' })
    await expect(item).toBeVisible()
    await item.locator('.el-select').first().click()
    await page.getByRole('option', { name: `${target} 节`, exact: true }).click()

    const saved = page.waitForResponse(
      (response) =>
        response.url().includes('/api/settings') && response.request().method() === 'PUT',
    )
    await page.getByRole('button', { name: '保存设置' }).click()
    expect((await saved).ok()).toBeTruthy()

    await page.reload()
    await expect(item.locator('.el-select')).toContainText(`${target} 节`)

    const persisted = String((await apiCall(request, token, 'get', '/settings'))[KEY])
    expect(persisted).toBe(target)
  } finally {
    await apiCall(request, token, 'put', '/settings', { [KEY]: before })
  }
})
