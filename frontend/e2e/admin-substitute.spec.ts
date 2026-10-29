import { expect, test, type Page } from '@playwright/test'
import {
  newContext,
  pickOption,
  seedWorkbenchScenario,
  sourceCard,
  uiLogin,
  type WorkbenchSeed,
} from './helpers'

/**
 * 票 03：管理员调代工作台全流程（三步向导版）。
 * 管理员不绑定教师身份：搜索教师 → 实际日期课表 → 选课 → 代课候选 →
 * 第③步变化格/文字明细对照（旧「左右对照」的新呈现）→ 提交审批 / 亲自确认。
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

/** 旧「候选表 choose-{id} + generate-preview」的新路径：选课 → 下一步 → radio 选候选 → 下一步。 */
async function pickSubstituteAndPreview(page: Page, seed: WorkbenchSeed, reason: string) {
  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  await expect(page.getByTestId('substitute-picker')).toBeVisible()
  // 并行 spec 共享同一后端库：按种子教师精确选择，不用第一行（可能命中别例数据）。
  await page.getByTestId(`substitute-option-${seed.substituteTeacherId}`).locator('input').check()
  await page.getByTestId('preview-reason').fill(reason)
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()
}

test('管理员亲自确认：选教师 → 指定代课 → 对照清单 → 课表生效', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await pickTeacher(page, seed)
  await fillLessonDate(page, seed)

  // 选一节课发起代课：候选列表排除当前教师、只含启用教师。
  await pickSubstituteAndPreview(page, seed, '管理员代发：任课教师临时有事')

  // 第③步对照（旧左右对照的新呈现）：变化格旧师删除线 → 新师绿色，
  // 折叠明细里同一组新旧值（上课老师：原来 → 改后）。
  await expect(page.getByTestId('change-visual')).toBeVisible()
  await expect(page.getByTestId('change-cell-1').locator('.old')).toContainText(seed.teacherName)
  await expect(page.getByTestId('change-cell-1').locator('.new')).toContainText(
    seed.substituteTeacherName,
  )
  await page.getByTestId('confirm-details').locator('summary').click()
  const teacherRow = page.getByTestId('change-diff').locator('.drow', { hasText: '上课老师' })
  await expect(teacherRow.locator('.old')).toContainText(seed.teacherName)
  await expect(teacherRow.locator('.new')).toContainText(seed.substituteTeacherName)

  // 种子代课人未标注学科：跨科门出现，勾选后两个提交按钮才解锁。
  await expect(page.getByTestId('cross-subject-callout')).toBeVisible()
  await page.getByTestId('cross-subject-ack').check()
  await expect(page.getByTestId('personal-confirm')).toBeEnabled()

  // 亲自确认：走普通批准路径，课表随之生效。
  await page.getByTestId('personal-confirm').click()
  await expect(page.getByTestId('workbench-result')).toContainText('已生效')
  await expect(page.getByTestId('workbench-result')).toContainText('课表已更新')

  // ADR-0030：按最终有效教师过滤——被代课后原教师这节课不再出现。
  // 「再调整一节课」回步骤 1 并重拉课表；周网格里周三第 1 节的课卡随之消失。
  await page.getByTestId('wizard-again').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(sourceCard(page, seed.lessonDate)).toHaveCount(0)
})

test('管理员提交审批：进入待批队列，旧安排继续生效', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await pickTeacher(page, seed)
  await fillLessonDate(page, seed)

  await pickSubstituteAndPreview(page, seed, '先走审批流')
  await expect(page.getByTestId('change-visual')).toBeVisible()
  await page.getByTestId('cross-subject-ack').check()

  await page.getByTestId('submit-approval').click()
  await expect(page.getByTestId('workbench-result')).toContainText('已提交审批')
  await expect(page.getByTestId('workbench-result')).toContainText(
    '申请已进入待批队列，审批通过前课表维持原样',
  )

  // 待批期间旧安排继续生效：回步骤 1 后周三这节课仍在原教师名下，可继续操作。
  await page.getByTestId('wizard-again').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(sourceCard(page, seed.lessonDate)).toContainText(seed.teacherName)

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
