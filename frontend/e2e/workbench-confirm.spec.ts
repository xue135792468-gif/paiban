import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  cnDate,
  isoDate,
  newContext,
  openWorkbenchAtSourceLesson,
  publishDraft,
  shiftDate,
  solveDraft,
  sourceCard,
  weekdayOf,
} from './helpers'
import { capture } from './screenshots'

/**
 * 票 02：第③步（核对并提交）。
 * 覆盖：代课结论卡与可视化（提交审批课表不变 / 亲自确认课表生效）、跨科勾选门、
 * 同周调课（一张周表两格）、跨周调课（本周+下周两排同屏）、方案 A/B 页签切换、
 * 返回上一步零写入、409 错误条与重新生成、390px 按天列表无横向溢出、控制台零错误。
 */

interface ConfirmSeed {
  class1Id: number
  class1Name: string
  courseChineseName: string
  teacherZhangName: string
  sameSubjectFreeId: number
  sameSubjectFreeName: string
  crossSubjectFreeId: number
  crossSubjectFreeName: string
  lessonDate: string
  targetDate: string
  crossDate: string
  wednesdayEntryId: number
}

/**
 * 票 02 确认页种子（在票 01 向导种子上加料）：
 * 高一1班 周三第1节语文（张X，来源）、周四/周五第1节数学（钱X，二人环+三人环）、
 * 下周一第1节英语（赵X，跨周目标）。王X（语文）/李X（数学）无课，分别是同科与跨科候选。
 */
async function seedConfirmScenario(context: APIRequestContext): Promise<ConfirmSeed> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const monday = new Date()
  monday.setDate(monday.getDate() + ((8 - monday.getDay()) % 7 || 7))
  const at = (offset: number) => {
    const date = new Date(monday)
    date.setDate(monday.getDate() + offset)
    return isoDate(date)
  }
  const wednesday = at(2)
  const friday = at(4)
  const nextMonday = at(7)

  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E确认-${suffix}`,
    startDate: at(0),
    endDate: at(100),
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 2,
    afternoonCount: 0,
    eveningCount: 0,
    days: [1, 2, 3, 4, 5],
  })
  const periodRows = await apiCall(context, token, 'get', `/semesters/${semester.id}/periods`)
  const periods: Record<string, number> = {}
  for (const row of periodRows) {
    periods[`${row.dayOfWeek}-${row.periodNo}`] = row.id
  }

  const grade = await apiCall(context, token, 'post', '/grades?semesterId=' + semester.id, {
    name: `高一${suffix}`,
    sortOrder: 1,
  })
  const class1 = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一1班${suffix}`,
    studentCount: 45,
  })
  const teacherZhang = await apiCall(context, token, 'post', '/teachers', {
    name: `确认张${suffix}`,
    employeeNo: `QZ-${suffix}`,
    subject: '语文',
  })
  const teacherWang = await apiCall(context, token, 'post', '/teachers', {
    name: `确认王${suffix}`,
    employeeNo: `QW-${suffix}`,
    subject: '语文',
  })
  const teacherLi = await apiCall(context, token, 'post', '/teachers', {
    name: `确认李${suffix}`,
    employeeNo: `QL-${suffix}`,
    subject: '数学',
  })
  const teacherQian = await apiCall(context, token, 'post', '/teachers', {
    name: `确认钱${suffix}`,
    employeeNo: `QQ-${suffix}`,
    subject: '数学',
  })
  const teacherZhao = await apiCall(context, token, 'post', '/teachers', {
    name: `确认赵${suffix}`,
    employeeNo: `QV-${suffix}`,
    subject: '英语',
  })
  const chinese = await apiCall(context, token, 'post', '/courses', {
    name: `语文${suffix}`,
    subject: '语文',
    allowConsecutive: false,
  })
  const math = await apiCall(context, token, 'post', '/courses', {
    name: `数学${suffix}`,
    subject: '数学',
    allowConsecutive: false,
  })
  const english = await apiCall(context, token, 'post', '/courses', {
    name: `英语${suffix}`,
    subject: '英语',
    allowConsecutive: false,
  })
  const roomA = await apiCall(context, token, 'post', '/rooms', {
    name: `确认教室A${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  const roomB = await apiCall(context, token, 'post', '/rooms', {
    name: `确认教室B${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: chinese.id,
    teacherId: teacherZhang.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: math.id,
    teacherId: teacherQian.id,
    weeklyLessons: 2,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: english.id,
    teacherId: teacherZhao.id,
    weeklyLessons: 1,
  })

  const draftId = await solveDraft(context, semester.id)
  const detail = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const class1Lessons = detail.lessons.filter((row: any) => row.classId === class1.id)
  const byCourse = (courseId: number) => class1Lessons.filter((row: any) => row.courseId === courseId)
  if (byCourse(chinese.id).length !== 1 || byCourse(math.id).length !== 2 || byCourse(english.id).length !== 1) {
    throw new Error('unexpected draft lessons for the confirm seed')
  }
  const place = (lessonId: number, key: string, roomId: number) =>
    apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lessonId}`, {
      periodId: periods[key],
      roomId,
      force: true,
    })
  await place(byCourse(chinese.id)[0].id, '3-1', roomA.id)
  await place(byCourse(math.id)[0].id, '4-1', roomB.id)
  await place(byCourse(math.id)[1].id, '5-1', roomB.id)
  await place(byCourse(english.id)[0].id, '1-1', roomA.id)
  await publishDraft(context, draftId)

  const entries = await apiCall(context, token, 'get', `/timetable/class/${class1.id}`, undefined, {
    view: 'baseline',
  })
  const atSlot = (dayOfWeek: number, periodNo: number) =>
    entries.find((row: any) => row.dayOfWeek === dayOfWeek && row.periodNo === periodNo)
  const wednesdayEntry = atSlot(3, 1)
  const fridayEntry = atSlot(5, 1)
  if (!wednesdayEntry || !fridayEntry || !atSlot(1, 1)) {
    throw new Error('confirm seed missed Wednesday/Friday/next-Monday lessons')
  }

  return {
    class1Id: class1.id,
    class1Name: class1.name,
    courseChineseName: chinese.name,
    teacherZhangName: teacherZhang.name,
    sameSubjectFreeId: teacherWang.id,
    sameSubjectFreeName: teacherWang.name,
    crossSubjectFreeId: teacherLi.id,
    crossSubjectFreeName: teacherLi.name,
    lessonDate: wednesday,
    targetDate: friday,
    crossDate: nextMonday,
    wednesdayEntryId: wednesdayEntry.id,
  }
}

function mmdd(date: string): string {
  const [, month, day] = date.split('-')
  return `${month}/${day}`
}

/** 步骤 1 公共入口（helpers.openWorkbenchAtSourceLesson）：本 spec 的种子字段名不同，做一次适配。 */
function openConfirmWizard(page: Page, seed: ConfirmSeed): Promise<void> {
  return openWorkbenchAtSourceLesson(page, {
    teacherName: seed.teacherZhangName,
    lessonDate: seed.lessonDate,
  })
}

/** 走到第③步（代课，指定候选教师）；narrow 时周网格不存在，点列表首卡。 */
async function toCoverStep3(page: Page, seed: ConfirmSeed, candidateId: number, narrow = false): Promise<void> {
  await openConfirmWizard(page, seed)
  if (narrow) {
    await page.getByTestId('pick-lesson').first().click()
  } else {
    await sourceCard(page, seed.lessonDate).click()
  }
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  await page.getByTestId(`substitute-option-${candidateId}`).locator('input').check()
  await page.getByTestId('preview-reason').fill('临时有事')
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('wizard-panel-3')).toBeVisible()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()
}

/** 走到第③步（调课，指定目标日期；目标节次固定第 1 节）。 */
async function toSwapStep3(page: Page, seed: ConfirmSeed, targetDate: string): Promise<void> {
  await openConfirmWizard(page, seed)
  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-swap').click()
  await page.getByTestId(`swap-date-chip-${targetDate}`).click()
  await page.getByTestId('swap-target-period-1').locator('input').check()
  await page.getByTestId('swap-reason').fill('老师要去开会')
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('wizard-panel-3')).toBeVisible()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()
}

/** 收集 swap-plans / swap-previews 的响应体（方案 A/B 与格子内容断言用）。 */
function captureSwapResponses(page: Page): { plans: any[]; previews: any[] } {
  const plans: any[] = []
  const previews: any[] = []
  page.on('response', (response) => {
    if (response.request().method() !== 'POST') return
    if (response.url().includes('/substitution-workbench/swap-plans')) {
      void response.json().then((body) => plans.push(body)).catch(() => {})
    }
    if (response.url().includes('/substitution-workbench/swap-previews')) {
      void response.json().then((body) => previews.push(body)).catch(() => {})
    }
  })
  return { plans, previews }
}

/**
 * 响应体由异步 json() 挂进数组：UI 断言通过不代表捕获已落袋。
 * 读取 captured.plans / captured.previews 前一律先 poll 到位，消除异步竞态。
 */
async function waitForCaptured(captured: { plans: any[]; previews: any[] }, key: 'plans' | 'previews') {
  await expect.poll(() => captured[key].length, { timeout: 10_000 }).toBeGreaterThan(0)
}

/** 每个用例开始前挂 console/pageerror 监听；用例结束断言为空。 */
let consoleErrors: string[] = []

test.beforeEach(({ page }) => {
  consoleErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => consoleErrors.push(`pageerror: ${error}`))
})

test('代课（同科）：结论卡、单格可视化、提交审批后课表不变', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  await toCoverStep3(page, seed, seed.sameSubjectFreeId)

  // 结论卡：一句话 + 影响范围；同科无跨科门。
  await expect(page.getByTestId('confirm-conclusion')).toHaveText(
    `${cnDate(seed.lessonDate)}（${weekdayOf(seed.lessonDate)}）第 1 节，` +
      `${seed.class1Name}${seed.courseChineseName}改由${seed.sameSubjectFreeName}上，时间、教室不变。`,
  )
  await expect(page.getByTestId('confirm-scope')).toContainText('只有这一节课')
  await expect(page.getByTestId('cross-subject-callout')).toHaveCount(0)

  // 可视化：本周一张表一格，旧值删除线 → 新值；图例一项。
  await expect(page.getByTestId('change-week-0')).toBeVisible()
  const cells = page.getByTestId('change-visual').locator('.wc.chg')
  await expect(cells).toHaveCount(1)
  const cell = page.getByTestId('change-cell-1')
  await expect(cell.locator('.old')).toHaveText(`${seed.courseChineseName} ${seed.teacherZhangName}`)
  await expect(cell.locator('.new')).toHaveText(`${seed.courseChineseName} ${seed.sameSubjectFreeName}`)
  await expect(page.getByTestId('change-legend')).toHaveText(
    `① ${seed.courseChineseName}：${seed.teacherZhangName} → ${seed.sameSubjectFreeName}`,
  )
  await expect(page.getByTestId('confirm-deadline')).toContainText('这个方案保留到')

  await capture(page, 't02-01-cover-conclusion-visual')

  // 提交审批：成功态「已提交审批」，stepper 与 dock 隐藏。
  await page.getByTestId('submit-approval').click()
  const result = page.getByTestId('workbench-result')
  await expect(result).toContainText('已提交审批')
  await expect(result).toContainText('申请已进入待批队列，审批通过前课表维持原样')
  await expect(result).toContainText('申请 #')
  await expect(page.getByTestId('wizard-stepper')).toHaveCount(0)
  await expect(page.getByTestId('wizard-dock')).toHaveCount(0)

  // 再调整一节课：回步骤 1；课表未变（仍是张老师）。
  await page.getByTestId('wizard-again').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(page.getByTestId('wizard-live')).toHaveText('第 1 步，共 3 步：选哪节课')
  await expect(sourceCard(page, seed.lessonDate)).toContainText(seed.teacherZhangName)
  expect(consoleErrors).toEqual([])
})

test('代课：亲自确认生效后课表已变', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  await toCoverStep3(page, seed, seed.sameSubjectFreeId)

  await page.getByTestId('personal-confirm').click()
  const result = page.getByTestId('workbench-result')
  await expect(result).toContainText('已生效')
  await expect(result).toContainText('课表已更新，相关老师会收到通知')
  await capture(page, 't02-06-confirm-effective-result')

  await page.getByTestId('wizard-again').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  // 生效后果实换人：这门课从原教师张X的课表里消失（latest 视图按新教师归属）。
  await expect(page.getByTestId('pick-lesson')).toHaveCount(0)
  // 班级视角（API 佐证）：以授课日期请求，周三这节课的任课教师已是王X。
  const token = await apiLogin(api)
  const classEntries = await apiCall(api, token, 'get', `/timetable/class/${seed.class1Id}`, undefined, {
    view: 'latest',
    date: seed.lessonDate,
  })
  const wednesday = classEntries.find((row: any) => row.dayOfWeek === 3 && row.periodNo === 1)
  expect(wednesday).toBeTruthy()
  expect(wednesday.teacherName).toBe(seed.sameSubjectFreeName)
  expect(consoleErrors).toEqual([])
})

test('跨科门：未勾选两提交按钮禁用，勾选后可用', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  await toCoverStep3(page, seed, seed.crossSubjectFreeId)

  const callout = page.getByTestId('cross-subject-callout')
  await expect(callout).toContainText(`${seed.crossSubjectFreeName}是数学老师，与${seed.courseChineseName}不同科，属于跨科代课`)
  await expect(page.getByTestId('cross-subject-ack')).not.toBeChecked()
  await expect(page.getByTestId('submit-approval')).toBeDisabled()
  await expect(page.getByTestId('personal-confirm')).toBeDisabled()

  await page.getByTestId('cross-subject-ack').check()
  await expect(page.getByTestId('submit-approval')).toBeEnabled()
  await expect(page.getByTestId('personal-confirm')).toBeEnabled()

  await capture(page, 't02-02-cross-subject-gate')
  expect(consoleErrors).toEqual([])
})

test('同周调课：一张周表两格，格内内容与接口一致，图例两项', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  const captured = captureSwapResponses(page)
  await toSwapStep3(page, seed, seed.targetDate)

  await waitForCaptured(captured, 'previews')
  const preview = captured.previews[0]
  const leave = preview.changes.find((item: any) => item.fromDate === seed.lessonDate)
  const back = preview.changes.find((item: any) => item.fromDate === seed.targetDate)
  expect(leave && back).toBeTruthy()

  // 一张周表、两个变化格，内容与接口返回一致。
  await expect(page.getByTestId('change-week-0')).toBeVisible()
  await expect(page.getByTestId('change-week-1')).toHaveCount(0)
  const cells = page.getByTestId('change-visual').locator('.wc.chg')
  await expect(cells).toHaveCount(2)
  const cell1 = page.getByTestId('change-cell-1')
  const cell2 = page.getByTestId('change-cell-2')
  await expect(cell1.locator('.old')).toHaveText(`${leave.courseName} ${leave.teacherName}`)
  await expect(cell1.locator('.new')).toHaveText(`${back.courseName} ${back.teacherName}`)
  await expect(cell2.locator('.old')).toHaveText(`${back.courseName} ${back.teacherName}`)
  await expect(cell2.locator('.new')).toHaveText(`${leave.courseName} ${leave.teacherName}`)

  // 图例两项，槽位写法 本周X第N节。
  await expect(page.getByTestId('change-legend').locator('.lg')).toHaveCount(2)
  await expect(page.getByTestId('change-legend')).toContainText(
    `① ${leave.courseName}：本周三第1节 → 本周五第1节`,
  )
  await expect(page.getByTestId('change-legend')).toContainText(
    `② ${back.courseName}：本周五第1节 → 本周三第1节`,
  )

  // 折叠明细：两行变化 + 其余不变。
  await page.getByTestId('confirm-details').locator('summary').click()
  const rows = page.getByTestId('change-diff').locator('.drow:not(.hd)')
  await expect(rows).toHaveCount(2)
  await expect(page.getByTestId('change-diff').locator('.rest')).toHaveText('其余不变：教室、课程、任课老师。')
  await expect(page.getByTestId('change-people').locator('.person')).toHaveCount(3)

  // 课表在内部滚动容器里滚：截图前把展开的明细滚进视口，fullPage 拍不到未滚动区域。
  await page.getByTestId('confirm-details').scrollIntoViewIfNeeded()
  await capture(page, 't02-03-same-week-swap-details')
  expect(consoleErrors).toEqual([])
})

test('跨周调课：本周+下周两排周表在 1280×720 同屏可见', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  const captured = captureSwapResponses(page)
  await toSwapStep3(page, seed, seed.crossDate)

  await waitForCaptured(captured, 'previews')
  const preview = captured.previews[0]
  expect(preview.changes).toHaveLength(2)

  const week0 = page.getByTestId('change-week-0')
  const week1 = page.getByTestId('change-week-1')
  await expect(week0).toBeVisible()
  await expect(week1).toBeVisible()
  await expect(week1).toContainText('下周')
  await expect(week1).toContainText(`${mmdd(seed.crossDate)}–${mmdd(shiftDate(seed.crossDate, 4))}`)

  // 两排都要完整落在首屏内。
  await page.getByTestId('change-visual').evaluate((element) => {
    ;(element as HTMLElement).scrollIntoView({ block: 'center' })
  })
  for (const week of [week0, week1]) {
    const box = await week.boundingBox()
    expect(box).toBeTruthy()
    expect(box!.y).toBeGreaterThanOrEqual(0)
    expect(box!.y + box!.height).toBeLessThanOrEqual(720)
  }

  // 跨周格内容：本周格与下周格各一，方向与接口一致。
  const leave = preview.changes.find((item: any) => item.fromDate === seed.lessonDate)
  const back = preview.changes.find((item: any) => item.fromDate === seed.crossDate)
  await expect(page.getByTestId('change-week-0').locator('.wc.chg .old')).toHaveText(
    `${leave.courseName} ${leave.teacherName}`,
  )
  await expect(page.getByTestId('change-week-1').locator('.wc.chg .old')).toHaveText(
    `${back.courseName} ${back.teacherName}`,
  )
  await expect(page.getByTestId('change-legend')).toContainText(
    `① ${leave.courseName}：本周三第1节 → 下周一第1节`,
  )

  await capture(page, 't02-04-cross-week-two-tables')
  expect(consoleErrors).toEqual([])
})

test('方案 A/B：页签切换格数变化（A=2、B≥3），明细同步', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  const captured = captureSwapResponses(page)
  await toSwapStep3(page, seed, seed.targetDate)

  // 种子必须造出 ≥2 个候选方案；不足则让测试红掉，不许静默跳过。
  await waitForCaptured(captured, 'plans')
  const planCount = captured.plans[0]?.plans?.length ?? 0
  expect(planCount, `种子必须造出 ≥2 个候选方案，swap-plans 实际返回 ${planCount} 个`).toBeGreaterThanOrEqual(2)

  const tabs = page.locator('[data-testid^="swap-plan-tab-"]')
  await expect(tabs).toHaveCount(planCount)
  await expect(tabs.nth(0)).toContainText('方案 A（推荐）')
  await expect(tabs.nth(1)).toContainText('方案 B')
  await expect(tabs.nth(0)).toHaveAttribute('aria-selected', 'true')

  const cells = page.getByTestId('change-visual').locator('.wc.chg')
  await waitForCaptured(captured, 'previews')
  const planA = captured.previews[0]
  // 硬断言：种子（周三语文 ↔ 周五数学）的方案 A 恰好两处对调，不只与 UI 自身比较。
  expect(planA.changes, '方案 A 应恰好 2 处对调').toHaveLength(2)
  await expect(cells).toHaveCount(planA.changes.length)
  const rowsA = page.getByTestId('change-diff').locator('.drow:not(.hd)')
  await expect(rowsA).toHaveCount(planA.changes.length)

  // 切到方案 B：对另一 planId 重调 swap-previews，格子与明细同步变化。
  await tabs.nth(1).click()
  await expect.poll(() => captured.previews.length, { timeout: 20_000 }).toBe(2)
  const planB = captured.previews[1]
  expect(planB.changes.length).toBeGreaterThanOrEqual(3)
  await expect(cells).toHaveCount(planB.changes.length)
  const rowsB = page.getByTestId('change-diff').locator('.drow:not(.hd)')
  await expect(rowsB).toHaveCount(planB.changes.length)
  await expect(page.getByTestId('change-legend').locator('.lg')).toHaveCount(planB.changes.length)

  // 截图前把方案 B 的可视化滚进视口（内部滚动容器，fullPage 不会自动滚）。
  await page.getByTestId('change-visual').scrollIntoViewIfNeeded()
  await capture(page, 't02-05-plan-b')
  expect(consoleErrors).toEqual([])
})

test('返回上一步零写入：无 submit/confirm 请求，重进步骤 3 重新生成', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  await openConfirmWizard(page, seed)
  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  await page.getByTestId(`substitute-option-${seed.sameSubjectFreeId}`).locator('input').check()
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()

  const consumeRequests: string[] = []
  page.on('request', (request) => {
    const url = request.url()
    if (url.includes('/submit-approval') || url.includes('/confirm')) consumeRequests.push(url)
  })

  await page.getByTestId('wizard-back-3').click()
  await expect(page.getByTestId('wizard-panel-2')).toBeVisible()

  // 再进步骤 3：重新生成预览，依旧只有预览请求。
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()
  await page.waitForTimeout(300)
  expect(consumeRequests).toEqual([])
  expect(consoleErrors).toEqual([])
})

test('409：产生活跃单后提交被拒，错误条给大白话与重新生成', async ({ page }) => {
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  await toCoverStep3(page, seed, seed.sameSubjectFreeId)

  // 预览建好后，另一位管理员走 API 提交审批，给同一课节造成活跃单（不生效、待批）。
  const token = await apiLogin(api)
  const rival = await apiCall(api, token, 'post', '/substitution-workbench/previews', {
    entryId: seed.wednesdayEntryId,
    lessonDate: seed.lessonDate,
    substituteTeacherId: seed.crossSubjectFreeId,
  })
  await apiCall(api, token, 'post', `/substitution-workbench/previews/${rival.voucherId}/submit-approval`)

  // 提交审批撞 409：错误条显示后端大白话原文，不跳步。
  await page.getByTestId('submit-approval').click()
  const errorBar = page.getByTestId('confirm-error')
  await expect(errorBar).toContainText('已有待处理的申请')
  await expect(errorBar).toContainText('请先处理该单')
  await expect(page.getByTestId('wizard-panel-3')).toBeVisible()
  // 错误态禁用两个提交按钮（必须先重新生成），错误条里的重新生成保持可用。
  await expect(page.getByTestId('submit-approval')).toBeDisabled()
  await expect(page.getByTestId('personal-confirm')).toBeDisabled()
  await expect(page.getByTestId('regenerate')).toBeEnabled()
  await capture(page, 't02-07-409-error')

  // 重新生成：活跃单仍在，同样 409——错误条始终可操作、可重试。
  await page.getByTestId('regenerate').click()
  await expect(errorBar).toContainText('已有待处理的申请')
  await expect(page.getByTestId('wizard-panel-3')).toBeVisible()
  // 预期的 409 会以浏览器资源加载错误出现在 console 里；除它之外不允许任何错误。
  const unexpected = consoleErrors.filter((text) => !text.includes('409'))
  expect(unexpected).toEqual([])
})

test('窄屏（390px）：变化可视化按天列表且无横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const api = await newContext()
  const seed = await seedConfirmScenario(api)
  await toCoverStep3(page, seed, seed.sameSubjectFreeId, true)

  // 网格与列表二选一渲染（v-if）。
  await expect(page.getByTestId('change-visual').locator('.wtab')).toHaveCount(0)
  const list = page.getByTestId('change-list')
  await expect(list).toBeVisible()
  await expect(list.locator('.wi')).toHaveCount(1)
  await expect(list).toContainText('第1节')
  await expect(list).toContainText(`→ ${seed.courseChineseName} ${seed.sameSubjectFreeName}`)

  const delta = await page
    .getByTestId('change-visual')
    .evaluate((element) => (element as HTMLElement).scrollWidth - (element as HTMLElement).clientWidth)
  expect(delta).toBeLessThanOrEqual(1)

  // 课表在内部滚动容器里滚：截图前把变化可视化（含按天列表）滚进视口，fullPage 拍不到未滚动区域。
  await page.getByTestId('change-visual').scrollIntoViewIfNeeded()
  await capture(page, 't02-08-narrow-change-list')
  expect(consoleErrors).toEqual([])
})
