import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test'
import { API, apiCall, apiLogin, newContext, publishDraft, solveDraft, uiLogin } from './helpers'

/** 不抛错的原始调用：截止路径的 409 需要读取状态码与错误消息。 */
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
 * 票 07：开课截止（保留功能）。以过去学期为锚：所有授课时刻已过，预览与方案
 * 生成都被服务端权威时钟拒绝（零写入），工作台也不允许选中过去的课卡。
 */

interface RescheduleSeed {
  semesterId: number
  classId: number
  teacherChineseId: number
  teacherChineseName: string
  teacherSpareId: number
  entryChineseId: number
  wednesday: string
  friday: string
  fridayPeriodId: number
}

/**
 * 种子：过去学期（2025-09-01），高一1班语文周三第 1 节（张）、数学周五第 1 节（王）、
 * 英语周一第 1 节（李），另备无课的丙老师。全部日期都已开课。
 */
async function seedRescheduleScenario(api: APIRequestContext): Promise<RescheduleSeed> {
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)
  const monday = new Date('2025-09-01T00:00:00')
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
  const end = new Date(monday)
  end.setDate(monday.getDate() + 100)

  const semester = await apiCall(api, token, 'post', '/semesters', {
    name: `E2E截止-${suffix}`,
    startDate: iso(monday),
    endDate: iso(end),
  })
  await apiCall(api, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(api, token, 'post', `/semesters/${semester.id}/periods/default`, {
    periodCount: 2,
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
    name: `截止张${suffix}`,
    employeeNo: `RS1-${suffix}`,
    subject: '语文',
  })
  const teacherMath = await apiCall(api, token, 'post', '/teachers', {
    name: `截止王${suffix}`,
    employeeNo: `RS2-${suffix}`,
    subject: '数学',
  })
  const teacherEnglish = await apiCall(api, token, 'post', '/teachers', {
    name: `截止李${suffix}`,
    employeeNo: `RS3-${suffix}`,
    subject: '英语',
  })
  const teacherSpare = await apiCall(api, token, 'post', '/teachers', {
    name: `截止丙${suffix}`,
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
    throw new Error('missing periods for the deadline seed')
  }
  const draftId = await solveDraft(api, semester.id)
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  const byCourse = new Map<number, any>()
  for (const lesson of detail.lessons) {
    if (lesson.classId === adminClass.id) byCourse.set(lesson.courseId, lesson)
  }
  for (const [courseId, periodId] of [
    [chinese.id, wednesdayPeriod.id],
    [math.id, fridayPeriod.id],
    [english.id, nextMondayPeriod.id],
  ]) {
    const lesson = byCourse.get(courseId)
    if (!lesson) throw new Error('missing draft lesson for the deadline seed')
    await apiCall(api, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
      periodId,
      force: true,
    })
  }
  await publishDraft(api, draftId)

  const entries = await apiCall(
    api, token, 'get', `/timetable/class/${adminClass.id}`, undefined, { view: 'baseline' },
  )
  const entryChineseId = entries.find((row: any) => row.courseId === chinese.id)?.id
  if (!entryChineseId) {
    throw new Error('published timetable misses seeded entries')
  }
  return {
    semesterId: semester.id,
    classId: adminClass.id,
    teacherChineseId: teacherChinese.id,
    teacherChineseName: teacherChinese.name,
    teacherSpareId: teacherSpare.id,
    entryChineseId,
    wednesday,
    friday,
    fridayPeriodId: fridayPeriod.id,
  }
}

test('已开课的实例不可再操作：预览与方案都被截止拒绝且零写入', async ({ page }) => {
  const api = await newContext()
  const seed = await seedRescheduleScenario(api)
  const admin = await apiLogin(api)

  // 学期锚在过去：全部授课时刻已过。管理端再次调课与代课的预览都按权威时钟拒绝。
  const reschedule = await apiCallRaw(api, admin, '/substitution-workbench/swap-plans', {
    entryId: seed.entryChineseId,
    lessonDate: seed.wednesday,
    targetDate: seed.friday,
    targetPeriodId: seed.fridayPeriodId,
    reason: '已开课后再调课',
  })
  expect(reschedule.status).toBe(409)
  expect(reschedule.body.message).toContain('开课')
  const cover = await apiCallRaw(api, admin, '/substitution-workbench/previews', {
    entryId: seed.entryChineseId,
    lessonDate: seed.wednesday,
    substituteTeacherId: seed.teacherSpareId,
    reason: '已开课后代课',
  })
  expect(cover.status).toBe(409)
  expect(cover.body.message).toContain('开课')

  // 浏览器固定在种子周五：周三课明确已过去，不依赖运行当天是周几。
  await page.clock.setFixedTime(new Date(`${seed.friday}T12:00:00`))

  // 新界面语义（过去日期拦截）：工作台不再允许选中过去日期/课卡，
  // 用户在界面上就无法发起已开课的调整；服务端截止仍由上面的 API 断言兜底。
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitution')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await page.getByTestId('teacher-select').click()
  await page.getByTestId('teacher-select').locator('input').fill(seed.teacherChineseName)
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(dropdown.getByText(seed.teacherChineseName, { exact: true })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')

  // 手输过去日期会被日期选择器/守卫挡住（值保持今天）；过去课卡不可选中。
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.wednesday)
  await dateInput.press('Tab')
  await expect(dateInput).toHaveValue(seed.friday)
  const cards = page.getByTestId('pick-lesson')
  await expect(cards).toHaveCount(1)
  await expect(cards.first()).toHaveAttribute('aria-disabled', 'true')
  await expect(cards.first()).toBeDisabled()
  await cards.first().dispatchEvent('click')
  await expect(page.getByTestId('wizard-next-1')).toBeDisabled()
  await expect(page.getByTestId('picked-summary')).toHaveCount(0)

  // 零写入：没有任何生效行。
  const swapsAfter = await apiCall(api, admin, 'get', '/substitutions', undefined, {
    type: 'SWAP',
  })
  expect(swapsAfter.length).toBe(0)
})
