import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  pickOption,
  seedWorkbenchScenario,
  uiLogin,
  type WorkbenchSeed,
} from './helpers'

/**
 * 票 04：课前再次调整（三步向导版）。A→B 已生效后，管理员从当前代课人 B 的
 * 课表发起「改为 C」或「恢复基线教师 A」；新修订经审批前 B 继续执行，确认后
 * 原子切换；历史全保留。课表按最终有效教师过滤（ADR-0030），修订入口在 B 的课表上。
 */

async function coverOnce(context: APIRequestContext, seed: WorkbenchSeed): Promise<number> {
  // API 直做首代 A→B（票 03 已覆盖其 UI），本 spec 专注票 04 交互。
  const token = await apiLogin(context)
  const preview = await apiCall(context, token, 'post', '/substitution-workbench/previews', {
    entryId: seed.entryId,
    lessonDate: seed.lessonDate,
    substituteTeacherId: seed.substituteTeacherId,
    reason: '首代：任课教师临时有事',
  })
  const confirmed = await apiCall(
    context,
    token,
    'post',
    `/substitution-workbench/previews/${preview.voucherId}/confirm`,
    {},
  )
  return confirmed.requestId
}

/** 再建一位无课教师做「改为 C」的目标。 */
async function addThirdTeacher(context: APIRequestContext, seed: WorkbenchSeed) {
  const token = await apiLogin(context)
  return apiCall(context, token, 'post', '/teachers', {
    name: `王老师${seed.username.slice(-6)}`,
    employeeNo: `W3-${seed.username.slice(-6)}`,
    subject: '语文',
  })
}

async function openLessonOfCurrentSubstitute(page: Page, seed: WorkbenchSeed) {
  // 从当前代课人 B 的课表进入：这节课此刻由 B 实际承担。
  // B 名下只有被代的那一节（日期在种子周）：先跳日期再等课卡（新界面课表为空时
  // 不渲染网格，只显示空态）。
  await pickOption(page.locator('.picker-card'), 'teacher-select', seed.substituteTeacherName)
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible({ timeout: 20_000 })
  await page.getByTestId('pick-lesson').first().click()
  // 票 05 在选中课节后加了动作选择：修订链仍走「换老师上（代课）」方向。
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  await expect(page.getByTestId('substitute-picker')).toBeVisible()
}

test('查看当前代课人 B 后改为 C：待批期间 B 生效，批准后 C 接管', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)
  const baseRequestId = await coverOnce(api, seed)
  const third = await addThirdTeacher(api, seed)
  const token = await apiLogin(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await openLessonOfCurrentSubstitute(page, seed)

  // 候选排除当前教师 B；精确选第三位教师 C 发起修订。
  await page.getByTestId(`substitute-option-${third.id}`).locator('input').check()
  await page.getByTestId('preview-reason').fill('课前再次调整：换人')
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()

  // 修订横幅：标明这是对被取代申请的再次调整；变化格 B → C（旧师删除线 → 新师绿色）。
  await expect(page.getByTestId('revision-banner')).toContainText('再次调整')
  await expect(page.getByTestId('revision-banner')).toContainText(`#${baseRequestId}`)
  await expect(page.getByTestId('change-cell-1').locator('.old')).toContainText(
    seed.substituteTeacherName,
  )
  await expect(page.getByTestId('change-cell-1').locator('.new')).toContainText(third.name)

  await page.getByTestId('submit-approval').click()
  await expect(page.getByTestId('workbench-result')).toContainText('已提交审批')

  // 待批期间旧安排（B）继续生效：跳到种子周后 B 的课表仍有这节课，
  // 接口确认当前教师仍为 B。
  await page.goto('/substitutions/workbench')
  await pickOption(page.locator('.picker-card'), 'teacher-select', seed.substituteTeacherName)
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible({ timeout: 20_000 })
  const lessons = await apiCall(api, token, 'get', `/timetable/teacher/${seed.substituteTeacherId}`, undefined, {
    view: 'latest',
    date: seed.lessonDate,
  })
  expect(lessons).toHaveLength(1)

  // 审批状态：待审批队列里可以按原因定位到这一单。
  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '待审批' }).click()
  await expect(page.getByText('课前再次调整：换人').first()).toBeVisible()
})

test('从 B 的课表恢复基线教师 A：确认后 A 回归、B 释放，历史可追溯', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)
  const baseRequestId = await coverOnce(api, seed)
  const token = await apiLogin(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await openLessonOfCurrentSubstitute(page, seed)

  // 恢复基线入口（第②步候选面板）：直接对基线原教师发起一次经预览、校验的再次调整。
  await page.getByTestId('restore-baseline').click()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()
  await expect(page.getByTestId('revision-banner')).toContainText('再次调整')
  await expect(page.getByTestId('revision-banner')).toContainText(`#${baseRequestId}`)

  await page.getByTestId('personal-confirm').click()
  await expect(page.getByTestId('workbench-result')).toContainText('已生效')

  // 当前有效结果：B 的课表清空（被解除）。「再调整一节课」回步骤 1 重拉课表，
  // B 本周已没有任何可操作的课卡。
  await page.getByTestId('wizard-again').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(page.getByTestId('pick-lesson')).toHaveCount(0)
  const released = await apiCall(api, token, 'get', `/timetable/teacher/${seed.substituteTeacherId}`, undefined, {
    view: 'latest',
    date: seed.lessonDate,
  })
  expect(released).toHaveLength(0)
  const restored = await apiCall(api, token, 'get', `/timetable/teacher/${seed.teacherId}`, undefined, {
    view: 'latest',
    date: seed.lessonDate,
  })
  // 种子教师每周有多节课；按授课实例精确断言：周三这节回到 A 名下（恢复行仍标记 substituted）。
  const restoredLesson = restored.find(
    (row: any) => String(row.id) === String(seed.entryId),
  )
  expect(restoredLesson).toBeTruthy()
  expect(restoredLesson.teacherId).toBe(seed.teacherId)
  expect(restoredLesson.substituted).toBe(true)
  expect(String(restoredLesson.lessonDate)).toBe(seed.lessonDate)
})
