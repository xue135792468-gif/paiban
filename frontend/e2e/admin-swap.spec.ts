import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSwapWorkbenchScenario, uiLogin } from './helpers'

/**
 * 票 05：管理员同班调课。管理员在工作台为实际授课实例明确指定目标日期与课节，
 * 生成闭合置换环候选（≤3），检查整环左右对照与变化清单后提交审批或亲自确认。
 */

async function openSwapFlow(
  page: import('@playwright/test').Page,
  seed: Awaited<ReturnType<typeof seedSwapWorkbenchScenario>>,
) {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  // filterable 下拉：输入带后缀的唯一教师名定位来源课教师。
  await page.getByTestId('teacher-select').click()
  await page.getByTestId('teacher-select').locator('input').fill(seed.teacherChineseName)
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(dropdown.getByText(seed.teacherChineseName, { exact: true })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('workbench-timetable')).toBeVisible({ timeout: 20_000 })

  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()

  // 选来源课 → 选择「调课」动作（区别于票 03 的「指定代课」）。
  await page.getByTestId('pick-lesson').first().click()
  await page.getByTestId('choose-swap').click()
  await expect(page.getByTestId('swap-planner')).toBeVisible()
}

async function pickTargetAndGenerate(
  page: import('@playwright/test').Page,
  seed: Awaited<ReturnType<typeof seedSwapWorkbenchScenario>>,
) {
  // 明确指定目标日期（周五）与目标课节（数学）——不会被替换成同星期的另一周。
  const targetDateInput = page.getByTestId('swap-target-date').locator('input')
  await targetDateInput.fill(seed.targetDate)
  await targetDateInput.press('Tab')
  await expect(page.getByTestId('swap-target-period')).toBeEnabled()
  await page.getByTestId('swap-target-period').click()
  const targetDropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(targetDropdown.locator('.el-select-dropdown__item').first()).toBeVisible()
  await targetDropdown
    .locator('.el-select-dropdown__item')
    .filter({ hasText: '数学' })
    .first()
    .click()

  await page.getByTestId('swap-reason').fill('运动会调课')
  await page.getByTestId('generate-swap-plans').click()
}

test('管理员调课：明确目标日期生成二人环 → 整环对照 → 提交审批后旧课表继续', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapWorkbenchScenario(api)

  await openSwapFlow(page, seed)
  await pickTargetAndGenerate(page, seed)

  // 候选：二人环、影响 2 天（跨日）。
  await expect(page.getByTestId('swap-plan-card').first()).toBeVisible()
  await expect(page.getByTestId('swap-plan-card').first()).toContainText('2 人环')
  await expect(page.getByTestId('swap-plan-card').first()).toContainText('影响 2 天')

  // 选定候选 → 整环左右对照：来源日与目标日各一块，变化清单完整两步。
  await page.getByTestId('choose-swap-plan-0').click()
  await expect(page.getByTestId('swap-compare')).toBeVisible()
  await expect(page.getByTestId(`swap-left-${seed.lessonDate}`)).toBeVisible()
  await expect(page.getByTestId(`swap-right-${seed.lessonDate}`)).toBeVisible()
  await expect(page.getByTestId(`swap-left-${seed.targetDate}`)).toBeVisible()
  await expect(page.getByTestId(`swap-right-${seed.targetDate}`)).toBeVisible()
  await expect(page.getByTestId('swap-change-list').locator('.el-table__row')).toHaveCount(2)
  await expect(page.getByTestId('swap-change-list')).toContainText(seed.lessonDate)
  await expect(page.getByTestId('swap-change-list')).toContainText(seed.targetDate)

  // 提交审批：进待批队列，旧课表继续生效。
  await page.getByTestId('swap-submit-approval').click()
  await expect(page.getByTestId('workbench-result')).toContainText('已提交审批')

  // API 侧不变量：有一笔待批 SWAP 申请，没有生效行。
  const token = await apiLogin(api)
  const pending = await apiCall(api, token, 'get', '/substitution-requests', undefined, {
    status: 'PENDING_APPROVAL',
  })
  expect(pending.length).toBe(1)
  expect(pending[0].type).toBe('SWAP')
  expect(pending[0].targetDate).toBe(seed.targetDate)
  const effective = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(effective.length).toBe(0)
})

test('管理员调课：亲自确认整环生效，课表按实际日期呈现', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapWorkbenchScenario(api)

  await openSwapFlow(page, seed)
  await pickTargetAndGenerate(page, seed)
  await page.getByTestId('choose-swap-plan-0').click()
  await expect(page.getByTestId('swap-compare')).toBeVisible()

  await page.getByTestId('swap-personal-confirm').click()
  await expect(page.getByTestId('workbench-result')).toContainText('亲自确认已生效')

  // 生效事实（API 侧）：两条 SWAP 生效行，实例日期成对（周三↔周五）。
  const token = await apiLogin(api)
  const effective = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(effective.length).toBe(2)
  const dates = effective
    .map((row: any) => `${row.lessonDate}|${row.toDayOfWeek}`)
    .sort()
  expect(dates).toEqual([`${seed.lessonDate}|5`, `${seed.targetDate}|3`].sort())

  // 班级课表（周五视角）：语文课出现在周五第 1 节并带「调」标记。
  const fridayEntries = await apiCall(
    api,
    token,
    'get',
    `/timetable/class/${seed.classId}`,
    undefined,
    { view: 'latest', date: seed.targetDate },
  )
  const chinese = fridayEntries.find((row: any) => row.courseName === seed.courseChineseName)
  expect(chinese).toBeTruthy()
  expect(chinese.dayOfWeek).toBe(5)
  expect(chinese.periodNo).toBe(1)
  expect(chinese.substituted).toBe(true)
})
