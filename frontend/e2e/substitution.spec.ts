import { expect, test } from '@playwright/test'
import { classEntries, newContext, seedSchool, solveAndPublish, uiLogin } from './helpers'

function dateForWeekday(dayOfWeek: number) {
  const date = new Date('2025-09-01T00:00:00')
  date.setDate(date.getDate() + (dayOfWeek - 1))
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

test('场景 2：教师提交调代 → 教务审批 → 通知与课表叠加', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  await solveAndPublish(api, seed)

  const entries = await classEntries(api, seed.classId)
  const entry = entries.find((item) => item.dayOfWeek != null)
  expect(entry).toBeTruthy()
  const effectiveDate = dateForWeekday(entry!.dayOfWeek)

  await uiLogin(page, seed.username, seed.password)
  await page.goto('/substitutions')
  const dateInput = page.getByRole('combobox', { name: '请假日期' })
  await dateInput.fill(effectiveDate)
  await dateInput.press('Tab')

  await page.locator('.el-form-item', { hasText: '课节' }).locator('.el-select').click()
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first().click()

  await page.getByPlaceholder('如：家中有事，需要请假半天').fill('家中有事，需要请假')
  await page.getByRole('button', { name: '提交申请' }).click()
  await expect(page.getByText(/申请已提交|调代已直接生效/)).toBeVisible()

  await page.evaluate(() => localStorage.clear())
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '待审批' }).click()
  await expect(page.getByRole('button', { name: '批准' }).first()).toBeVisible()
  await page.getByRole('button', { name: '批准' }).first().click()
  await page.locator('.el-dialog .el-form-item', { hasText: '代课教师' }).locator('.el-select').click()
  await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').first().click()
  await page.getByRole('button', { name: '确认批准' }).click()
  await expect(page.getByText('已批准，调代已生效')).toBeVisible()

  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '调代记录' }).click()
  await expect(page.getByText('已生效').first()).toBeVisible()

  await page.evaluate(() => localStorage.clear())
  await uiLogin(page, seed.username, seed.password)

  await page.goto('/')
  await expect(page.locator('.bell-badge sup').first()).toBeVisible()
  await page.locator('.bell-badge button').click()
  await expect(page.getByText('调代申请已批准').first()).toBeVisible()
  await page.keyboard.press('Escape')

  await page.goto('/notifications')
  await expect(page.getByText('调代申请已批准').first()).toBeVisible()

  await page.goto('/timetable')
  await page.getByPlaceholder('选择日期').fill(effectiveDate)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.getByText('代').first()).toBeVisible()
})
