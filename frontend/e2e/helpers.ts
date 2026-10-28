import { expect, request, type APIRequestContext, type Locator, type Page } from '@playwright/test'

/**
 * API origin used by direct calls. Defaults to the Playwright config's backend;
 * isolated local runs can point it at another port without touching the specs.
 */
export const API = process.env.PAIBAN_E2E_API ?? 'http://127.0.0.1:8080/api'

const TOUR_KEYS = ['paiban.tour.seen', 'paiban.tour.advanced']

/**
 * Logs in through the real login form. By default both tour flags are written
 * before the app boots, so existing specs are never covered by the new-user
 * tour; specs that assert the tour itself pass `{ tour: true }`.
 */
export async function uiLogin(
  page: Page,
  username: string,
  password: string,
  options: { tour?: boolean } = {},
): Promise<void> {
  if (!options.tour) {
    await page.addInitScript((keys: string[]) => {
      for (const key of keys) localStorage.setItem(key, '1')
    }, TOUR_KEYS)
  }
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

export async function apiLogin(
  context: APIRequestContext,
  username = 'admin',
  password = 'admin123',
): Promise<string> {
  const response = await context.post(`${API}/auth/login`, { data: { username, password } })
  if (!response.ok()) {
    throw new Error(`login failed: ${response.status()} ${await response.text()}`)
  }
  const body = await response.json()
  return body.token as string
}

export async function apiCall(
  context: APIRequestContext,
  token: string,
  method: 'get' | 'post' | 'put' | 'patch' | 'delete',
  path: string,
  data?: unknown,
  params?: Record<string, string | number>,
): Promise<any> {
  const response = await context[method](`${API}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    data,
    params,
  })
  if (!response.ok()) {
    throw new Error(`${method} ${path} -> ${response.status()} ${await response.text()}`)
  }
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

export interface SeedResult {
  semesterId: number
  classId: number
  teacherId: number
  substituteTeacherId: number
  roomId: number
  username: string
  password: string
}

export interface ConstraintSeed {
  semesterId: number
  teachers: { id: number; name: string }[]
  courses: { id: number; name: string }[]
}

/**
 * Semester + timetable + two teachers and two courses for the constraint page.
 * The constraint specs assert entry cards by teacher/course name, so the seeded
 * names are returned (synthetic names only — never a real person).
 */
export async function seedConstraintScenario(context: APIRequestContext): Promise<ConstraintSeed> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E约束-${suffix}`,
    startDate: '2025-09-01',
    endDate: '2026-01-20',
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 4,
    afternoonCount: 4,
    eveningCount: 2,
    days: [1, 2, 3, 4, 5],
  })

  const teachers: { id: number; name: string }[] = []
  for (const [index, base] of ['张伟', '李娜'].entries()) {
    const name = `${base}${suffix}`
    const teacher = await apiCall(context, token, 'post', '/teachers', {
      name,
      employeeNo: `C${index + 1}-${suffix}`,
    })
    teachers.push({ id: teacher.id, name })
  }

  // Courses are global assets (not semester-scoped); the target-type specs pick
  // them by the suffixed name so a previous run's courses cannot be matched.
  const courses: { id: number; name: string }[] = []
  for (const base of ['数学', '语文']) {
    const course = await apiCall(context, token, 'post', '/courses', {
      name: `${base}${suffix}`,
      subject: base,
      allowConsecutive: false,
    })
    courses.push({ id: course.id, name: `${base}${suffix}` })
  }
  return { semesterId: semester.id, teachers, courses }
}

/** Seeds a minimal, solvable school through the public API. */
export async function seedSchool(context: APIRequestContext): Promise<SeedResult> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)

  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E学期-${suffix}`,
    startDate: '2025-09-01',
    endDate: '2026-01-20',
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 4,
    afternoonCount: 4,
    eveningCount: 2,
    days: [1, 2, 3, 4, 5],
  })

  const grade = await apiCall(context, token, 'post', '/grades?semesterId=' + semester.id, {
    name: `高一${suffix}`,
    sortOrder: 1,
  })
  const teacher = await apiCall(context, token, 'post', '/teachers', {
    name: `张老师${suffix}`,
    employeeNo: `T1-${suffix}`,
  })
  const substitute = await apiCall(context, token, 'post', '/teachers', {
    name: `李老师${suffix}`,
    employeeNo: `T2-${suffix}`,
  })
  const adminClass = await apiCall(
    context,
    token,
    'post',
    `/classes?semesterId=${semester.id}`,
    { gradeId: grade.id, name: `高一1班${suffix}`, studentCount: 45 },
  )
  const course = await apiCall(context, token, 'post', '/courses', {
    name: `数学${suffix}`,
    subject: '数学',
    allowConsecutive: false,
  })
  const room = await apiCall(context, token, 'post', '/rooms', {
    name: `教室${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: adminClass.id,
    courseId: course.id,
    teacherId: teacher.id,
    weeklyLessons: 4,
  })

  const username = `e2e_${suffix}`
  await apiCall(context, token, 'post', '/users', {
    username,
    password: 'teacher123',
    displayName: `张老师${suffix}`,
    roleCodes: ['teacher'],
    teacherId: teacher.id,
  })

  return {
    semesterId: semester.id,
    classId: adminClass.id,
    teacherId: teacher.id,
    substituteTeacherId: substitute.id,
    roomId: room.id,
    username,
    password: 'teacher123',
  }
}

export async function newContext(): Promise<APIRequestContext> {
  return request.newContext()
}

/** Solves the seeded semester and returns the draft id without publishing it. */
export async function solveDraft(context: APIRequestContext, semesterId: number): Promise<number> {
  const token = await apiLogin(context)
  const job = await apiCall(
    context,
    token,
    'post',
    `/scheduling-jobs?semesterId=${semesterId}`,
    { maxSeconds: 5 },
  )
  const deadline = Date.now() + 120_000
  let draftId = -1
  while (Date.now() < deadline) {
    const current = await apiCall(context, token, 'get', `/scheduling-jobs/${job.id}`)
    if (current.status === 'COMPLETED') {
      draftId = current.draftId
      break
    }
    if (current.status === 'FAILED' || current.status === 'CANCELLED') {
      throw new Error(`scheduling failed: ${current.errorMessage}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  if (draftId < 0) {
    throw new Error('scheduling timed out')
  }
  return draftId
}

export async function publishDraft(context: APIRequestContext, draftId: number): Promise<void> {
  const token = await apiLogin(context)
  await apiCall(context, token, 'post', `/timetable-drafts/${draftId}/publish`)
}

/** Solves the seeded semester and publishes the resulting draft. */
export async function solveAndPublish(context: APIRequestContext, seed: SeedResult): Promise<number> {
  const draftId = await solveDraft(context, seed.semesterId)
  await publishDraft(context, draftId)
  return draftId
}

export function dateFor(dayOfWeek: number): string {
  const date = new Date('2025-09-01T00:00:00')
  date.setDate(date.getDate() + (dayOfWeek - 1))
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export interface SwapScenario {
  semesterId: number
  class1: number
  class2: number
  teachers: { a: number; b: number; c: number }
  courses: { chinese: number; math: number; english: number }
  rooms: number[]
  /** Period ids keyed "<dayOfWeek>-<periodNo>". */
  periods: Record<string, number>
  username: string
  password: string
}

/**
 * Deterministic published timetable for swap E2E. The solver places the four
 * lessons, then a forced move puts them exactly where the scenario needs them,
 * so a same-day or cross-day cycle is guaranteed before the browser starts.
 *
 * Class 1, Friday: period 1 Chinese (teacher A), period 2 Math (teacher B).
 * Class 1, Monday: period 2 English (teacher C).
 * Class 2, Friday: Math (teacher B) at period 3, or period 1 when the same-day
 * cycle must be blocked.
 */
export async function seedSwapScenario(
  context: APIRequestContext,
  options: { blockSameDay: boolean },
): Promise<SwapScenario> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E调课-${suffix}`,
    startDate: '2025-09-01',
    endDate: '2026-01-20',
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 2,
    afternoonCount: 2,
    eveningCount: 0,
    days: [1, 2, 3, 4, 5],
  })
  const periodRows = await apiCall(context, token, 'get', `/semesters/${semester.id}/periods`)
  const periods: Record<string, number> = {}
  for (const row of periodRows) {
    periods[`${row.dayOfWeek}-${row.periodNo}`] = row.id
  }

  const grade = await apiCall(context, token, 'post', `/grades?semesterId=${semester.id}`, {
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
  const teacherA = await apiCall(context, token, 'post', '/teachers', {
    name: `语文老师${suffix}`,
    employeeNo: `A-${suffix}`,
    subject: '语文',
  })
  const teacherB = await apiCall(context, token, 'post', '/teachers', {
    name: `数学老师${suffix}`,
    employeeNo: `B-${suffix}`,
    subject: '数学',
  })
  const teacherC = await apiCall(context, token, 'post', '/teachers', {
    name: `英语老师${suffix}`,
    employeeNo: `C-${suffix}`,
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
  const rooms: number[] = []
  for (let index = 1; index <= 4; index++) {
    const room = await apiCall(context, token, 'post', '/rooms', {
      name: `调课教室${index}${suffix}`,
      capacity: 50,
      type: 'NORMAL',
    })
    rooms.push(room.id)
  }
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: chinese.id,
    teacherId: teacherA.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: math.id,
    teacherId: teacherB.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: english.id,
    teacherId: teacherC.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class2.id,
    courseId: math.id,
    teacherId: teacherB.id,
    weeklyLessons: 1,
  })

  const username = `swape2e_${suffix}`
  await apiCall(context, token, 'post', '/users', {
    username,
    password: 'teacher123',
    displayName: `语文老师${suffix}`,
    roleCodes: ['teacher'],
    teacherId: teacherA.id,
  })

  const draftId = await solveDraft(context, semester.id)
  const detail = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const byKey = new Map<string, any>()
  for (const lesson of detail.lessons) {
    byKey.set(`${lesson.classId}-${lesson.courseId}`, lesson)
  }
  const place = async (classId: number, courseId: number, key: string, roomId: number) => {
    const lesson = byKey.get(`${classId}-${courseId}`)
    if (!lesson) {
      throw new Error(`missing draft lesson for class ${classId} course ${courseId}`)
    }
    await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
      periodId: periods[key],
      roomId,
      force: true,
    })
  }
  await place(class1.id, chinese.id, '5-1', rooms[0])
  await place(class1.id, math.id, '5-2', rooms[1])
  await place(class1.id, english.id, '1-2', rooms[2])
  await place(class2.id, math.id, options.blockSameDay ? '5-1' : '5-3', rooms[3])
  await publishDraft(context, draftId)

  return {
    semesterId: semester.id,
    class1: class1.id,
    class2: class2.id,
    teachers: { a: teacherA.id, b: teacherB.id, c: teacherC.id },
    courses: { chinese: chinese.id, math: math.id, english: english.id },
    rooms,
    periods,
    username,
    password: 'teacher123',
  }
}


export async function classEntries(
  context: APIRequestContext,
  classId: number,
): Promise<Array<{ id: number; dayOfWeek: number; periodNo: number; courseName: string }>> {
  const token = await apiLogin(context)
  return apiCall(context, token, 'get', `/timetable/class/${classId}`)
}

/**
 * Picks one option from an Element Plus select by its exact label. The dropdown is
 * teleported to <body>, so only the trigger is scoped to the card; the last visible
 * dropdown is the one the click just opened. Shared by all constraint specs so a
 * widget change is one edit, not seven.
 */
export async function pickOption(
  scope: Locator,
  testId: string,
  text: string,
): Promise<void> {
  const page = scope.page()
  const select = scope.getByTestId(testId)
  await select.click()
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  const input = select.locator('input')
  if (await input.isEditable().catch(() => false)) {
    // A filterable select is the deterministic path: type the exact name, then pick
    // the single filtered row with the keyboard. Clicking a teleported option can be
    // lost when the popper re-renders under full-suite load.
    await input.fill(text)
    const filtered = dropdown.getByText(text, { exact: true }).first()
    await expect(filtered).toBeVisible()
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('Enter')
    await expect(dropdown).toBeHidden()
    return
  }
  // getByText normalises whitespace; a raw ^text$ regex does not, and the
  // teleported option can carry padding/newlines the visible label hides.
  const option = dropdown.getByText(text, { exact: true }).first()
  await expect(option).toBeVisible()
  // The teleported popper can fail Playwright's actionability check while moving;
  // force-clicking then hits a neighbour. Trigger the resolved option's own click
  // handler instead, so the exact element receives the event.
  await option.evaluate((element) => (element as HTMLElement).click())
}

export interface WorkbenchSeed {
  semesterId: number
  classId: number
  teacherId: number
  teacherName: string
  /** 无课教师：工作台候选里的默认代课人。 */
  substituteTeacherId: number
  substituteTeacherName: string
  entryId: number
  lessonDate: string
  username: string
  password: string
}

/**
 * 票 03 调代工作台的种子：学期从下周一拱到未来（服务端开课截止用权威时钟，
 * 过去的日期会被正确拒绝），语文课一节，另备一位无课代课人。
 */
export async function seedWorkbenchScenario(context: APIRequestContext): Promise<WorkbenchSeed> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const monday = new Date()
  monday.setDate(monday.getDate() + ((8 - monday.getDay()) % 7 || 7))
  const iso = (value: Date) => {
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const day = String(value.getDate()).padStart(2, '0')
    return `${value.getFullYear()}-${month}-${day}`
  }
  const wednesday = new Date(monday)
  wednesday.setDate(monday.getDate() + 2)
  const end = new Date(monday)
  end.setDate(monday.getDate() + 100)

  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E工作台-${suffix}`,
    startDate: iso(monday),
    endDate: iso(end),
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 4,
    afternoonCount: 4,
    eveningCount: 2,
    days: [1, 2, 3, 4, 5],
  })

  const grade = await apiCall(context, token, 'post', '/grades?semesterId=' + semester.id, {
    name: `高一${suffix}`,
    sortOrder: 1,
  })
  const teacher = await apiCall(context, token, 'post', '/teachers', {
    name: `张老师${suffix}`,
    employeeNo: `W1-${suffix}`,
    subject: '语文',
  })
  const substitute = await apiCall(context, token, 'post', '/teachers', {
    name: `李老师${suffix}`,
    employeeNo: `W2-${suffix}`,
  })
  const adminClass = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一1班${suffix}`,
    studentCount: 45,
  })
  const course = await apiCall(context, token, 'post', '/courses', {
    name: `语文${suffix}`,
    subject: '语文',
    allowConsecutive: false,
  })
  const room = await apiCall(context, token, 'post', '/rooms', {
    name: `工作台教室${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: adminClass.id,
    courseId: course.id,
    teacherId: teacher.id,
    weeklyLessons: 2,
  })

  const username = `wbe2e_${suffix}`
  await apiCall(context, token, 'post', '/users', {
    username,
    password: 'teacher123',
    displayName: `张老师${suffix}`,
    roleCodes: ['teacher'],
    teacherId: teacher.id,
  })

  // 求解后强制把这节课摆到周三第 1 节，保证种子的授课日期是确定事实。
  const periodRows = await apiCall(context, token, 'get', `/semesters/${semester.id}/periods`)
  const wednesdayPeriod = periodRows.find((row: any) => row.dayOfWeek === 3 && row.periodNo === 1)
  if (!wednesdayPeriod) {
    throw new Error('missing Wednesday period for the workbench seed')
  }
  const draftId = await solveDraft(context, semester.id)
  const detail = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const lesson = detail.lessons.find(
    (row: any) => row.classId === adminClass.id && row.courseId === course.id,
  )
  if (!lesson) {
    throw new Error('missing draft lesson for the workbench seed')
  }
  await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
    periodId: wednesdayPeriod.id,
    roomId: room.id,
    force: true,
  })
  await publishDraft(context, draftId)
  const entries = await apiCall(context, token, 'get', `/timetable/class/${adminClass.id}`, undefined, {
    view: 'baseline',
  })
  const wednesdayEntry = entries.find((row: any) => row.dayOfWeek === 3)
  if (!wednesdayEntry) {
    throw new Error('solver did not place a lesson on Wednesday for the workbench seed')
  }
  return {
    semesterId: semester.id,
    classId: adminClass.id,
    teacherId: teacher.id,
    teacherName: teacher.name,
    substituteTeacherId: substitute.id,
    substituteTeacherName: substitute.name,
    entryId: wednesdayEntry.id,
    lessonDate: iso(wednesday),
    username,
    password: 'teacher123',
  }
}

/**
 * Runs one SQL statement against the e2e backend's in-memory H2 database via
 * the H2 web console (enabled only in the e2e profile). This exists so specs
 * can manufacture pre-ticket-01 legacy data (substitution rows without
 * lesson_date); every other seed must go through the public API.
 * The JDBC URL must match application-e2e.yml exactly.
 */
export async function runSql(request: APIRequestContext, sql: string): Promise<void> {
  const base = API.replace(/\/api$/, '')
  const jdbcUrl = 'jdbc:h2:mem:paiban_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'
  const consolePage = await request.get(`${base}/h2-console/`)
  if (!consolePage.ok()) {
    throw new Error(`h2 console unavailable: ${consolePage.status()}`)
  }
  const html = await consolePage.text()
  const sessionId = html.match(/name="jsessionid"\s+value="([^"]+)"/)?.[1]
    ?? html.match(/jsessionid=([a-zA-Z0-9.-]+)/)?.[1]
  if (!sessionId) {
    throw new Error(`h2 console session not found in: ${html.slice(0, 300)}`)
  }
  // H2 控制台的登录表单字段名是 url（JDBC URL）；登录后连接挂在会话上，
  // query.do 只需要 sql。
  const login = await request.post(`${base}/h2-console/login.do?jsessionid=${sessionId}`, {
    form: {
      settingsName: 'Generic H2 (Embedded)',
      setting: 'Generic H2 (Embedded)',
      name: 'Generic H2 (Embedded)',
      driver: 'org.h2.Driver',
      url: jdbcUrl,
      user: 'sa',
      password: '',
    },
  })
  if (!login.ok()) {
    throw new Error(`h2 console login failed: ${login.status()}`)
  }
  const result = await request.post(`${base}/h2-console/query.do?jsessionid=${sessionId}`, {
    form: { sql, maxrows: '1000' },
  })
  const body = await result.text()
  const failed = !result.ok() || /org\.h2\.jdbc|JdbcSQL|Syntax error|Feature not supported/i.test(body)
  if (failed) {
    throw new Error(`h2 sql failed (${result.status()}): ${body.slice(0, 1500)}`)
  }
}


export interface SwapWorkbenchSeed {
  semesterId: number
  classId: number
  teacherChineseId: number
  teacherChineseName: string
  teacherMathId: number
  teacherMathName: string
  entryChineseId: number
  courseChineseName: string
  lessonDate: string
  targetDate: string
}

/**
 * 票 05 调课工作台的种子：未来学期，高一1班周三第 1 节语文（张老师）、
 * 周五第 1 节数学（王老师），二人环无冲突——管理员明确指定目标日期即可成环。
 */
export async function seedSwapWorkbenchScenario(
  context: APIRequestContext,
): Promise<SwapWorkbenchSeed> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const monday = new Date()
  monday.setDate(monday.getDate() + ((8 - monday.getDay()) % 7 || 7))
  const iso = (value: Date) => {
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const dayNo = String(value.getDate()).padStart(2, '0')
    return `${value.getFullYear()}-${month}-${dayNo}`
  }
  const wednesday = new Date(monday)
  wednesday.setDate(monday.getDate() + 2)
  const friday = new Date(monday)
  friday.setDate(monday.getDate() + 4)
  const end = new Date(monday)
  end.setDate(monday.getDate() + 100)

  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E调课台-${suffix}`,
    startDate: iso(monday),
    endDate: iso(end),
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    morningCount: 2,
    afternoonCount: 0,
    eveningCount: 0,
    days: [1, 2, 3, 4, 5],
  })

  const grade = await apiCall(context, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `高一${suffix}`,
    sortOrder: 1,
  })
  const adminClass = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一1班${suffix}`,
    studentCount: 45,
  })
  const teacherChinese = await apiCall(context, token, 'post', '/teachers', {
    name: `调课张${suffix}`,
    employeeNo: `SW1-${suffix}`,
    subject: '语文',
  })
  const teacherMath = await apiCall(context, token, 'post', '/teachers', {
    name: `调课王${suffix}`,
    employeeNo: `SW2-${suffix}`,
    subject: '数学',
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
  const roomA = await apiCall(context, token, 'post', '/rooms', {
    name: `调课台教室A${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  const roomB = await apiCall(context, token, 'post', '/rooms', {
    name: `调课台教室B${suffix}`,
    capacity: 50,
    type: 'NORMAL',
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: adminClass.id,
    courseId: chinese.id,
    teacherId: teacherChinese.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: adminClass.id,
    courseId: math.id,
    teacherId: teacherMath.id,
    weeklyLessons: 1,
  })

  // 求解后强制摆放：语文周三第 1、数学周五第 1（二人环的确定性事实）。
  const periodRows = await apiCall(context, token, 'get', `/semesters/${semester.id}/periods`)
  const wednesdayPeriod = periodRows.find((row: any) => row.dayOfWeek === 3 && row.periodNo === 1)
  const fridayPeriod = periodRows.find((row: any) => row.dayOfWeek === 5 && row.periodNo === 1)
  if (!wednesdayPeriod || !fridayPeriod) {
    throw new Error('missing Wednesday/Friday periods for the swap workbench seed')
  }
  const draftId = await solveDraft(context, semester.id)
  const detail = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const byCourse = new Map<number, any>()
  for (const lesson of detail.lessons) {
    if (lesson.classId === adminClass.id) byCourse.set(lesson.courseId, lesson)
  }
  const chineseLesson = byCourse.get(chinese.id)
  const mathLesson = byCourse.get(math.id)
  if (!chineseLesson || !mathLesson) {
    throw new Error('missing draft lessons for the swap workbench seed')
  }
  await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${chineseLesson.id}`, {
    periodId: wednesdayPeriod.id,
    roomId: roomA.id,
    force: true,
  })
  await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${mathLesson.id}`, {
    periodId: fridayPeriod.id,
    roomId: roomB.id,
    force: true,
  })
  await publishDraft(context, draftId)
  const entries = await apiCall(context, token, 'get', `/timetable/class/${adminClass.id}`, undefined, {
    view: 'baseline',
  })
  const chineseEntry = entries.find((row: any) => row.dayOfWeek === 3)
  if (!chineseEntry) {
    throw new Error('solver did not keep the Wednesday Chinese lesson for the swap workbench seed')
  }
  return {
    semesterId: semester.id,
    classId: adminClass.id,
    teacherChineseId: teacherChinese.id,
    teacherChineseName: teacherChinese.name,
    teacherMathId: teacherMath.id,
    teacherMathName: teacherMath.name,
    entryChineseId: chineseEntry.id,
    courseChineseName: chinese.name,
    lessonDate: iso(wednesday),
    targetDate: iso(friday),
  }
}

// ---------------------------------------------------------------- 票 05/06
// 管理员同班调课的公共流程：选教师 → 选日期 → 选来源课 → 进入调课面板。
// 拖拽 spec（admin-swap-drag）与表单 spec（admin-swap）共用，避免两份漂移。

/**
 * Opens the admin swap flow up to the swap planner panel: log in as admin,
 * search the seeded Chinese teacher, jump to the lesson date and pick the
 * first lesson, then choose the 调课 action.
 */
export async function openSwapFlow(
  page: Page,
  seed: Awaited<ReturnType<typeof seedSwapWorkbenchScenario>>,
): Promise<void> {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  // filterable 下拉：输入带后缀的唯一教师名定位来源课教师。
  await page.getByTestId('teacher-select').click()
  await page.getByTestId('teacher-select').locator('input').fill(seed.teacherChineseName)
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(dropdown.getByText(seed.teacherChineseName, { exact: true })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('workbench-timetable')).toBeVisible({ timeout: 20_000 })

  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()

  // 选来源课 → 选择「调课」动作（区别于票 03 的「指定代课」）。
  await page.getByTestId('pick-lesson').first().click()
  await page.getByTestId('choose-swap').click()
  await expect(page.getByTestId('swap-planner')).toBeVisible()
}

/**
 * Form path for picking the swap target: explicit target date (Friday) and
 * target period (数学) so the seed's two-person cycle is deterministic.
 */
export async function pickTargetAndGenerate(
  page: Page,
  seed: Awaited<ReturnType<typeof seedSwapWorkbenchScenario>>,
): Promise<void> {
  // 明确指定目标日期（周五）与目标课节（数学）——不会被替换成同星期的另一周。
  const targetDateInput = page.getByTestId('swap-target-date').locator('input')
  await targetDateInput.fill(seed.targetDate)
  await targetDateInput.press('Tab')
  await expect(page.getByTestId('swap-target-period')).toBeEnabled()
  await page.getByTestId('swap-target-period').click()
  const targetDropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(targetDropdown.locator('.el-select-dropdown__item').first()).toBeVisible()
  await targetDropdown
    .locator('.el-select-dropdown__item')
    .filter({ hasText: '数学' })
    .first()
    .click()

  await page.getByTestId('swap-reason').fill('运动会调课')
  await page.getByTestId('generate-swap-plans').click()
}
