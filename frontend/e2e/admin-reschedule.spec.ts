import { expect, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'
import { API, apiCall, apiLogin, newContext, publishDraft, solveDraft, uiLogin } from './helpers'

/** 不抛错的原始调用：冻结路径的 409 需要读取状态码与错误消息。 */
async function apiCallRaw(
  context: APIRequestContext,
  token: string,
  path: string,
  data: unknown,
): Promise<{ status: number; body: any }> {
  const response: APIResponse = await context.post(`${API}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    data,
  })
  const text = await response.text()
  return { status: response.status(), body: text ? JSON.parse(text) : null }
}

/**
 * 票 07：已调课实例继续调课或代课。以当前实际安排为起点发起再次调整：
 * 移动后的课再移动（修订调课）、调课后代课、旧环已开课成员的冻结与可调整结果。
 * 所有重复修订都是新版本——预览 → 确认 → 原子替换，历史全留。
 */

interface RescheduleSeed {
  semesterId: number
  classId: number
  teacherChineseId: number
  teacherChineseName: string
  teacherSpareId: number
  teacherSpareName: string
  entryChineseId: number
  entryEnglishId: number
  wednesday: string
  friday: string
  nextMonday: string
  fridayPeriodId: number
  nextMondayPeriodId: number
}

/**
 * 票 07 种子：高一1班语文周三第 1 节（张）、数学周五第 1 节（王）、英语周一第 1 节
 * （李），另备无课的丙老师。{@code past} 直接把学期锚在 2025-09-01（全部已开课，
 * 用于冻结路径；首次调课走教师自助旧路径，不经管理端截止检查）。
 */
async function seedRescheduleScenario(
  api: APIRequestContext,
  past = false,
): Promise<RescheduleSeed> {
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)
  let monday: Date
  if (past) {
    monday = new Date('2025-09-01T00:00:00')
  } else {
    monday = new Date()
    monday.setDate(monday.getDate() + ((8 - monday.getDay()) % 7 || 7))
  }
  const iso = (value: Date) => {
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const day = String(value.getDate()).padStart(2, '0')
    return `${value.getFullYear()}-${month}-${day}`
  }
  const day = (offset: number) => {
    const shifted = new Date(monday)
    shifted.setDate(monday.getDate() + offset)
    return iso(shifted)
  }
  const wednesday = day(2)
  const friday = day(4)
  const nextMonday = day(7)
  const end = new Date(monday)
  end.setDate(monday.getDate() + 100)

  const semester = await apiCall(api, token, 'post', '/semesters', {
    name: `E2E再次调整-${suffix}`,
    startDate: iso(monday),
    endDate: iso(end),
  })
  await apiCall(api, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(api, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 2,
    afternoonCount: 0,
    eveningCount: 0,
    days: [1, 2, 3, 4, 5],
  })

  const grade = await apiCall(api, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `高一${suffix}`,
    sortOrder: 1,
  })
  const adminClass = await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一1班${suffix}`,
    studentCount: 45,
  })
  const teacherChinese = await apiCall(api, token, 'post', '/teachers', {
    name: `再调张${suffix}`,
    employeeNo: `RS1-${suffix}`,
    subject: '语文',
  })
  const teacherMath = await apiCall(api, token, 'post', '/teachers', {
    name: `再调王${suffix}`,
    employeeNo: `RS2-${suffix}`,
    subject: '数学',
  })
  const teacherEnglish = await apiCall(api, token, 'post', '/teachers', {
    name: `再调李${suffix}`,
    employeeNo: `RS3-${suffix}`,
    subject: '英语',
  })
  const teacherSpare = await apiCall(api, token, 'post', '/teachers', {
    name: `再调丙${suffix}`,
    employeeNo: `RS4-${suffix}`,
    subject: '语文',
  })
  const chinese = await apiCall(api, token, 'post', '/courses', {
    name: `语文${suffix}`,
    subject: '语文',
    allowConsecutive: false,
  })
  const math = await apiCall(api, token, 'post', '/courses', {
    name: `数学${suffix}`,
    subject: '数学',
    allowConsecutive: false,
  })
  const english = await apiCall(api, token, 'post', '/courses', {
    name: `英语${suffix}`,
    subject: '英语',
    allowConsecutive: false,
  })
  const roomA = await apiCall(api, token, 'post', '/rooms', {
    name: `再调教室A${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  const roomB = await apiCall(api, token, 'post', '/rooms', {
    name: `再调教室B${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  for (const [courseId, teacherId] of [
    [chinese.id, teacherChinese.id],
    [math.id, teacherMath.id],
    [english.id, teacherEnglish.id],
  ]) {
    await apiCall(api, token, 'post', '/assignments', {
      classId: adminClass.id,
      courseId,
      teacherId,
      weeklyLessons: 1,
    })
  }

  const periodRows = await apiCall(api, token, 'get', `/semesters/${semester.id}/periods`)
  const periodByDayNo = new Map<string, any>()
  for (const row of periodRows) {
    periodByDayNo.set(`${row.dayOfWeek}-${row.periodNo}`, row)
  }
  const wednesdayPeriod = periodByDayNo.get('3-1')
  const fridayPeriod = periodByDayNo.get('5-1')
  const nextMondayPeriod = periodByDayNo.get('1-1')
  if (!wednesdayPeriod || !fridayPeriod || !nextMondayPeriod) {
    throw new Error('missing periods for the reschedule seed')
  }
  const draftId = await solveDraft(api, semester.id)
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  const byCourse = new Map<number, any>()
  for (const lesson of detail.lessons) {
    if (lesson.classId === adminClass.id) byCourse.set(lesson.courseId, lesson)
  }
  for (const [courseId, periodId, roomId] of [
    [chinese.id, wednesdayPeriod.id, roomA.id],
    [math.id, fridayPeriod.id, roomB.id],
    [english.id, nextMondayPeriod.id, roomA.id],
  ]) {
    const lesson = byCourse.get(courseId)
    if (!lesson) throw new Error('missing draft lesson for the reschedule seed')
    await apiCall(api, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
      periodId,
      roomId,
      force: true,
    })
  }
  await publishDraft(api, draftId)

  const entries = await apiCall(
    api, token, 'get', `/timetable/class/${adminClass.id}`, undefined, { view: 'baseline' },
  )
  const entryByCourse = (courseId: number) =>
    entries.find((row: any) => row.courseId === courseId)?.id
  const entryChineseId = entryByCourse(chinese.id)
  const entryEnglishId = entryByCourse(english.id)
  if (!entryChineseId || !entryEnglishId) {
    throw new Error('published timetable misses seeded entries')
  }
  return {
    semesterId: semester.id,
    classId: adminClass.id,
    teacherChineseId: teacherChinese.id,
    teacherChineseName: teacherChinese.name,
    teacherSpareId: teacherSpare.id,
    teacherSpareName: teacherSpare.name,
    entryChineseId,
    entryEnglishId,
    wednesday,
    friday,
    nextMonday,
    fridayPeriodId: fridayPeriod.id,
    nextMondayPeriodId: nextMondayPeriod.id,
  }
}

/** 首次调课（语文 周三 1 → 周五 1）走管理端 API 亲自确认，返回申请 id。 */
async function firstSwapViaApi(api: APIRequestContext, seed: RescheduleSeed): Promise<number> {
  const token = await apiLogin(api)
  const generated = await apiCall(api, token, 'post', '/substitution-workbench/swap-plans', {
    entryId: seed.entryChineseId,
    lessonDate: seed.wednesday,
    targetDate: seed.friday,
    targetPeriodId: seed.fridayPeriodId,
    reason: '运动会调课',
  })
  const preview = await apiCall(api, token, 'post', '/substitution-workbench/swap-previews', {
    entryId: seed.entryChineseId,
    lessonDate: seed.wednesday,
    planId: generated.plans[0].planId,
    reason: '运动会调课',
  })
  const confirmed = await apiCall(
    api, token, 'post', `/substitution-workbench/previews/${preview.voucherId}/confirm-swap`, {},
  )
  return confirmed.requestId
}

/** 工作台选教师 + 授课日期 + 课节（票 07 的再次操作从当前实际安排的课进入）。 */
async function openMovedLesson(
  page: Page,
  seed: RescheduleSeed,
  lessonDate: string,
) {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await page.getByTestId('teacher-select').click()
  await page.getByTestId('teacher-select').locator('input').fill(seed.teacherChineseName)
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(dropdown.getByText(seed.teacherChineseName, { exact: true })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('workbench-timetable')).toBeVisible({ timeout: 20_000 })
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()
  await page.getByTestId('pick-lesson').first().click()
}

test('已移动的课再次调课：以周五落点为起点移到下周一，旧环其余成员保持', async ({ page }) => {
  const api = await newContext()
  const seed = await seedRescheduleScenario(api)
  const firstRequestId = await firstSwapViaApi(api, seed)

  // 从周五落点进入（当前实际安排里的语文），再次指定目标 = 下周一（英语的基线槽）。
  await openMovedLesson(page, seed, seed.friday)
  await page.getByTestId('choose-swap').click()
  await expect(page.getByTestId('swap-planner')).toBeVisible()
  const targetDateInput = page.getByTestId('swap-target-date').locator('input')
  await targetDateInput.fill(seed.nextMonday)
  await targetDateInput.press('Tab')
  await expect(page.getByTestId('swap-target-period')).toBeEnabled()
  await page.getByTestId('swap-target-period').click()
  const targetDropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(targetDropdown.locator('.el-select-dropdown__item').first()).toBeVisible()
  await targetDropdown
    .locator('.el-select-dropdown__item')
    .filter({ hasText: '英语' })
    .first()
    .click()
  await page.getByTestId('swap-reason').fill('再次调整到下周一')
  await page.getByTestId('generate-swap-plans').click()
  await expect(page.getByTestId('swap-plan-card').first()).toContainText('2 人环')
  await page.getByTestId('choose-swap-plan-0').click()
  await expect(page.getByTestId('swap-compare')).toBeVisible()
  await page.getByTestId('swap-personal-confirm').click()
  await expect(page.getByTestId('workbench-result')).toContainText('亲自确认已生效')

  // API 侧：修订申请绑定前驱（RESCHEDULE_REVISION），历史链可追溯。
  const token = await apiLogin(api)
  const effective = await apiCall(api, token, 'get', '/substitution-requests', undefined, {
    status: 'EFFECTIVE',
  })
  const revision = effective.find((row: any) => row.revisesRequestId === firstRequestId)
  expect(revision).toBeTruthy()
  expect(revision.revisionKind).toBe('RESCHEDULE_REVISION')

  // 净差行：语文行的来源日 = 基线周三（而非周五落点），落点 = 下周一。
  const swaps = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  const chineseRow = swaps.find((row: any) => row.entryId === seed.entryChineseId)
  // 净差行：来源日 = 基线周三；落点标注 = 下周一（toDayOfWeek 1）。
  expect(chineseRow.lessonDate).toBe(seed.wednesday)
  expect(chineseRow.toDayOfWeek).toBe(1)

  // 最终安排：下周一语文、周五英语；首次环的数学（未被本次触及）保持生效。
  const mondayEntries = await apiCall(
    api, token, 'get', `/timetable/class/${seed.classId}`, undefined,
    { view: 'latest', date: seed.nextMonday },
  )
  const mondayChinese = mondayEntries.find((row: any) => row.courseName?.includes('语文'))
  expect(mondayChinese.substituted).toBe(true)
  const fridayEntries = await apiCall(
    api, token, 'get', `/timetable/class/${seed.classId}`, undefined,
    { view: 'latest', date: seed.friday },
  )
  const fridayEnglish = fridayEntries.find((row: any) => row.courseName?.includes('英语'))
  expect(fridayEnglish).toBeTruthy()
})

test('调课后代课：从周五落点指定丙老师，修订横幅标明取代的调课申请', async ({ page }) => {
  const api = await newContext()
  const seed = await seedRescheduleScenario(api)
  const swapRequestId = await firstSwapViaApi(api, seed)

  await openMovedLesson(page, seed, seed.friday)
  await page.getByTestId('choose-cover').click()
  await expect(page.getByTestId('substitute-picker')).toBeVisible()
  await page.getByTestId(`choose-${seed.teacherSpareId}`).click()
  await page.getByTestId('preview-reason').fill('周五临时有事')
  await page.getByTestId('generate-preview').click()

  // 修订链（COVER-on-SWAP）：横幅明确被取代的调课申请。
  await expect(page.getByTestId('revision-banner')).toContainText(`取代申请 #${swapRequestId}`)
  await page.getByTestId('personal-confirm').click()
  await expect(page.getByTestId('workbench-result')).toContainText('亲自确认已生效')

  // 当前有效结果：周五的语文由丙承担；SWAP 行保留（来源日移出语义不受影响）。
  const token = await apiLogin(api)
  const fridayEntries = await apiCall(
    api, token, 'get', `/timetable/class/${seed.classId}`, undefined,
    { view: 'latest', date: seed.friday },
  )
  const fridayChinese = fridayEntries.find((row: any) => row.courseName?.includes('语文'))
  expect(fridayChinese.teacherId).toBe(seed.teacherSpareId)
  const swaps = await apiCall(api, token, 'get', '/substitutions', undefined, { type: 'SWAP' })
  expect(swaps.length).toBe(2)
  const requests = await apiCall(api, token, 'get', '/substitution-requests', undefined, {
    status: 'EFFECTIVE',
  })
  const cover = requests.find(
    (row: any) => row.revisesRequestId === swapRequestId && row.type === 'COVER',
  )
  expect(cover).toBeTruthy()
  expect(cover.revisionKind).toBe('CHANGE_SUBSTITUTE')
})

test('旧环成员已开课：冻结实例不可再操作，预览明确拒绝且零写入', async () => {
  const api = await newContext()
  const seed = await seedRescheduleScenario(api, true)
  const admin = await apiLogin(api)
  // 学期锚在 2025-09-01：全部授课时刻已过。首次调课走教师自助旧路径（无截止检查）。
  const username = `respast_${Math.random().toString(36).slice(2, 8)}`
  await apiCall(api, admin, 'post', '/users', {
    username,
    password: 'teacher123',
    displayName: username,
    roleCodes: ['teacher'],
    teacherId: seed.teacherChineseId,
  })
  const teacherToken = await apiLogin(api, username, 'teacher123')
  const request = await apiCall(api, teacherToken, 'post', '/substitution-requests', {
    entryId: seed.entryChineseId,
    effectiveDate: seed.wednesday,
    reason: '旧环种子',
    type: 'SWAP',
    targetPeriodId: seed.fridayPeriodId,
  })
  const generated = await apiCall(
    api, admin, 'post', `/substitution-requests/${request.id}/plans`, {},
  )
  await apiCall(api, admin, 'post', `/substitution-plans/${generated.plans[0].id}/approve`, {})

  const swapsBefore = await apiCall(api, admin, 'get', '/substitutions', undefined, {
    type: 'SWAP',
  })
  expect(swapsBefore.length).toBe(2)

  // 已开课环成员（周三的数学 + 周五的语文）冻结：再次调课与代课的预览都明确拒绝。
  const reschedule = await apiCallRaw(api, admin, '/substitution-workbench/swap-plans', {
    entryId: seed.entryChineseId,
    lessonDate: seed.friday,
    targetDate: seed.nextMonday,
    targetPeriodId: seed.nextMondayPeriodId,
    reason: '已开课后再调课',
  })
  expect(reschedule.status).toBe(409)
  expect(reschedule.body.message).toContain('开课')
  const cover = await apiCallRaw(api, admin, '/substitution-workbench/previews', {
    entryId: seed.entryChineseId,
    lessonDate: seed.friday,
    substituteTeacherId: seed.teacherSpareId,
    reason: '已开课后代课',
  })
  expect(cover.status).toBe(409)
  expect(cover.body.message).toContain('开课')

  // 零写入：生效行仍是首次调课的两条。
  const swapsAfter = await apiCall(api, admin, 'get', '/substitutions', undefined, {
    type: 'SWAP',
  })
  expect(swapsAfter.length).toBe(2)
})
