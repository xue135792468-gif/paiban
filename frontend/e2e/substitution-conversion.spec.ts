import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  classEntries,
  dateFor,
  newContext,
  runSql,
  seedSchool,
  solveAndPublish,
  uiLogin,
} from './helpers'

function dateForWeekday(dayOfWeek: number) {
  return dateFor(dayOfWeek)
}

function plusDays(date: string, days: number) {
  const next = new Date(`${date}T00:00:00`)
  next.setDate(next.getDate() + days)
  const year = next.getFullYear()
  const month = String(next.getMonth() + 1).padStart(2, '0')
  const day = String(next.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

async function seedLegacyCover(api: APIRequestContext) {
  const seed = await seedSchool(api)
  await solveAndPublish(api, seed)
  const entries = await classEntries(api, seed.classId)
  const entry = entries.find((item) => item.dayOfWeek != null)
  if (!entry) throw new Error('published timetable has no entries')

  const token = await apiLogin(api)
  const request = await apiCall(api, token, 'post', '/substitution-requests', {
    entryId: entry.id,
    effectiveDate: dateForWeekday(entry.dayOfWeek),
    reason: '历史代课种子',
    expectedSubstituteTeacherId: seed.substituteTeacherId,
  })
  await apiCall(api, token, 'post', `/substitution-requests/${request.id}/approve`, {
    substituteTeacherId: seed.substituteTeacherId,
  })
  // 票 01 之前的存量行没有实例日期：抹掉后即是旧格式（持续覆盖语义）
  await runSql(api, 'update substitution set lesson_date = null, target_lesson_date = null')
  return { seed, effectiveDate: dateForWeekday(entry.dayOfWeek) }
}

test('历史转换：未确认时无切换，确认后课表按实际授课日期呈现', async ({ page }) => {
  const api = await newContext()
  const { effectiveDate } = await seedLegacyCover(api)
  const nextWeek = plusDays(effectiveDate, 7)

  // 未确认：授课日出现「代」，下周同一槽位仍被旧格式行持续覆盖（旧语义照旧）。
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/timetable')
  await page.getByPlaceholder('选择日期').fill(effectiveDate)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(1)
  await page.getByPlaceholder('选择日期').fill(nextWeek)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(1)

  // 预检：一条可转换记录，阻塞为 0；确认门放行。
  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '历史转换' }).click()
  await expect(page.getByText('未通过数据确认门前不会切换历史解释')).toBeVisible()
  await page.getByRole('button', { name: '生成预检报告' }).click()
  await expect(page.getByText('可转换 1')).toBeVisible()
  await expect(page.getByText('阻塞 0')).toBeVisible()
  // 展开该记录的日期差异清单：转换前是代课教师，转换后恢复常规安排。
  const panel = page.locator('.conversion')
  await panel.locator('.el-table__expand-icon').first().click()
  await expect(page.getByText('恢复常规安排').first()).toBeVisible()

  // 数据确认门：经教务确认后转换，状态切到「已全部按实际授课日期解释」。
  await page.getByRole('button', { name: '确认转换' }).click()
  await expect(page.getByText(/转换完成/)).toBeVisible()
  await expect(page.getByText('历史调代记录已全部按实际授课日期解释')).toBeVisible()
  await expect(page.getByText('已转换')).toBeVisible()

  // 确认后：授课日仍是「代」，下周恢复常规安排（新日期结果）。
  await page.goto('/timetable')
  await page.getByPlaceholder('选择日期').fill(effectiveDate)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(1)
  await page.getByPlaceholder('选择日期').fill(nextWeek)
  await page.getByPlaceholder('选择日期').press('Enter')
  await expect(page.locator('.lesson .el-tag', { hasText: '代' })).toHaveCount(0)
})
