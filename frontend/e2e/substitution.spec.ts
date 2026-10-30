import { expect, test } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  classEntries,
  dateFor,
  isoDate,
  newContext,
  seedSchool,
  shiftDate,
  solveAndPublish,
  uiLogin,
} from './helpers'

// 种子学期锚在未来的周一（批准受开课截止约束），日期一律由 dateFor 派生。
function dateForWeekday(dayOfWeek: number) {
  return dateFor(dayOfWeek)
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

  // 自助表单的日期选择器禁止过去日期：今天可选、昨天置灰。
  const localToday = isoDate(new Date())
  const yesterday = shiftDate(localToday, -1)
  await dateInput.click()
  const datePanel = page.locator('.el-picker-panel:visible').last()
  const dateTable = datePanel.locator('.el-date-table')
  await expect(dateTable).toBeVisible()
  await expect(dateTable.locator('td.available.today').first()).not.toHaveClass(/disabled/)
  if (yesterday.slice(0, 7) !== localToday.slice(0, 7)) {
    await datePanel.locator('.el-date-picker__prev-btn').click()
  }
  const yesterdayCell = dateTable
    .locator('td.disabled:not(.prev-month):not(.next-month)', {
      hasText: new RegExp(`^\\s*${Number(yesterday.slice(8))}\\s*$`),
    })
    .first()
  await expect(yesterdayCell).toHaveClass(/disabled/)
  await page.keyboard.press('Escape')
  await expect(datePanel).toBeHidden()

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
  await expect(page.getByText('已批准，代课已生效')).toBeVisible()

  // A cover is counted separately from a swap: the original teacher was covered
  // once, and is not credited with any swap participation.
  const token = await apiLogin(api)
  const stats = await apiCall(api, token, 'get', `/substitutions/stats?teacherId=${seed.teacherId}`)
  expect(stats.substitutedOut).toBe(1)
  expect(stats.swapParticipation).toBe(0)

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

  // 一次性调代只影响所指授课日期（ADR-0030）。
  // 申请者（被代教师）视角：授课日这节课被代课人接走，课表少一节；
  // 下周同一槽位恢复常规，课回到课表且全程不出现「代」标记。
  await page.goto('/timetable')
  await page.getByPlaceholder('选择日期').fill(effectiveDate)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(0)
  const lessonsOnLessonWeek = await page.locator('.lesson').count()

  const nextWeekDate = new Date(`${effectiveDate}T00:00:00`)
  nextWeekDate.setDate(nextWeekDate.getDate() + 7)
  const nextWeek = `${nextWeekDate.getFullYear()}-${String(nextWeekDate.getMonth() + 1).padStart(2, '0')}-${String(nextWeekDate.getDate()).padStart(2, '0')}`
  await page.getByPlaceholder('选择日期').fill(nextWeek)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson')).toHaveCount(lessonsOnLessonWeek + 1)
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(0)

  // 管理员的班级课表对同一实例给出一致结果：授课日出现「代」，下周恢复基线。
  await page.evaluate(() => localStorage.clear())
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/timetable')
  await page.getByPlaceholder('选择日期').fill(effectiveDate)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' }).first()).toBeVisible()
  await page.getByPlaceholder('选择日期').fill(nextWeek)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(0)
})
