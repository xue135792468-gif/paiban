import { expect, test } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  classEntries,
  dateFor,
  fillDateInput,
  isoDate,
  newContext,
  pickFirstVisibleOption,
  seedSchool,
  shiftDate,
  solveAndPublish,
  uiLogin,
} from './helpers'

// 种子学期锚在未来的周一（调代受开课截止约束），日期一律由 dateFor 派生。
function dateForWeekday(dayOfWeek: number) {
  return dateFor(dayOfWeek)
}

test('场景 2：教师提交代课 → 教务审批生效 → 统计与课表叠加', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  await solveAndPublish(api, seed)

  const entries = await classEntries(api, seed.classId)
  const entry = entries.find((item) => item.dayOfWeek != null)
  expect(entry).toBeTruthy()
  const effectiveDate = dateForWeekday(entry!.dayOfWeek)

  await uiLogin(page, seed.username, seed.password)
  await page.goto('/substitution/requests')
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

  await fillDateInput(dateInput, effectiveDate)

  await pickFirstVisibleOption(
    page.locator('.el-form-item', { hasText: '我的课节' }).locator('.el-select'),
  )

  // 提交时必须指定代课教师（后端要求），选中另一位教师；提交后停在待审批。
  await pickFirstVisibleOption(
    page.locator('.el-form-item', { hasText: '期望代课' }).locator('.el-select'),
  )

  await page.getByPlaceholder('如：家中有事，需要请假半天').fill('家中有事，需要请假')
  await page.getByRole('button', { name: '提交申请' }).click()
  await expect(page.getByText('申请已提交，等待教务审批')).toBeVisible()
  await expect(page.locator('.el-table').getByText('待审批').first()).toBeVisible()

  // 票 05：自助提交不即时生效；走真实审批接口后再断言原有业务结果。
  const teacherToken = await apiLogin(api, seed.username, seed.password)
  const mine = await apiCall(api, teacherToken, 'get', '/substitution-requests?mine=true')
  const request = mine.find(
    (item: { entryId: number; effectiveDate: string; status: string }) =>
      item.entryId === entry!.id
      && item.effectiveDate === effectiveDate
      && item.status === 'SUBMITTED',
  )
  expect(request).toBeTruthy()
  const token = await apiLogin(api)
  const approved = await apiCall(
    api,
    token,
    'post',
    `/substitution-requests/${request.id}/approve`,
    {},
  )
  expect(approved.status).toBe('EFFECTIVE')

  // A cover is counted separately from a swap: the original teacher was covered
  // once, and is not credited with any swap participation.
  const stats = await apiCall(api, token, 'get', `/substitutions/stats?teacherId=${seed.teacherId}`)
  expect(stats.substitutedOut).toBe(1)
  expect(stats.swapParticipation).toBe(0)

  // 管理员视角的调代记录里这一单在审批后标「已生效」。
  await page.evaluate(() => localStorage.clear())
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitution/requests')
  await page.getByRole('tab', { name: '调代记录' }).click()
  await expect(page.getByText('已生效').first()).toBeVisible()

  // 一次性调代只影响所指授课日期（ADR-0030）。
  // 申请者（被代教师）视角：授课日这节课被代课人接走，课表少一节；
  // 下周同一槽位恢复常规，课回到课表且全程不出现「代」标记。
  await page.evaluate(() => localStorage.clear())
  await uiLogin(page, seed.username, seed.password)
  await page.goto('/workbench/timetable')
  await page.getByPlaceholder('选择日期').fill(effectiveDate)
  await page.getByPlaceholder('选择日期').press('Enter')
  // The seeded teacher owns the baseline entries; one is covered on this week.
  // A zero-tag assertion also passes on the previous week, so wait for the
  // actual expected rows before reading a count used by the next-week check.
  await expect(page.locator('.lesson')).toHaveCount(entries.length - 1)
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
  await page.goto('/workbench/timetable')
  await page.getByPlaceholder('选择日期').fill(effectiveDate)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' }).first()).toBeVisible()
  await page.getByPlaceholder('选择日期').fill(nextWeek)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(0)
})
