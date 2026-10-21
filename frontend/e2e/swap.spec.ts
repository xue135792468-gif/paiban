import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { API, apiCall, apiLogin, newContext, uiLogin } from './helpers'

/**
 * Manual swap coverage. The seed is deliberately separate from `seedSchool`:
 * swapping needs two classes, two same-subject teachers, two same-subject
 * courses and one extra different-subject course for the rejection case.
 */
interface SwapSeed {
  semesterId: number
  classAId: number
  classBId: number
  classAName: string
  classBName: string
  mathAId: number
  mathBId: number
  chineseAId: number
  mathAName: string
  mathBName: string
  chineseAName: string
  teacherAId: number
  teacherBId: number
  teacherCId: number
  teacherAName: string
  teacherBName: string
  teacherCName: string
}

interface DraftLessonView {
  id: number
  courseId: number
  courseName: string
  teacherId: number
  teacherName: string
  classId: number
  className: string
  periodId: number | null
  dayOfWeek: number | null
  periodNo: number | null
}

interface DraftDetail {
  lessons: DraftLessonView[]
  periods: Array<{ id: number; dayOfWeek: number; periodNo: number }>
}

interface GridLayout {
  days: number[]
  periodNos: number[]
}

async function seedSwapSchool(api: APIRequestContext): Promise<SwapSeed> {
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)

  const semester = await apiCall(api, token, 'post', '/semesters', {
    name: `换课学期-${suffix}`,
    startDate: '2025-09-01',
    endDate: '2026-01-20',
  })
  await apiCall(api, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(api, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 4,
    afternoonCount: 4,
    eveningCount: 0,
    days: [1, 2, 3, 4, 5],
  })

  const grade = await apiCall(api, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `高一-${suffix}`,
    sortOrder: 1,
  })

  const teacherAName = `张老师-${suffix}`
  const teacherBName = `李老师-${suffix}`
  const teacherCName = `王老师-${suffix}`
  const teacherA = await apiCall(api, token, 'post', '/teachers', {
    name: teacherAName,
    employeeNo: `T1-${suffix}`,
    subject: '数学',
  })
  const teacherB = await apiCall(api, token, 'post', '/teachers', {
    name: teacherBName,
    employeeNo: `T2-${suffix}`,
    subject: '数学',
  })
  const teacherC = await apiCall(api, token, 'post', '/teachers', {
    name: teacherCName,
    employeeNo: `T3-${suffix}`,
    subject: '语文',
  })

  const classAName = `高一1班-${suffix}`
  const classBName = `高一2班-${suffix}`
  const classA = await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: classAName,
    studentCount: 45,
  })
  const classB = await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: classBName,
    studentCount: 44,
  })

  const mathAName = `数学A-${suffix}`
  const mathBName = `数学B-${suffix}`
  const chineseAName = `语文A-${suffix}`
  const mathA = await apiCall(api, token, 'post', '/courses', {
    name: mathAName,
    subject: '数学',
    allowConsecutive: false,
  })
  const mathB = await apiCall(api, token, 'post', '/courses', {
    name: mathBName,
    subject: '数学',
    allowConsecutive: false,
  })
  const chineseA = await apiCall(api, token, 'post', '/courses', {
    name: chineseAName,
    subject: '语文',
    allowConsecutive: false,
  })

  await apiCall(api, token, 'post', '/rooms', {
    name: `教室-${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })

  await apiCall(api, token, 'post', '/assignments', {
    classId: classA.id,
    courseId: mathA.id,
    teacherId: teacherA.id,
    weeklyLessons: 1,
  })
  await apiCall(api, token, 'post', '/assignments', {
    classId: classB.id,
    courseId: mathB.id,
    teacherId: teacherB.id,
    weeklyLessons: 1,
  })
  await apiCall(api, token, 'post', '/assignments', {
    classId: classA.id,
    courseId: chineseA.id,
    teacherId: teacherC.id,
    weeklyLessons: 1,
  })

  return {
    semesterId: semester.id,
    classAId: classA.id,
    classBId: classB.id,
    classAName,
    classBName,
    mathAId: mathA.id,
    mathBId: mathB.id,
    chineseAId: chineseA.id,
    mathAName,
    mathBName,
    chineseAName,
    teacherAId: teacherA.id,
    teacherBId: teacherB.id,
    teacherCId: teacherC.id,
    teacherAName,
    teacherBName,
    teacherCName,
  }
}

async function solveDraft(api: APIRequestContext, semesterId: number): Promise<number> {
  const token = await apiLogin(api)
  const job = await apiCall(api, token, 'post', `/scheduling-jobs?semesterId=${semesterId}`, {
    maxSeconds: 5,
  })
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    const current = await apiCall(api, token, 'get', `/scheduling-jobs/${job.id}`)
    if (current.status === 'COMPLETED') return current.draftId as number
    if (current.status === 'FAILED' || current.status === 'CANCELLED') {
      throw new Error(`scheduling failed: ${current.errorMessage}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error('scheduling timed out')
}

async function draftDetail(api: APIRequestContext, draftId: number): Promise<DraftDetail> {
  const token = await apiLogin(api)
  return apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
}

function gridLayout(detail: DraftDetail): GridLayout {
  return {
    days: [...new Set(detail.periods.map((period) => period.dayOfWeek))].sort((a, b) => a - b),
    periodNos: [...new Set(detail.periods.map((period) => period.periodNo))].sort((a, b) => a - b),
  }
}

function cellAt(cells: Locator, layout: GridLayout, day: number, periodNo: number): Locator {
  const row = layout.periodNos.indexOf(periodNo)
  const column = layout.days.indexOf(day)
  if (row < 0 || column < 0) {
    throw new Error(`layout has no cell for day ${day} period ${periodNo}`)
  }
  return cells.nth(row * layout.days.length + column)
}

function lessonOf(detail: DraftDetail, classId: number, courseId: number): DraftLessonView {
  const lesson = detail.lessons.find(
    (item) => item.classId === classId && item.courseId === courseId,
  )
  if (!lesson) {
    throw new Error(`lesson not found for class ${classId} course ${courseId}`)
  }
  if (lesson.periodId == null || lesson.dayOfWeek == null || lesson.periodNo == null) {
    throw new Error(`lesson ${lesson.id} was left unscheduled by the solver`)
  }
  return lesson
}

async function openDraft(page: Page, draftId: number): Promise<void> {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/admin/drafts/${draftId}`)
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
}

async function selectClass(page: Page, className: string): Promise<void> {
  const select = page.locator('.controls .el-select')
  await select.click()
  await page
    .locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: className })
    .first()
    .click()
  await expect(select).toContainText(className)
}

async function openSwapPanel(page: Page, layout: GridLayout, lesson: DraftLessonView) {
  const cells = page.locator('.grid-card .cell')
  await cellAt(cells, layout, lesson.dayOfWeek!, lesson.periodNo!).click()
  await expect(page.locator('.controls .el-tag')).toContainText(lesson.courseName)
  await page.getByRole('button', { name: '换课', exact: true }).click()
  const panel = page.getByTestId('swap-candidate-panel')
  await expect(panel).toBeVisible()
  return panel
}

function waitForDraftReload(page: Page, draftId: number) {
  return page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().endsWith(`/api/timetable-drafts/${draftId}`),
  )
}

test('场景 2a：同班换位交换两节课的节次，撤销后逐格恢复', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)
  const detail = await draftDetail(api, draftId)
  const layout = gridLayout(detail)
  const math = lessonOf(detail, seed.classAId, seed.mathAId)
  const chinese = lessonOf(detail, seed.classAId, seed.chineseAId)

  await openDraft(page, draftId)
  await selectClass(page, seed.classAName)
  const cells = page.locator('.grid-card .cell')
  const before = await cells.allInnerTexts()

  await expect(cellAt(cells, layout, math.dayOfWeek!, math.periodNo!)).toContainText(
    seed.mathAName,
  )
  await expect(cellAt(cells, layout, chinese.dayOfWeek!, chinese.periodNo!)).toContainText(
    seed.chineseAName,
  )

  const panel = await openSwapPanel(page, layout, math)
  const candidate = panel.locator('.candidate').filter({ hasText: seed.chineseAName })
  await expect(candidate).toHaveCount(1)
  await candidate.click()

  const preview = page.getByTestId('dual-timetable-preview')
  await expect(preview).toBeVisible()
  await expect(preview.getByText('同班换位', { exact: true })).toBeVisible()
  await preview.getByText('调整后', { exact: true }).click()

  const leftPane = preview.locator('.pane').nth(0)
  const rightPane = preview.locator('.pane').nth(1)
  await expect(
    cellAt(leftPane.locator('.cell'), layout, chinese.dayOfWeek!, chinese.periodNo!),
  ).toContainText(seed.mathAName)
  await expect(
    cellAt(rightPane.locator('.cell'), layout, math.dayOfWeek!, math.periodNo!),
  ).toContainText(seed.chineseAName)

  const reload = waitForDraftReload(page, draftId)
  await page.getByRole('button', { name: '确认换课' }).click()
  await reload

  await expect(cellAt(cells, layout, chinese.dayOfWeek!, chinese.periodNo!)).toContainText(
    seed.mathAName,
  )
  await expect(cellAt(cells, layout, chinese.dayOfWeek!, chinese.periodNo!)).toContainText(
    seed.teacherAName,
  )
  await expect(cellAt(cells, layout, math.dayOfWeek!, math.periodNo!)).toContainText(
    seed.chineseAName,
  )
  await expect(cellAt(cells, layout, math.dayOfWeek!, math.periodNo!)).toContainText(
    seed.teacherCName,
  )

  const undo = page.getByRole('button', { name: /^撤销/ })
  await expect(undo).toBeEnabled()
  const undoReload = waitForDraftReload(page, draftId)
  await undo.click()
  await undoReload

  await expect.poll(async () => cells.allInnerTexts()).toEqual(before)
  await expect(undo).toBeDisabled()
})

test('场景 2b：跨班换人保持节次与课程，只交换任课教师，撤销后恢复', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)
  const detail = await draftDetail(api, draftId)
  const layout = gridLayout(detail)
  const mathA = lessonOf(detail, seed.classAId, seed.mathAId)
  const mathB = lessonOf(detail, seed.classBId, seed.mathBId)

  await openDraft(page, draftId)
  const cells = page.locator('.grid-card .cell')

  await selectClass(page, seed.classAName)
  const beforeA = await cells.allInnerTexts()
  await selectClass(page, seed.classBName)
  const beforeB = await cells.allInnerTexts()
  await selectClass(page, seed.classAName)

  const panel = await openSwapPanel(page, layout, mathA)
  const candidate = panel.locator('.candidate').filter({ hasText: seed.mathBName })
  await expect(candidate).toHaveCount(1)
  await candidate.click()

  const preview = page.getByTestId('dual-timetable-preview')
  await expect(preview).toBeVisible()
  await expect(preview.getByText('跨班换人', { exact: true })).toBeVisible()
  await preview.getByText('调整后', { exact: true }).click()

  const leftPane = preview.locator('.pane').nth(0)
  const rightPane = preview.locator('.pane').nth(1)
  await expect(
    cellAt(leftPane.locator('.cell'), layout, mathB.dayOfWeek!, mathB.periodNo!),
  ).toContainText(seed.mathBName)
  await expect(
    cellAt(rightPane.locator('.cell'), layout, mathA.dayOfWeek!, mathA.periodNo!),
  ).toContainText(seed.mathAName)

  const reload = waitForDraftReload(page, draftId)
  await page.getByRole('button', { name: '确认换课' }).click()
  await reload

  await expect(cellAt(cells, layout, mathA.dayOfWeek!, mathA.periodNo!)).toContainText(
    seed.mathAName,
  )
  await expect(cellAt(cells, layout, mathA.dayOfWeek!, mathA.periodNo!)).toContainText(
    seed.teacherBName,
  )

  await selectClass(page, seed.classBName)
  await expect(cellAt(cells, layout, mathB.dayOfWeek!, mathB.periodNo!)).toContainText(
    seed.mathBName,
  )
  await expect(cellAt(cells, layout, mathB.dayOfWeek!, mathB.periodNo!)).toContainText(
    seed.teacherAName,
  )

  const undo = page.getByRole('button', { name: /^撤销/ })
  await expect(undo).toBeEnabled()
  const undoReload = waitForDraftReload(page, draftId)
  await undo.click()
  await undoReload

  await expect.poll(async () => cells.allInnerTexts()).toEqual(beforeB)
  await selectClass(page, seed.classAName)
  await expect.poll(async () => cells.allInnerTexts()).toEqual(beforeA)
})

test('场景 2c：不同科目的跨班换课被服务端拒绝且原因可读', async () => {
  const api = await newContext()
  const seed = await seedSwapSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)
  const detail = await draftDetail(api, draftId)
  const chinese = lessonOf(detail, seed.classAId, seed.chineseAId)
  const mathB = lessonOf(detail, seed.classBId, seed.mathBId)

  const token = await apiLogin(api)
  const result = await apiCall(api, token, 'post', `/timetable-drafts/${draftId}/swap`, {
    lessonId: chinese.id,
    targetLessonId: mathB.id,
    swapRooms: false,
  })

  expect(result.saved).toBe(false)
  expect(result.valid).toBe(false)
  expect(result.hardConflicts).toBeGreaterThan(0)
  expect(result.type).toBe('CROSS_CLASS')
  const messages = result.conflicts.map((conflict: { message: string }) => conflict.message).join('；')
  expect(messages).toContain('跨班换人要求两门课程同科目')
  expect(messages).toContain(seed.chineseAName)
  expect(messages).toContain(seed.mathBName)

  const after = await draftDetail(api, draftId)
  const chineseAfter = after.lessons.find((lesson) => lesson.id === chinese.id)
  const mathBAfter = after.lessons.find((lesson) => lesson.id === mathB.id)
  expect(chineseAfter?.teacherId).toBe(chinese.teacherId)
  expect(chineseAfter?.periodId).toBe(chinese.periodId)
  expect(mathBAfter?.teacherId).toBe(mathB.teacherId)
  expect(mathBAfter?.periodId).toBe(mathB.periodId)
})

test('场景 2d：已发布课表的换课入口不可用', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)
  const detail = await draftDetail(api, draftId)
  const layout = gridLayout(detail)
  const math = lessonOf(detail, seed.classAId, seed.mathAId)

  const token = await apiLogin(api)
  await apiCall(api, token, 'post', `/timetable-drafts/${draftId}/publish`)

  await openDraft(page, draftId)
  await expect(page.locator('.toolbar .el-tag').first()).toContainText('已发布')
  await selectClass(page, seed.classAName)

  const cells = page.locator('.grid-card .cell')
  await cellAt(cells, layout, math.dayOfWeek!, math.periodNo!).click()
  await expect(page.locator('.controls .el-tag')).toContainText(math.courseName)
  await expect(page.getByRole('button', { name: '换课', exact: true })).toBeDisabled()

  const chinese = lessonOf(detail, seed.classAId, seed.chineseAId)
  const swapResponse = await api.post(`${API}/timetable-drafts/${draftId}/swap`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { lessonId: math.id, targetLessonId: chinese.id, swapRooms: false },
  })
  expect(swapResponse.status()).toBe(400)
  expect((await swapResponse.json()).message).toContain('课表已发布')
})

