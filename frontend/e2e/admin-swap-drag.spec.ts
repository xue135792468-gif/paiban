import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  dragCardToCell,
  isoDate,
  newContext,
  openWorkbenchAtSourceLesson,
  publishDraft,
  shiftDate,
  solveDraft,
  sourceCard,
} from './helpers'

/**
 * 票 06：已发布课表拖拽进入调课预览（三步向导版）。
 * 拖拽源 = 步骤 1 周网格里的课卡（教师本人课表），目标格必须与本班有课的格子
 * （同教师同班的另一节课）对调；松手只预选第②步表单（调课方向 + 目标日期/节次），
 * 绝不自动生成方案；无效目标（空格/来源日之前/跨班）给出明确原因且零写入；
 * 表单入口保持等价。沿用 `expect(...).toPass()` + pointerdown 渲染节拍（可重试）。
 */

/** 拖拽种子：在票 05 种子骨架上加料——张老师在本班多一节课（周五第 2 节写作），
 * 并在高一2班带两节语文（周一/周四第 1 节），分别充当「来源日之前」与「跨班」投放格。 */
interface SwapDragSeed {
  classId: number
  class2Name: string
  teacherChineseName: string
  courseChineseName: string
  courseWritingName: string
  lessonDate: string
  targetDate: string
  monday: string
  thursday: string
}

async function seedSwapDragScenario(api: APIRequestContext): Promise<SwapDragSeed> {
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)
  const monday = new Date()
  monday.setDate(monday.getDate() + ((8 - monday.getDay()) % 7 || 7))
  const day = (offset: number) => {
    const shifted = new Date(monday)
    shifted.setDate(monday.getDate() + offset)
    return isoDate(shifted)
  }
  const wednesday = day(2)
  const thursday = day(3)
  const friday = day(4)

  const semester = await apiCall(api, token, 'post', '/semesters', {
    name: `E2E拖拽-${suffix}`,
    startDate: day(0),
    endDate: day(100),
  })
  await apiCall(api, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(api, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 2,
    afternoonCount: 0,
    eveningCount: 0,
    days: [1, 2, 3, 4, 5],
  })
  const periodRows = await apiCall(api, token, 'get', `/semesters/${semester.id}/periods`)
  const periods: Record<string, number> = {}
  for (const row of periodRows) {
    periods[`${row.dayOfWeek}-${row.periodNo}`] = row.id
  }

  const grade = await apiCall(api, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `高一${suffix}`,
    sortOrder: 1,
  })
  const class1 = await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一1班${suffix}`,
    studentCount: 45,
  })
  const class2 = await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一2班${suffix}`,
    studentCount: 45,
  })
  const teacherChinese = await apiCall(api, token, 'post', '/teachers', {
    name: `拖张${suffix}`,
    employeeNo: `DG1-${suffix}`,
    subject: '语文',
  })
  const teacherMath = await apiCall(api, token, 'post', '/teachers', {
    name: `拖王${suffix}`,
    employeeNo: `DG2-${suffix}`,
    subject: '数学',
  })
  const chinese = await apiCall(api, token, 'post', '/courses', {
    name: `语文${suffix}`,
    subject: '语文',
    allowConsecutive: false,
  })
  const writing = await apiCall(api, token, 'post', '/courses', {
    name: `写作${suffix}`,
    subject: '语文',
    allowConsecutive: false,
  })
  const math = await apiCall(api, token, 'post', '/courses', {
    name: `数学${suffix}`,
    subject: '数学',
    allowConsecutive: false,
  })
  const roomA = await apiCall(api, token, 'post', '/rooms', {
    name: `拖拽教室A${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  const roomB = await apiCall(api, token, 'post', '/rooms', {
    name: `拖拽教室B${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  await apiCall(api, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: chinese.id,
    teacherId: teacherChinese.id,
    weeklyLessons: 1,
  })
  await apiCall(api, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: writing.id,
    teacherId: teacherChinese.id,
    weeklyLessons: 1,
  })
  await apiCall(api, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: math.id,
    teacherId: teacherMath.id,
    weeklyLessons: 1,
  })
  await apiCall(api, token, 'post', '/assignments', {
    classId: class2.id,
    courseId: chinese.id,
    teacherId: teacherChinese.id,
    weeklyLessons: 2,
  })

  // 求解后强制摆放：语文周三第 1（来源）、写作周五第 2（同班有效目标）、
  // 数学周五第 1（王老师，不在张老师网格里）、高一2班语文周一/周四第 1（无效目标格）。
  const draftId = await solveDraft(api, semester.id)
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  const byClassCourse = new Map<string, any[]>()
  for (const lesson of detail.lessons) {
    const key = `${lesson.classId}-${lesson.courseId}`
    byClassCourse.set(key, [...(byClassCourse.get(key) ?? []), lesson])
  }
  const place = (classId: number, courseId: number, index: number, key: string, roomId: number) => {
    const lessons = byClassCourse.get(`${classId}-${courseId}`)
    const lesson = lessons?.[index]
    if (!lesson) {
      throw new Error(`missing draft lesson for class ${classId} course ${courseId}`)
    }
    return apiCall(api, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
      periodId: periods[key],
      roomId,
      force: true,
    })
  }
  await place(class1.id, chinese.id, 0, '3-1', roomA.id)
  await place(class1.id, writing.id, 0, '5-2', roomA.id)
  await place(class1.id, math.id, 0, '5-1', roomB.id)
  await place(class2.id, chinese.id, 0, '1-1', roomB.id)
  await place(class2.id, chinese.id, 1, '4-1', roomB.id)
  await publishDraft(api, draftId)

  return {
    classId: class1.id,
    class2Name: class2.name,
    teacherChineseName: teacherChinese.name,
    courseChineseName: chinese.name,
    courseWritingName: writing.name,
    lessonDate: wednesday,
    targetDate: friday,
    monday: shiftDate(wednesday, -2),
    thursday,
  }
}

test('拖拽到同班有课的目标格：预选调课带出目标日期/节次，生成方案后整环对照', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapDragScenario(api)
  await openWorkbenchAtSourceLesson(page, { teacherName: seed.teacherChineseName, lessonDate: seed.lessonDate })

  // 新界面拖拽面 = 教师本人的周课表（步骤 1 网格，每格都是投放格）；
  // 别的老师在本班周五第 1 节的数学课不在拖拽源网格里，不会被误当目标。
  const grid = page.getByTestId('workbench-timetable')
  await expect(grid).toBeVisible()
  await expect(grid.locator('[data-drop-cell]').first()).toBeAttached()
  await expect(grid).not.toContainText('数学')

  // 拖拽只预选表单：swap-plans（生成方案）必须一个都不发。
  const swapPlanRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/substitution-workbench/swap-plans')) swapPlanRequests.push(request.url())
  })

  const panel2 = page.getByTestId('wizard-panel-2')
  const targetCell = page.getByTestId(`workbench-cell-${seed.targetDate}-2`)
  await expect(async () => {
    if ((await panel2.count()) === 0) {
      await dragCardToCell(page, sourceCard(page, seed.lessonDate), targetCell)
    }
    await expect(panel2).toBeVisible({ timeout: 1_000 })
    await expect(page.getByTestId('choose-swap')).toHaveAttribute('aria-pressed', 'true', { timeout: 1_000 })
    await expect(page.getByTestId(`swap-date-chip-${seed.targetDate}`))
      .toHaveAttribute('aria-pressed', 'true', { timeout: 1_000 })
    await expect(page.getByTestId('swap-target-period-2').locator('input'))
      .toBeChecked({ timeout: 1_000 })
  }).toPass({ timeout: 30_000 })

  // 仍在步骤 2：绝不自动进步骤 3、不自动生成方案。
  await expect(page.getByTestId('wizard-panel-3')).toHaveCount(0)
  await expect(page.getByTestId('wizard-need-2')).toHaveText('可以点下一步了')
  await expect(page.getByTestId('swap-target-period-2')).toContainText(seed.courseWritingName)
  expect(swapPlanRequests).toHaveLength(0)

  // 预选完成后与表单同一条路：下一步生成方案 → 整环对照（本周一张表两处变化）。
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('swap-plan-tab-0')).toContainText('2 节课对调')
  await expect(page.getByTestId('change-week-0')).toBeVisible()
  await expect(page.getByTestId('change-visual').locator('.wc.chg')).toHaveCount(2)

  // 小屏：对照区切按天列表，箭头写出旧 → 新，明细照常可读。
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByTestId('change-list')).toBeVisible()
  await expect(page.getByTestId('change-list')).toContainText('→')
})

test('拖到空格、来源日之前与跨班格子：显示明确原因，留在步骤 1 且零写入', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapDragScenario(api)
  await openWorkbenchAtSourceLesson(page, { teacherName: seed.teacherChineseName, lessonDate: seed.lessonDate })

  // 登录已完成，此后页面上任何一个非 GET 请求都算写入；不只盯 swap-plans/swap-targets。
  const writeRequests: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writeRequests.push(`${request.method()} ${request.url()}`)
  })

  // 空格（周三第 2 节本班没课）：空格只挪不补。
  const emptyCell = page.getByTestId(`workbench-cell-${seed.lessonDate}-2`)
  await expect(async () => {
    await dragCardToCell(page, sourceCard(page, seed.lessonDate), emptyCell)
    await expect(page.getByTestId('swap-drop-invalid')).toContainText('空格', { timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(page.getByTestId('wizard-next-1')).toBeDisabled()

  // 来源日之前（周一第 1 节）：窗口从来源日期向后展开，不支持向前搜索。
  const beforeCell = page.getByTestId(`workbench-cell-${seed.monday}-1`)
  await expect(beforeCell).toContainText(seed.courseChineseName)
  await expect(async () => {
    await dragCardToCell(page, sourceCard(page, seed.lessonDate), beforeCell)
    await expect(page.getByTestId('swap-drop-invalid'))
      .toContainText('来源日期之前', { timeout: 1_000 })
  }).toPass({ timeout: 30_000 })

  // 跨班（周四第 1 节是高一2班的课）：原因里点出目标班级。
  const crossCell = page.getByTestId(`workbench-cell-${seed.thursday}-1`)
  await expect(crossCell).toContainText(seed.courseChineseName)
  await expect(async () => {
    await dragCardToCell(page, sourceCard(page, seed.lessonDate), crossCell)
    await expect(page.getByTestId('swap-drop-invalid'))
      .toContainText(`跨班：目标格是${seed.class2Name}的课`, { timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()

  // 计数前给一拍可观测窗口：确认 UI 稳定后再查，迟到的写入也逃不掉。
  await page.waitForTimeout(200)
  expect(writeRequests).toEqual([])

  // API 侧不变量：拖动零写入——没有申请，也没有生效行。
  const token = await apiLogin(api)
  const pending = await apiCall(api, token, 'get', '/substitution-requests', undefined, {
    status: 'PENDING_APPROVAL',
  })
  expect(pending.length).toBe(0)
  const effective = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(effective.length).toBe(0)
})

test('取消预览后零变化：无申请、无生效、当前有效课表不变', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapDragScenario(api)
  await openWorkbenchAtSourceLesson(page, { teacherName: seed.teacherChineseName, lessonDate: seed.lessonDate })

  const consumeRequests: string[] = []
  page.on('request', (request) => {
    const url = request.url()
    if (url.includes('/submit-approval') || url.includes('/confirm')) consumeRequests.push(url)
  })

  const targetCell = page.getByTestId(`workbench-cell-${seed.targetDate}-2`)
  await expect(async () => {
    if ((await page.getByTestId('wizard-panel-2').count()) === 0) {
      await dragCardToCell(page, sourceCard(page, seed.lessonDate), targetCell)
    }
    await expect(page.getByTestId('wizard-panel-2')).toBeVisible({ timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('swap-plan-tab-0')).toBeVisible()

  // 返回上一步弃用凭证，再从摘要「修改」回到流程起点。
  await page.getByTestId('wizard-back-3').click()
  await expect(page.getByTestId('wizard-panel-2')).toBeVisible()
  await page.getByTestId('picked-edit').click()
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()

  const token = await apiLogin(api)
  const pending = await apiCall(api, token, 'get', '/substitution-requests', undefined, {
    status: 'PENDING_APPROVAL',
  })
  expect(pending.length).toBe(0)
  const effective = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(effective.length).toBe(0)

  // 当前有效安排不变：来源课仍在本班周三第 1 节。
  const entries = await apiCall(api, token, 'get', `/timetable/class/${seed.classId}`, undefined, {
    view: 'latest',
    date: seed.lessonDate,
  })
  const chinese = entries.find((row: any) => row.courseName === seed.courseChineseName)
  expect(chinese).toBeTruthy()
  expect(chinese.dayOfWeek).toBe(3)
  expect(chinese.periodNo).toBe(1)
})

test('表单与拖拽等价：同一目标（周五第 2 节）得到同一批方案', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSwapDragScenario(api)

  // 表单路径：窗口 chip 周五 + 目标节次 radio（第 2 节 写作）。
  // 来源课必须选周三第 1 节的语文（first() 会命中教师周一在高一2班的课）。
  await openWorkbenchAtSourceLesson(page, { teacherName: seed.teacherChineseName, lessonDate: seed.lessonDate })
  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-swap').click()
  await expect(page.getByTestId('window-dates')).toBeVisible()
  await page.getByTestId(`swap-date-chip-${seed.targetDate}`).click()
  await page.getByTestId('swap-target-period-2').locator('input').check()
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('swap-plan-tab-0')).toBeVisible()
  const formCount = await page.locator('[data-testid^="swap-plan-tab-"]').count()
  const formFirst = await page.getByTestId('swap-plan-tab-0').innerText()

  // 返回起点（凭证弃用），改走拖拽路径。
  await page.getByTestId('wizard-back-3').click()
  await page.getByTestId('picked-edit').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()

  // 拖拽路径：拖到同一个目标格（周五第 2 节）。
  const targetCell = page.getByTestId(`workbench-cell-${seed.targetDate}-2`)
  await expect(async () => {
    if ((await page.getByTestId('wizard-panel-2').count()) === 0) {
      await dragCardToCell(page, sourceCard(page, seed.lessonDate), targetCell)
    }
    await expect(page.getByTestId('swap-target-period-2').locator('input'))
      .toBeChecked({ timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('swap-plan-tab-0')).toBeVisible()

  expect(await page.locator('[data-testid^="swap-plan-tab-"]').count()).toBe(formCount)
  expect(await page.getByTestId('swap-plan-tab-0').innerText()).toBe(formFirst)
})
