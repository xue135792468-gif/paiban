import { expect, test, type APIRequestContext } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  cnDate,
  dragCardToCell,
  isoDate,
  newContext,
  openWorkbenchAtSourceLesson,
  pickOption,
  publishDraft,
  shiftDate,
  solveDraft,
  sourceCard,
  uiLogin,
  weekdayOf,
} from './helpers'
import { capture } from './screenshots'

/**
 * 票 01：调代工作台三步向导（第①②步）。
 * 步骤条与单面板渲染、步骤 1 选课（周网格 + 320px 按天列表）、动作卡与切换链接、
 * 代课候选、调课窗口 chips、拖拽预选（有效落步骤 2，无效给原因且零请求）。
 * 第 3 步是票 02 的占位面板，这里只断言不会自动跳进去。
 */

interface WizardSeed {
  semesterId: number
  class1Id: number
  class2Name: string
  teacherId: number
  teacherName: string
  /** 同科且无课：候选徽标「同科 · 空闲」。 */
  sameSubjectFreeId: number
  sameSubjectFreeName: string
  /** 跨科且无课：候选徽标「跨科」。 */
  crossSubjectFreeId: number
  crossSubjectFreeName: string
  /** 该时段（周三第 1 节）在别的班有课：候选置灰并给出原因。 */
  busyTeacherId: number
  lessonDate: string
  targetDate: string
  crossDate: string
  saturday: string
  sunday: string
}

/**
 * 票 01 向导种子：高一1班 周三第 1 节语文（张X）+ 周五第 1 节语文（张X），
 * 高一2班 周四第 1 节语文（张X，跨班投放目标）+ 周三第 1 节英语（赵X，占忙候选）。
 * 王（语文）/李（数学）无课，分别充当同科与跨科空闲候选。
 */
async function seedWizardScenario(context: APIRequestContext): Promise<WizardSeed> {
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
  const thursday = at(3)
  const friday = at(4)
  const saturday = at(5)
  const sunday = at(6)

  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E向导-${suffix}`,
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
  const class2 = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一2班${suffix}`,
    studentCount: 45,
  })
  const teacherZhang = await apiCall(context, token, 'post', '/teachers', {
    name: `向导张${suffix}`,
    employeeNo: `WZ-${suffix}`,
    subject: '语文',
  })
  const teacherWang = await apiCall(context, token, 'post', '/teachers', {
    name: `向导王${suffix}`,
    employeeNo: `WW-${suffix}`,
    subject: '语文',
  })
  const teacherLi = await apiCall(context, token, 'post', '/teachers', {
    name: `向导李${suffix}`,
    employeeNo: `WL-${suffix}`,
    subject: '数学',
  })
  const teacherZhao = await apiCall(context, token, 'post', '/teachers', {
    name: `向导赵${suffix}`,
    employeeNo: `VZ-${suffix}`,
    subject: '英语',
  })
  const chinese = await apiCall(context, token, 'post', '/courses', {
    name: `语文${suffix}`,
    subject: '语文',
    allowConsecutive: false,
  })
  const english = await apiCall(context, token, 'post', '/courses', {
    name: `英语${suffix}`,
    subject: '英语',
    allowConsecutive: false,
  })
  const roomA = await apiCall(context, token, 'post', '/rooms', {
    name: `向导教室A${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  const roomB = await apiCall(context, token, 'post', '/rooms', {
    name: `向导教室B${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: chinese.id,
    teacherId: teacherZhang.id,
    weeklyLessons: 2,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class2.id,
    courseId: chinese.id,
    teacherId: teacherZhang.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class2.id,
    courseId: english.id,
    teacherId: teacherZhao.id,
    weeklyLessons: 1,
  })

  const draftId = await solveDraft(context, semester.id)
  const detail = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const class1Chinese = detail.lessons
    .filter((row: any) => row.classId === class1.id && row.courseId === chinese.id)
    .map((row: any) => row.id)
  const class2Chinese = detail.lessons
    .filter((row: any) => row.classId === class2.id && row.courseId === chinese.id)
    .map((row: any) => row.id)
  const class2English = detail.lessons
    .filter((row: any) => row.classId === class2.id && row.courseId === english.id)
    .map((row: any) => row.id)
  if (class1Chinese.length !== 2 || class2Chinese.length !== 1 || class2English.length !== 1) {
    throw new Error('unexpected draft lessons for the wizard seed')
  }
  const place = (lessonId: number, key: string, roomId: number) =>
    apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lessonId}`, {
      periodId: periods[key],
      roomId,
      force: true,
    })
  await place(class1Chinese[0], '3-1', roomA.id)
  await place(class1Chinese[1], '5-1', roomA.id)
  await place(class2Chinese[0], '4-1', roomB.id)
  await place(class2English[0], '3-1', roomB.id)
  await publishDraft(context, draftId)

  const entries = await apiCall(context, token, 'get', `/timetable/class/${class1.id}`, undefined, {
    view: 'baseline',
  })
  if (!entries.some((row: any) => row.dayOfWeek === 3 && row.periodNo === 1)) {
    throw new Error('wizard seed missed the Wednesday source lesson')
  }
  if (!entries.some((row: any) => row.dayOfWeek === 5 && row.periodNo === 1)) {
    throw new Error('wizard seed missed the Friday target lesson')
  }

  return {
    semesterId: semester.id,
    class1Id: class1.id,
    class2Name: class2.name,
    teacherId: teacherZhang.id,
    teacherName: teacherZhang.name,
    sameSubjectFreeId: teacherWang.id,
    sameSubjectFreeName: teacherWang.name,
    crossSubjectFreeId: teacherLi.id,
    crossSubjectFreeName: teacherLi.name,
    busyTeacherId: teacherZhao.id,
    lessonDate: wednesday,
    targetDate: friday,
    crossDate: thursday,
    saturday,
    sunday,
  }
}

interface PastWeekSeed {
  teacherId: number
  teacherName: string
  /** 测试进程按本地时区算出的今天；页面默认日期必须等于它。 */
  today: string
  /** 今天所在周的周一~周五（默认浏览周）。 */
  weekDates: string[]
}

/**
 * 过去日期守护种子：学期从两周前开始，教师周一~周五第 1 节各一节课，
 * 覆盖「默认周里既有已过去课卡、也有今天/未来课卡」的场景。
 */
async function seedPastWeekScenario(context: APIRequestContext): Promise<PastWeekSeed> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const now = new Date()
  const todayDate = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const semesterStart = new Date(todayDate)
  semesterStart.setDate(semesterStart.getDate() - 14)
  const semesterEnd = new Date(todayDate)
  semesterEnd.setDate(semesterEnd.getDate() + 100)

  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E过去日期-${suffix}`,
    startDate: isoDate(semesterStart),
    endDate: isoDate(semesterEnd),
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 1,
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
    name: `高一过去${suffix}`,
    sortOrder: 1,
  })
  const adminClass = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一1班过去${suffix}`,
    studentCount: 45,
  })
  const teacher = await apiCall(context, token, 'post', '/teachers', {
    name: `过去张${suffix}`,
    employeeNo: `PZ-${suffix}`,
    subject: '语文',
  })
  const course = await apiCall(context, token, 'post', '/courses', {
    name: `语文过去${suffix}`,
    subject: '语文',
    allowConsecutive: false,
  })
  const room = await apiCall(context, token, 'post', '/rooms', {
    name: `过去教室${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: adminClass.id,
    courseId: course.id,
    teacherId: teacher.id,
    weeklyLessons: 5,
  })

  const draftId = await solveDraft(context, semester.id)
  const detail = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const lessonIds = detail.lessons
    .filter((row: any) => row.classId === adminClass.id && row.courseId === course.id)
    .map((row: any) => row.id)
  if (lessonIds.length !== 5) {
    throw new Error('unexpected draft lessons for the past-week seed')
  }
  for (let dayOfWeek = 1; dayOfWeek <= 5; dayOfWeek += 1) {
    await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lessonIds[dayOfWeek - 1]}`, {
      periodId: periods[`${dayOfWeek}-1`],
      roomId: room.id,
      force: true,
    })
  }
  await publishDraft(context, draftId)

  const monday = new Date(todayDate)
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7))
  const weekDates = Array.from({ length: 5 }, (_, index) => {
    const day = new Date(monday)
    day.setDate(monday.getDate() + index)
    return isoDate(day)
  })

  return {
    teacherId: teacher.id,
    teacherName: teacher.name,
    today: isoDate(todayDate),
    weekDates,
  }
}

/** 每个用例开始前挂 console/pageerror 监听；用例结束断言为空（见各 test 尾部）。 */
let consoleErrors: string[] = []

test.beforeEach(({ page }) => {
  consoleErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => consoleErrors.push(`pageerror: ${error}`))
})

test('步骤1：周课表点选、下一步解锁、步骤条与单面板渲染', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  await expect(page.getByTestId('wizard-stepper')).toBeVisible()
  await expect(page.getByTestId('wizard-live')).toHaveText('第 1 步，共 3 步：选哪节课')
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(page.getByTestId('wizard-panel-2')).toHaveCount(0)
  await expect(page.getByTestId('wizard-panel-3')).toHaveCount(0)
  await expect(page.getByTestId('wizard-dock')).toContainText('还差：点一节课开始')
  await expect(page.getByTestId('wizard-next-1')).toBeDisabled()

  // 周网格：列头是所选周的真实日期；种子教师本周三/四/五各一节。
  await expect(page.getByTestId('pick-lesson')).toHaveCount(3)
  await expect(page.getByTestId(`workbench-cell-${shiftDate(seed.lessonDate, -2)}-1`)).toBeAttached()
  await capture(page, 't01-01-step1-grid')

  // 点选：卡片高亮（aria-pressed）+ dock 摘要 + 下一步解锁。整周课卡都可点。
  await sourceCard(page, seed.lessonDate).click()
  await expect(sourceCard(page, seed.lessonDate)).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('picked-summary')).toContainText(`${weekdayOf(seed.lessonDate)}第1节`)
  await expect(page.getByTestId('picked-summary')).toContainText(seed.teacherName)
  await expect(page.getByTestId('wizard-next-1')).toBeEnabled()
  await page.getByTestId('wizard-next-1').click()

  await expect(page.getByTestId('wizard-panel-2')).toBeVisible()
  await expect(page.getByTestId('wizard-panel-1')).toHaveCount(0)
  await expect(page.getByTestId('wizard-live')).toHaveText('第 2 步，共 3 步：怎么调')
  await expect(page.getByTestId('wizard-step-1').locator('.dot')).toHaveText('✓')

  await page.getByTestId('wizard-back-2').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(page.getByTestId('wizard-panel-2')).toHaveCount(0)
  expect(consoleErrors).toEqual([])
})

test('窄屏（390px）按天列表', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  // 网格与列表二选一渲染（v-if，不是 CSS 隐藏）。
  await expect(page.locator('.wgrid')).toHaveCount(0)
  await expect(page.getByTestId('workbench-timetable')).toBeVisible()
  const items = page.getByTestId('pick-lesson')
  await expect(items).toHaveCount(3)
  // 按天列表项：周X M/D · 第N节 · 课程 · 教师。
  await expect(items.first()).toContainText(`${weekdayOf(seed.lessonDate)}`)
  await expect(items.first()).toContainText('· 第1节 ·')

  await capture(page, 't01-02-step1-mobile-list')
  await items.first().click()
  await expect(items.first()).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('picked-summary')).toBeVisible()
  await expect(page.getByTestId('wizard-next-1')).toBeEnabled()
  expect(consoleErrors).toEqual([])
})

test('动作卡：两方向来回切，切换链接在表单上方且 1280×720 首屏可见', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await expect(page.getByTestId('wizard-panel-2')).toBeVisible()
  await expect(page.getByTestId('wizard-need-2')).toHaveText('还差：选择一种调整方式')

  // 选代课：选中卡 → 虚线切换链接 → 表单；链接必须在表单上方且完整落在首屏。
  await page.getByTestId('choose-cover').click()
  const coverForm = page.getByTestId('substitute-picker')
  await expect(coverForm).toBeVisible()
  await expect(page.getByTestId('choose-cover')).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('wizard-need-2')).toHaveText('还差：选择代课老师')

  const coverLink = page.getByTestId('switch-swap')
  const linkBox = await coverLink.boundingBox()
  const formBox = await coverForm.boundingBox()
  expect(linkBox).toBeTruthy()
  expect(formBox).toBeTruthy()
  expect(linkBox!.y).toBeLessThan(formBox!.y)
  expect(linkBox!.y).toBeGreaterThanOrEqual(0)
  expect(linkBox!.y + linkBox!.height).toBeLessThanOrEqual(720)

  // 先把代课侧填一半：候选选中 + 原因已填，切走后必须被清掉。
  await page.getByTestId(`substitute-option-${seed.sameSubjectFreeId}`).locator('input').check()
  await page.getByTestId('preview-reason').fill('临时有事')
  await capture(page, 't01-03-step2-action-cards')

  // 切到调课：另一方向的链接同样在表单上方、首屏可见。
  await coverLink.click()
  const swapPane = page.locator('.pane').filter({ has: page.getByTestId('window-dates') })
  await expect(swapPane).toBeVisible()
  await expect(page.getByTestId('choose-swap')).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('wizard-need-2')).toHaveText('还差：选择目标日期')
  const swapLink = page.getByTestId('switch-cover')
  const swapLinkBox = await swapLink.boundingBox()
  const swapPaneBox = await swapPane.boundingBox()
  expect(swapLinkBox).toBeTruthy()
  expect(swapPaneBox).toBeTruthy()
  expect(swapLinkBox!.y).toBeLessThan(swapPaneBox!.y)
  expect(swapLinkBox!.y).toBeGreaterThanOrEqual(0)
  expect(swapLinkBox!.y + swapLinkBox!.height).toBeLessThanOrEqual(720)

  // 来回切回代课：代课未定状态已清场（radio 全部未选中、原因为空）。
  await swapLink.click()
  await expect(coverForm).toBeVisible()
  const coverRadios = coverForm.locator('input[name="workbench-substitute"]')
  await expect(coverRadios.first()).toBeVisible()
  const radioCount = await coverRadios.count()
  for (let index = 0; index < radioCount; index += 1) {
    await expect(coverRadios.nth(index)).not.toBeChecked()
  }
  await expect(page.getByTestId('preview-reason')).toHaveValue('')

  // 再走一遍调课并填原因，切回代课再切回调课：swap-reason 为空、目标 chip 无选中。
  await coverLink.click()
  const fridayChip = page.getByTestId(`swap-date-chip-${seed.targetDate}`)
  await fridayChip.click()
  await expect(fridayChip).toHaveAttribute('aria-pressed', 'true')
  await page.getByTestId('swap-reason').fill('要去开会')
  await page.getByTestId('switch-cover').click()
  await page.getByTestId('switch-swap').click()
  await expect(page.getByTestId('swap-reason')).toHaveValue('')
  await expect(page.locator('[data-testid^="swap-date-chip-"][aria-pressed="true"]')).toHaveCount(0)
  expect(consoleErrors).toEqual([])
})

test('代课候选：排除当前教师、同科排前、跨科标记、禁用候选原因可见', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  const picker = page.getByTestId('substitute-picker')
  await expect(picker).toBeVisible()
  await expect(picker).not.toContainText(seed.teacherName)

  // 同科空闲候选：徽标 + 说明行；跨科候选排在其后。
  const sameSubject = picker.getByTestId(`substitute-option-${seed.sameSubjectFreeId}`)
  await expect(sameSubject).toBeVisible()
  await expect(sameSubject.locator('.bg')).toHaveText('同科 · 空闲')
  await expect(sameSubject).toContainText('语文老师，这一节没有课')
  const crossSubject = picker.getByTestId(`substitute-option-${seed.crossSubjectFreeId}`)
  await expect(crossSubject.locator('.bg')).toHaveText('跨科')
  await expect(crossSubject).toContainText('数学老师，这一节没有课')
  const names = await picker.locator('[data-testid^="substitute-option-"] .nm').allTextContents()
  expect(names.indexOf(seed.sameSubjectFreeName)).toBeGreaterThanOrEqual(0)
  expect(names.indexOf(seed.sameSubjectFreeName)).toBeLessThan(names.indexOf(seed.crossSubjectFreeName))

  // 忙碌候选：整行可见、radio 禁用、原因点出冲突班级。
  const busy = picker.getByTestId(`substitute-option-${seed.busyTeacherId}`)
  await expect(busy).toBeVisible()
  await expect(busy.locator('.bg')).toHaveText('该时段有课')
  await expect(busy).toContainText('该时段已有课')
  await expect(busy).toContainText(seed.class2Name)
  await expect(busy.locator('input')).toBeDisabled()

  const reasonBox = await page.getByTestId('preview-reason').boundingBox()
  expect(reasonBox).toBeTruthy()
  expect(reasonBox!.height).toBeLessThanOrEqual(50)
  // 截图前把内部滚动容器滚到忙碌候选行，让证据图拍出「禁用候选 + 原因」。
  await busy.scrollIntoViewIfNeeded()
  await capture(page, 't01-04-step2-cover-candidates')
  expect(consoleErrors).toEqual([])
})

test('调课窗口 chips：含当天共 7 天、周末置灰、选日期拉目标节次、空格日提示', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-swap').click()

  await expect(page.getByTestId('window-dates')).toBeVisible()
  await expect(page.getByTestId('wizard-panel-2')).toContainText('含当天共 7 天')
  await expect(page.getByTestId('wizard-panel-2')).toContainText(
    `可调到 ${cnDate(seed.lessonDate)}–${cnDate(shiftDate(seed.lessonDate, 6))}`,
  )
  const chips = page.locator('[data-testid^="swap-date-chip-"]')
  await expect(chips).toHaveCount(7)

  // 周末 chip 禁用并注明原因；窗口外日期不渲染（总数就是 7）。
  await expect(page.getByTestId(`swap-date-chip-${seed.saturday}`)).toBeDisabled()
  await expect(page.getByTestId(`swap-date-chip-${seed.saturday}`)).toContainText('周末没有课')
  await expect(page.getByTestId(`swap-date-chip-${seed.sunday}`)).toBeDisabled()
  await expect(page.getByTestId(`swap-date-chip-${seed.sunday}`)).toContainText('周末没有课')

  // 选周五：swap-targets 返回本班（高一1班）周五的课，节次 radio 可选。
  const fridayChip = page.getByTestId(`swap-date-chip-${seed.targetDate}`)
  await fridayChip.click()
  await expect(fridayChip).toHaveAttribute('aria-pressed', 'true')
  const periods = page.getByTestId('swap-target-period')
  await expect(periods).toBeVisible()
  await expect(page.getByTestId('swap-target-period-1')).toContainText('第1节')

  await page.getByTestId('swap-target-period-1').locator('input').check()
  await expect(page.getByTestId('swap-target-period-1').locator('input')).toBeChecked()
  await expect(page.getByTestId('wizard-need-2')).toHaveText('可以点下一步了')
  await expect(page.getByTestId('wizard-next-2')).toBeEnabled()

  const reasonBox = await page.getByTestId('swap-reason').boundingBox()
  expect(reasonBox).toBeTruthy()
  expect(reasonBox!.height).toBeLessThanOrEqual(50)
  await capture(page, 't01-05-step2-swap-chips')

  // 周四本班没课：空格只挪不补。
  await page.getByTestId(`swap-date-chip-${seed.crossDate}`).click()
  await expect(page.getByTestId('swap-empty-day')).toContainText('空格只挪不补，请换一个目标日期')
  expect(consoleErrors).toEqual([])
})

test('拖拽有效：预选调课并带出目标日期/节次，落在步骤 2 不自动进步骤 3', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  // 拖拽只预选表单：swap-plans（生成方案）必须一个都不发。
  const swapPlanRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/substitution-workbench/swap-plans')) swapPlanRequests.push(request.url())
  })

  const panel2 = page.getByTestId('wizard-panel-2')
  const target = page.getByTestId(`workbench-cell-${seed.targetDate}-1`)
  // 拖拽零写入、可整体重试（CI 负载下 pointerup 偶发丢失）。
  await expect(async () => {
    if ((await panel2.count()) === 0) {
      await dragCardToCell(page, sourceCard(page, seed.lessonDate), target)
    }
    await expect(panel2).toBeVisible({ timeout: 1_000 })
    await expect(page.getByTestId('choose-swap')).toHaveAttribute('aria-pressed', 'true', { timeout: 1_000 })
    await expect(page.getByTestId(`swap-date-chip-${seed.targetDate}`))
      .toHaveAttribute('aria-pressed', 'true', { timeout: 1_000 })
    await expect(page.getByTestId('swap-target-period-1').locator('input'))
      .toBeChecked({ timeout: 1_000 })
  }).toPass({ timeout: 30_000 })

  // 仍在步骤 2：绝不自动进步骤 3、不自动生成方案。
  await expect(page.getByTestId('wizard-panel-3')).toHaveCount(0)
  await expect(page.getByTestId('wizard-live')).toHaveText('第 2 步，共 3 步：怎么调')
  await expect(page.getByTestId('wizard-need-2')).toHaveText('可以点下一步了')
  await expect(page.getByTestId('wizard-next-2')).toBeEnabled()
  await expect(page.getByTestId('swap-target-period-1')).toContainText('第1节')
  await capture(page, 't01-06-drag-preselect-step2')
  expect(swapPlanRequests).toHaveLength(0)
  expect(consoleErrors).toEqual([])
})

test('拖拽无效：空格与跨班给出原因，留在步骤 1 且零写入', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  // 登录已完成，此后页面上任何一个非 GET 请求都算写入；不只盯 swap-plans/swap-targets。
  const writeRequests: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'GET') writeRequests.push(`${request.method()} ${request.url()}`)
  })

  // 空格（周一第 1 节）：原因条 + 留在步骤 1 + 未选中任何课。
  const mondayCell = page.getByTestId(`workbench-cell-${shiftDate(seed.lessonDate, -2)}-1`)
  await expect(async () => {
    await dragCardToCell(page, sourceCard(page, seed.lessonDate), mondayCell)
    await expect(page.getByTestId('swap-drop-invalid')).toContainText('空格', { timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(page.getByTestId('wizard-next-1')).toBeDisabled()

  // 跨班（周四第 1 节是高一2班的课）：原因里点出目标班级。
  const crossCell = page.getByTestId(`workbench-cell-${seed.crossDate}-1`)
  await expect(async () => {
    await dragCardToCell(page, sourceCard(page, seed.lessonDate), crossCell)
    await expect(page.getByTestId('swap-drop-invalid'))
      .toContainText(`跨班：目标格是${seed.class2Name}的课`, { timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await capture(page, 't01-07-drop-invalid-reason')

  // 计数前给一拍可观测窗口：确认 UI 稳定后再查，迟到的写入也逃不掉。
  await page.waitForTimeout(200)
  expect(writeRequests).toEqual([])
  expect(consoleErrors).toEqual([])
})

test('①②步全流程走查：控制台零错误', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWorkbenchAtSourceLesson(page, seed)

  // 步骤 1 → 代课：选候选、填原因。
  await sourceCard(page, seed.lessonDate).click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  await page.getByTestId(`substitute-option-${seed.sameSubjectFreeId}`).locator('input').check()
  await page.getByTestId('preview-reason').fill('临时有事')

  // 切到调课：代课未定状态被清掉，重新选日期与节次。
  await page.getByTestId('switch-swap').click()
  await expect(page.getByTestId('wizard-need-2')).toHaveText('还差：选择目标日期')
  await page.getByTestId(`swap-date-chip-${seed.targetDate}`).click()
  await page.getByTestId('swap-target-period-1').locator('input').check()
  await expect(page.getByTestId('wizard-need-2')).toHaveText('可以点下一步了')

  // 摘要条「修改」回步骤 1，重新点选后再走一遍动作与窗口（重置语义：旧选择被清空）。
  await page.getByTestId('picked-edit').click()
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await page.getByTestId(`workbench-cell-${seed.lessonDate}-1`).getByTestId('pick-lesson').click()
  await page.getByTestId('wizard-next-1').click()
  await expect(page.getByTestId('wizard-need-2')).toHaveText('还差：选择一种调整方式')
  await page.getByTestId('choose-swap').click()
  await page.getByTestId(`swap-date-chip-${seed.targetDate}`).click()
  await expect(page.getByTestId('swap-target-period')).toBeVisible()

  expect(consoleErrors).toEqual([])
})

test('过去日期与课卡不可选：第①步默认今天，昨天置灰', async ({ page }) => {
  const api = await newContext()
  const seed = await seedPastWeekScenario(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()

  // 默认日期 = 测试进程按本地时区算出的今天（不是 UTC 的昨天）。
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await expect(dateInput).toHaveValue(seed.today)

  // 日期面板：今天可选、昨天禁用（跨月时先翻到上个月）。
  await dateInput.click()
  const panel = page.locator('.el-picker-panel:visible').last()
  const table = panel.locator('.el-date-table')
  await expect(table).toBeVisible()
  const todayCell = table.locator('td.available.today').first()
  await expect(todayCell).toBeVisible()
  await expect(todayCell).not.toHaveClass(/disabled/)

  const yesterday = shiftDate(seed.today, -1)
  const yesterdayDay = Number(yesterday.slice(8))
  if (yesterday.slice(0, 7) === seed.today.slice(0, 7)) {
    const yesterdayCell = table
      .locator('td.disabled:not(.prev-month):not(.next-month)', {
        hasText: new RegExp(`^\\s*${yesterdayDay}\\s*$`),
      })
      .first()
    await expect(yesterdayCell).toHaveClass(/disabled/)
  } else {
    await panel.locator('.el-date-picker__prev-btn').click()
    const yesterdayCell = table
      .locator('td.disabled:not(.prev-month):not(.next-month)', {
        hasText: new RegExp(`^\\s*${yesterdayDay}\\s*$`),
      })
      .first()
    await expect(yesterdayCell).toHaveClass(/disabled/)
  }
  await page.keyboard.press('Escape')
  await expect(panel).toBeHidden()

  // 默认周：周一~周五各一节课；过去日期的课卡不可用。
  await expect(async () => {
    if ((await page.getByTestId('pick-lesson').count()) === 0) {
      await pickOption(page.locator('.picker-card'), 'teacher-select', seed.teacherName)
    }
    await expect(page.getByTestId('pick-lesson')).toHaveCount(5, { timeout: 5_000 })
  }).toPass({ timeout: 60_000 })
  const cardOn = (date: string) =>
    page.getByTestId(`workbench-cell-${date}-1`).getByTestId('pick-lesson')

  const pastDates = seed.weekDates.filter((date) => date < seed.today)
  for (const date of pastDates) {
    const card = cardOn(date)
    await expect(card).toHaveAttribute('aria-disabled', 'true')
    await expect(card).toBeDisabled()
    await expect(card).toHaveAttribute('title', '已过去的日期不能调整')
    await expect(card).toHaveClass(/is-past/)
  }
  if (pastDates.length > 0) {
    // 强制派发 click 也选不中：next 仍禁用、dock 不出现已选摘要。
    await cardOn(pastDates[0]).dispatchEvent('click')
    await expect(page.getByTestId('wizard-next-1')).toBeDisabled()
    await expect(page.getByTestId('picked-summary')).toHaveCount(0)
  }

  // 今天或未来的课卡正常选中。
  const futureDate = seed.weekDates.find((date) => date >= seed.today)
  if (futureDate) {
    await cardOn(futureDate).click()
  } else {
    // 今天在周末时默认周全是过去：跳到下周一验证未来课卡可选。
    const nextMonday = shiftDate(seed.weekDates[0], 7)
    await dateInput.fill(nextMonday)
    await dateInput.press('Tab')
    await expect(page.getByTestId('pick-lesson')).toHaveCount(5)
    await cardOn(nextMonday).click()
  }
  await expect(page.getByTestId('picked-summary')).toBeVisible()
  await expect(page.getByTestId('wizard-next-1')).toBeEnabled()

  await page.getByTestId('wizard-panel-1').scrollIntoViewIfNeeded()
  await capture(page, 't04-01-past-date-guard')
  expect(consoleErrors).toEqual([])
})
