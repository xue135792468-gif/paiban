import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, dateFor, newContext, seedSwapScenario, uiLogin } from './helpers'

const FRIDAY = dateFor(5)

async function submitSwap(page: import('@playwright/test').Page, date: string, reason: string) {
  await page.goto('/substitutions')
  await page.locator('.el-radio-button', { hasText: '调课' }).click()
  const dateInput = page.getByRole('combobox', { name: '调课日期' })
  await dateInput.fill(date)
  await dateInput.press('Tab')

  await page.locator('.el-form-item', { hasText: '我的课节' }).locator('.el-select').click()
  const sourceOption = page
    .locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: '语文' })
    .first()
  await expect(sourceOption).toBeVisible()
  await sourceOption.click()

  await page.locator('.el-form-item', { hasText: '换到' }).locator('.el-select').click()
  const targetOption = page
    .locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: '数学' })
    .first()
  await expect(targetOption).toBeVisible()
  await targetOption.click()

  await page.getByPlaceholder('如：周三第 3 节想换到第 2 节').fill(reason)
  await page.getByRole('button', { name: '提交申请' }).click()
  await expect(page.getByText('调课申请已提交，等待教务生成方案')).toBeVisible()
}

async function adminApprovePlan(page: import('@playwright/test').Page, expectedImpact: string) {
  await page.evaluate(() => localStorage.clear())
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '待审批' }).click()
  await page.getByRole('button', { name: '生成方案' }).first().click()
  const dialog = page.locator('.el-dialog', { hasText: '候选调课方案' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.plan-card').first()).toContainText(expectedImpact)
  await dialog.getByRole('button', { name: '整单批准' }).first().click()
  await expect(page.getByText('方案已整单批准并生效')).toBeVisible()
}

test('场景 9a：教师提交同日调课 → 教务生成方案并整单批准 → 课表显示「调」', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapScenario(api, { blockSameDay: false })

  await uiLogin(page, seed.username, seed.password)
  await submitSwap(page, FRIDAY, '想把周五第一节换到第二节')

  await adminApprovePlan(page, '影响 1 天')

  // The plan produced a real swap: two lessons moved, both teachers are involved.
  const token = await apiLogin(api)
  const records = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(records.length).toBe(2)
  const statsA = await apiCall(api, token, 'get', `/substitutions/stats?teacherId=${seed.teachers.a}`)
  expect(statsA.swapParticipation).toBe(1)
  expect(statsA.substitutedOut).toBe(0)
  expect(statsA.substitutedIn).toBe(0)
  const statsB = await apiCall(api, token, 'get', `/substitutions/stats?teacherId=${seed.teachers.b}`)
  expect(statsB.swapParticipation).toBe(1)
  expect(statsB.substitutedOut).toBe(0)

  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '调代记录' }).click()
  await expect(page.getByText('已生效').first()).toBeVisible()
  await expect(page.locator('.el-tag', { hasText: '调课' }).first()).toBeVisible()

  await page.goto('/timetable')
  await page.getByPlaceholder('选择日期').fill(FRIDAY)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.getByText('调').first()).toBeVisible()
})

test('场景 9b：周五无解时跨天到下周 → 影响 2 天且整单生效', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapScenario(api, { blockSameDay: true })

  await uiLogin(page, seed.username, seed.password)
  await submitSwap(page, FRIDAY, '周五当天换不开，请向后找')

  await adminApprovePlan(page, '影响 2 天')

  const token = await apiLogin(api)
  const records = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(records.length).toBe(3)
  const days = new Set(records.map((row: { effectiveDate: string }) => row.effectiveDate))
  expect(days.size).toBe(2)

  await page.goto('/timetable')
  await page.getByPlaceholder('选择日期').fill(FRIDAY)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.getByText('调').first()).toBeVisible()
})
