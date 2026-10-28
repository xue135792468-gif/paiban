import { expect, test, type Page } from '@playwright/test'
import { newContext, pickOption, seedWorkbenchScenario, uiLogin, type WorkbenchSeed } from './helpers'

/**
 * 票 03：管理员调代工作台全流程。管理员不绑定教师身份：
 * 搜索教师 → 实际日期课表 → 指定代课 → 左右对照与变化清单 → 提交审批 / 亲自确认。
 */

async function pickTeacher(page: Page, seed: WorkbenchSeed) {
  // filterable 下拉：输入带后缀的唯一教师名，避免全库同名/其他用例教师干扰。
  await pickOption(page.locator('.picker-card'), 'teacher-select', seed.teacherName)
  await expect(page.getByTestId('workbench-timetable')).toBeVisible({ timeout: 20_000 })
}

async function fillLessonDate(page: Page, seed: WorkbenchSeed) {
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()
}

test('管理员亲自确认：选教师 → 指定代课 → 对照清单 → 课表生效', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await pickTeacher(page, seed)
  await fillLessonDate(page, seed)

  // 选一节课发起代课：先选「指定代课」动作，候选列表排除当前教师、只含启用教师。
  await page.getByTestId('pick-lesson').first().click()
  await page.getByTestId('choose-cover').click()
  await expect(page.getByTestId('substitute-picker')).toBeVisible()
  await expect(page.getByTestId('candidate-table')).toBeVisible()
  // 并行 spec 共享同一后端库：按种子教师精确选择，不用第一行（可能命中别例数据）。
  await page.getByTestId(`choose-${seed.substituteTeacherId}`).click()

  await page.getByTestId('preview-reason').fill('管理员代发：任课教师临时有事')
  await page.getByTestId('generate-preview').click()

  // 左右对照 + 完整变化清单：左 = 原教师离开，右 = 代课人接替。
  await expect(page.getByTestId('compare-panels')).toBeVisible()
  await expect(page.getByTestId('left-grid')).toBeVisible()
  await expect(page.getByTestId('right-grid')).toBeVisible()
  await expect(page.getByTestId('change-list')).toBeVisible()
  await expect(page.locator('[data-testid="change-list"] .leave')).toContainText(seed.teacherName)
  await expect(page.locator('[data-testid="change-list"] .take-over')).toContainText(
    seed.substituteTeacherName,
  )

  // 亲自确认：走普通批准路径，课表随之生效。
  await page.getByTestId('personal-confirm').click()
  await expect(page.getByTestId('workbench-result')).toContainText('亲自确认已生效')

  // ADR-0030：按最终有效教师过滤——被代课后原教师当天的这节课不再出现，
  // 所选日期（周三）的可操作课节随之清空。
  await expect(page.getByTestId('pick-lesson')).toHaveCount(0)
})

test('管理员提交审批：进入待批队列，旧安排继续生效', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await pickTeacher(page, seed)
  await fillLessonDate(page, seed)

  await page.getByTestId('pick-lesson').first().click()
  await page.getByTestId('choose-cover').click()
  await page.getByTestId(`choose-${seed.substituteTeacherId}`).click()
  await page.getByTestId('preview-reason').fill('先走审批流')
  await page.getByTestId('generate-preview').click()
  await expect(page.getByTestId('compare-panels')).toBeVisible()

  await page.getByTestId('submit-approval').click()
  await expect(page.getByTestId('workbench-result')).toContainText('已提交审批')

  // 待批期间旧安排继续生效：所选日期的课仍在原教师名下，可继续操作。
  await expect(page.getByTestId('pick-lesson')).toHaveCount(1)

  // 申请进了待审批队列，原因可定位到这一单。
  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '待审批' }).click()
  await expect(page.getByText('先走审批流').first()).toBeVisible()
})

test('教师账号打开工作台被引导回自助入口', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)
  await uiLogin(page, seed.username, seed.password)
  await page.goto('/substitutions/workbench')
  // 缺全校课表查看与审批权限：页面按权限把关；「调代课」自助入口照常可用。
  await expect(page.getByTestId('workbench-forbidden')).toBeVisible()
})
