import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { apiCall, apiLogin, isoDate, newContext, pickOption, publishDraft, solveDraft, uiLogin } from './helpers'
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

/** 按本地时区平移日期。 */
function shiftDate(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00`)
  value.setDate(value.getDate() + days)
  return isoDate(value)
}

/** 与视图 monthDayCn 同款：11月2日（不补零）。 */
function cnDate(date: string): string {
  const [, month, day] = date.split('-')
  return `${Number(month)}月${Number(day)}日`
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function weekdayOf(date: string): string {
  return WEEKDAYS[new Date(`${date}T00:00:00`).getDay()]
}

/** 登录管理员 → 选种子教师 → 跳到来源课所在周，等课卡出现。 */
async function openWizard(page: Page, seed: WizardSeed): Promise<void> {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  // 选中前无副作用（选教师/填日期都不发写请求），整段可重试；成功信号 = 课卡出现。
  // el-select 选中后标签渲染在 selected-item span、filter input 会被清空，
  // 所以不能用 input value 判断是否选中。
  await expect(async () => {
    if ((await page.getByTestId('pick-lesson').count()) === 0) {
      await pickOption(page.locator('.picker-card'), 'teacher-select', seed.teacherName)
      // 种子学期从下周一开始：先选日期再等课表（今天的周没有课）。
      const dateInput = page.getByTestId('lesson-date').locator('input')
      await dateInput.fill(seed.lessonDate)
      await dateInput.press('Tab')
    }
    await expect(page.getByTestId('pick-lesson').first()).toBeVisible({ timeout: 5_000 })
  }).toPass({ timeout: 60_000 })
}

/** 来源格（周三第 1 节）里的课卡按钮。 */
function sourceCard(page: Page, seed: WizardSeed): Locator {
  return page.getByTestId(`workbench-cell-${seed.lessonDate}-1`).getByTestId('pick-lesson')
}

/** 真实鼠标管线：按住来源卡 → 移到目标格中心 → 松手（票 06 同节拍）。 */
async function dragCardToCell(page: Page, from: Locator, to: Locator): Promise<void> {
  await from.scrollIntoViewIfNeeded()
  await to.scrollIntoViewIfNeeded()
  const fromBox = await from.boundingBox()
  const toBox = await to.boundingBox()
  expect(fromBox).toBeTruthy()
  expect(toBox).toBeTruthy()
  await page.mouse.move(fromBox!.x + fromBox!.width / 2, fromBox!.y + fromBox!.height / 2)
  await page.mouse.down()
  await page.waitForTimeout(50)
  await page.mouse.move(toBox!.x + toBox!.width / 2, toBox!.y + toBox!.height / 2, { steps: 10 })
  await page.mouse.up()
  await page.waitForTimeout(50)
}

test('步骤1：周课表点选、下一步解锁、步骤条与单面板渲染', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWizard(page, seed)

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
  await sourceCard(page, seed).click()
  await expect(sourceCard(page, seed)).toHaveAttribute('aria-pressed', 'true')
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
})

test('320px 以下改为按天列表，网格不渲染', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWizard(page, seed)

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
})

test('动作卡：两方向来回切，切换链接在表单上方且 1280×720 首屏可见', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWizard(page, seed)

  await sourceCard(page, seed).click()
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

  // 来回切回代课，表单仍可用。
  await swapLink.click()
  await expect(coverForm).toBeVisible()
})

test('代课候选：排除当前教师、同科排前、跨科标记、禁用候选原因可见', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWizard(page, seed)

  await sourceCard(page, seed).click()
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
  await capture(page, 't01-04-step2-cover-candidates')
})

test('调课窗口 chips：含当天共 7 天、周末置灰、选日期拉目标节次、空格日提示', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWizard(page, seed)

  await sourceCard(page, seed).click()
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
})

test('拖拽有效：预选调课并带出目标日期/节次，落在步骤 2 不自动进步骤 3', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWizard(page, seed)

  const panel2 = page.getByTestId('wizard-panel-2')
  const target = page.getByTestId(`workbench-cell-${seed.targetDate}-1`)
  // 拖拽零写入、可整体重试（CI 负载下 pointerup 偶发丢失）。
  await expect(async () => {
    if ((await panel2.count()) === 0) {
      await dragCardToCell(page, sourceCard(page, seed), target)
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
})

test('拖拽无效：空格与跨班给出原因，留在步骤 1 且零请求', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWizardScenario(api)

  const swapPlanRequests: string[] = []
  const swapTargetRequests: string[] = []
  page.on('request', (request) => {
    const url = request.url()
    if (url.includes('/substitution-workbench/swap-plans')) swapPlanRequests.push(url)
    if (url.includes('/substitution-workbench/swap-targets')) swapTargetRequests.push(url)
  })

  await openWizard(page, seed)

  // 空格（周一第 1 节）：原因条 + 留在步骤 1 + 未选中任何课。
  const mondayCell = page.getByTestId(`workbench-cell-${shiftDate(seed.lessonDate, -2)}-1`)
  await expect(async () => {
    await dragCardToCell(page, sourceCard(page, seed), mondayCell)
    await expect(page.getByTestId('swap-drop-invalid')).toContainText('空格', { timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await expect(page.getByTestId('wizard-next-1')).toBeDisabled()

  // 跨班（周四第 1 节是高一2班的课）：原因里点出目标班级。
  const crossCell = page.getByTestId(`workbench-cell-${seed.crossDate}-1`)
  await expect(async () => {
    await dragCardToCell(page, sourceCard(page, seed), crossCell)
    await expect(page.getByTestId('swap-drop-invalid'))
      .toContainText(`跨班：目标格是${seed.class2Name}的课`, { timeout: 1_000 })
  }).toPass({ timeout: 30_000 })
  await expect(page.getByTestId('wizard-panel-1')).toBeVisible()
  await capture(page, 't01-07-drop-invalid-reason')

  // 零写入：没有 swap-plans，也没有 swap-targets 请求。
  expect(swapPlanRequests).toHaveLength(0)
  expect(swapTargetRequests).toHaveLength(0)
})

test('①②步全流程走查：控制台零错误', async ({ page }) => {
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => consoleErrors.push(`pageerror: ${error}`))

  const api = await newContext()
  const seed = await seedWizardScenario(api)
  await openWizard(page, seed)

  // 步骤 1 → 代课：选候选、填原因。
  await sourceCard(page, seed).click()
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
