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

/**
 * Removes every live import recipe. Import specs share one in-memory H2 database
 * for the whole Playwright run, so a recipe saved by another spec would otherwise
 * make the next upload match it. Each spec starts from an empty recipe library.
 */
export async function clearImportRecipes(context: APIRequestContext): Promise<void> {
  const token = await apiLogin(context)
  const recipes = (await apiCall(context, token, 'get', '/import-recipes')) ?? []
  for (const recipe of recipes) {
    await apiCall(context, token, 'delete', `/import-recipes/${recipe.id}`)
  }
}

export interface SeedResult {
  semesterId: number
  classId: number
  teacherId: number
  substituteTeacherId: number
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
    periodCount: 10,
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
    startDate: SEED_MONDAY_ISO,
    endDate: SEED_END_ISO,
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    periodCount: 10,
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

/**
 * 学期锚点：最近的下个周一。批准路径受开课截止约束（规格第 6 节，服务端真实
 * 时钟判定），因此 e2e 种子学期必须落在未来，否则任何批准都会被 409 拒绝。
 */
function nextMonday(): Date {
  const today = new Date()
  const monday = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  monday.setDate(monday.getDate() + ((8 - monday.getDay()) % 7 || 7))
  return monday
}

const SEED_MONDAY = nextMonday()

export function isoDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 种子学期的开始日（ISO），跨周断言都以它为基准。 */
export const SEED_MONDAY_ISO = isoDate(SEED_MONDAY)
/** 种子学期的结束日：学期覆盖 20 周，跨周用例都在范围内。 */
export const SEED_END_ISO = isoDate(new Date(
  SEED_MONDAY.getFullYear(),
  SEED_MONDAY.getMonth(),
  SEED_MONDAY.getDate() + 140,
))

export function dateFor(dayOfWeek: number): string {
  const date = new Date(SEED_MONDAY)
  date.setDate(date.getDate() + (dayOfWeek - 1))
  return isoDate(date)
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
    periodCount: 10,
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
    periodCount: 2,
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
    force: true,
  })
  await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${mathLesson.id}`, {
    periodId: fridayPeriod.id,
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
// 管理员同班调课的公共流程（三步向导版）：选教师 → 选日期 → 定位来源课（步骤 1）。
// 表单 spec（admin-swap）从步骤 1 继续走向导；拖拽 spec（admin-swap-drag）在步骤 1
// 的周网格里拖课卡。两路共用定位逻辑，避免漂移。

/** 定位来源课所需的最小种子字段（seedSwapWorkbenchScenario / 拖拽本地种子都满足）。 */
export interface WorkbenchSourceSeed {
  teacherName: string
  lessonDate: string
}

/**
 * 登录管理员 → 搜索种子教师 → 跳到来源课所在周，等课卡出现即返回（仍在步骤 1）。
 * 选中教师/填日期之前零副作用，整段可安全重试（CI 负载下弹出层偶尔不渲染）。
 */
export async function openWorkbenchAtSourceLesson(
  page: Page,
  seed: WorkbenchSourceSeed,
): Promise<void> {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await expect(async () => {
    if ((await page.getByTestId('pick-lesson').count()) === 0) {
      await pickOption(page.locator('.picker-card'), 'teacher-select', seed.teacherName)
      const dateInput = page.getByTestId('lesson-date').locator('input')
      await dateInput.fill(seed.lessonDate)
      await dateInput.press('Tab')
    }
    await expect(page.getByTestId('pick-lesson').first()).toBeVisible({ timeout: 5_000 })
  }).toPass({ timeout: 60_000 })
}

/** 表单路径：从步骤 1 进入第②步并选择「调课」动作（窗口日期块可见即成功）。 */
export async function startSwapAction(page: Page): Promise<void> {
  await page.getByTestId('pick-lesson').first().click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-swap').click()
  await expect(page.getByTestId('window-dates')).toBeVisible()
}

/**
 * 表单路径：点目标日期 chip + 目标节次 radio（默认第 1 节，即种子数学课），
 * 填原因后进入第③步并等方案页签出现。
 */
export async function pickSwapTargetAndContinue(
  page: Page,
  seed: { targetDate: string },
  options: { periodNo?: number; reason?: string } = {},
): Promise<void> {
  const periodNo = options.periodNo ?? 1
  const reason = options.reason ?? '运动会调课'
  await page.getByTestId(`swap-date-chip-${seed.targetDate}`).click()
  const period = page.getByTestId(`swap-target-period-${periodNo}`)
  await expect(period).toBeVisible()
  await period.locator('input').check()
  await page.getByTestId('swap-reason').fill(reason)
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('wizard-panel-3')).toBeVisible()
  await expect(page.getByTestId('swap-plan-tab-0')).toBeVisible()
}

// ---------------------------------------------------------------- 票 01-06
// 工作台各 spec 复制的公共 UI 交互与日期工具，只在这里放一份
// （CODING_STANDARDS：E2E 公共交互只在 helpers.ts 出现一次）。

/** 按本地时区平移日期。 */
export function shiftDate(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00`)
  value.setDate(value.getDate() + days)
  return isoDate(value)
}

/** 与视图 monthDayCn 同款：11月2日（不补零）。 */
export function cnDate(date: string): string {
  const [, month, day] = date.split('-')
  return `${Number(month)}月${Number(day)}日`
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export function weekdayOf(date: string): string {
  return WEEKDAYS[new Date(`${date}T00:00:00`).getDay()]
}

/** 来源格（第 1 节）里的课卡按钮；lessonDate 是该格的真实日期。 */
export function sourceCard(page: Page, lessonDate: string): Locator {
  return page.getByTestId(`workbench-cell-${lessonDate}-1`).getByTestId('pick-lesson')
}

/** 真实鼠标管线：按住来源卡 → 移到目标格中心 → 松手（拖拽 spec 同节拍）。 */
export async function dragCardToCell(page: Page, from: Locator, to: Locator): Promise<void> {
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

// ------------------------------------------------- 票 05/06/08 草稿详情页公共交互
/** 草稿详情接口的宽类型：三个草稿 spec 各自只读自己关心的字段。 */
export interface DraftDetailView {
  draft: { id?: number; name?: string; version: number }
  lessons: Array<{
    id: number
    classId?: number
    assignmentId?: number
    periodId: number | null
    courseName?: string
    locked?: boolean
  }>
}

/** 草稿网格里的一格（按节次 id 定位，不依赖行列顺序）。 */
export function gridCell(page: Page, periodId: number): Locator {
  return page.locator(`.grid-card .cell[data-period-id="${periodId}"]`)
}

/** 网格里的一张课卡（按课节 id 定位；同门课一周多节也互不串）。 */
export function lessonCard(page: Page, lessonId: number): Locator {
  return page.locator(`.grid-card .lesson[data-lesson-id="${lessonId}"]`)
}

/** 管理员登录 → 打开草稿详情 → 等网格首格渲染。 */
export async function openDraft(page: Page, draftId: number): Promise<void> {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/workbench/drafts/${draftId}`)
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
}

/** 在「按行政班」视角里选中班级，等触发器回填班级名。 */
export async function selectClass(page: Page, className: string): Promise<void> {
  const select = page.locator('.controls .el-select')
  await select.click()
  await page
    .locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: className })
    .first()
    .click()
  await expect(select).toContainText(className)
}

/** 切到「按教师」视角并选中教师（先点视角标签，再选下拉）。 */
export async function selectTeacher(page: Page, teacherName: string): Promise<void> {
  await page.getByText('按教师', { exact: true }).click()
  const select = page.locator('.controls .el-select')
  await select.click()
  await page
    .locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: teacherName })
    .first()
    .click()
  await expect(select).toContainText(teacherName)
}

/** 等草稿详情重新拉取（写操作成功后前端会刷新权威状态）。 */
export function waitForDraftReload(page: Page, draftId: number) {
  return page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().endsWith(`/api/timetable-drafts/${draftId}`),
  )
}

/** 等一次草稿变更提交（POST /changes），断言状态码用它的返回值。 */
export function waitForChanges(page: Page, draftId: number) {
  return page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith(`/api/timetable-drafts/${draftId}/changes`),
  )
}

/** 等一次锁批量写入（PUT /lessons/locks）。 */
export function waitForLocks(page: Page, draftId: number) {
  return page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      response.url().endsWith(`/api/timetable-drafts/${draftId}/lessons/locks`),
  )
}

/** 走 API 读草稿详情；非 2xx 直接抛错（apiCall 已带状态与响应体）。 */
export async function draftDetail(
  api: APIRequestContext,
  draftId: number,
): Promise<DraftDetailView> {
  const token = await apiLogin(api)
  return apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
}

/**
 * 通过 API 批量上/解锁：先读当前 version 再带 expectedVersion 写入，断言 200。
 * 集成/优化类用例用它把前置状态摆好，不依赖 UI 点击。
 */
export async function lockLessons(
  api: APIRequestContext,
  seed: { draftId: number },
  lessonIds: number[],
  locked: boolean,
): Promise<void> {
  const token = await apiLogin(api)
  const current = await draftDetail(api, seed.draftId)
  const response = await api.put(`${API}/timetable-drafts/${seed.draftId}/lessons/locks`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { lessonIds, locked, expectedVersion: current.draft.version },
  })
  expect(response.status()).toBe(200)
}

/** 点选一张课卡，等右侧控制区回填课程名（选中态已成）。 */
export async function openLesson(page: Page, lessonId: number): Promise<void> {
  await lessonCard(page, lessonId).click()
}

/** 课节在草稿里的节次 id；未排课返回 null。 */
export function positionOf(detail: DraftDetailView, lessonId: number): number | null {
  return detail.lessons.find((lesson) => lesson.id === lessonId)?.periodId ?? null
}

// ---------------------------------------------------------------- 票 05 草稿拖拽
/**
 * Three same-class lessons on day 1 (periods 1-3) plus two other-class lessons.
 * Class 2's Chinese sits at day 1 period 1, so swapping class 1's Chinese into
 * period 1 adds a teacher conflict; a 3-cycle with the English lesson can still
 * resolve it. `ambiguous` puts two class 1 lessons in the same cell.
 */
export interface DraftDragScenario {
  semesterId: number
  draftId: number
  classId: number
  className: string
  otherClassId: number
  teacherAName: string
  teacherBName: string
  lessonAId: number
  lessonBId: number
  lessonCId: number
  class2ChineseId: number
  class2EnglishId: number
  courseAName: string
  courseBName: string
  courseCName: string
  periodA: number
  periodB: number
  periodC: number
  emptyPeriod: number
  crossPeriod: number
  ambiguousPeriod: number
}

export async function seedDraftDragScenario(
  context: APIRequestContext,
  options: { ambiguous?: boolean; overlapTarget?: boolean; blockedOverlapTarget?: boolean } = {},
): Promise<DraftDragScenario> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E拖拽-${suffix}`,
    startDate: SEED_MONDAY_ISO,
    endDate: SEED_END_ISO,
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    periodCount: 4,
    days: [1, 2],
  })
  const periodRows = await apiCall(context, token, 'get', `/semesters/${semester.id}/periods`)
  const periodIds: Record<string, number> = {}
  for (const row of periodRows) {
    periodIds[`${row.dayOfWeek}-${row.periodNo}`] = row.id
  }

  const grade = await apiCall(context, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `高一${suffix}`,
    sortOrder: 1,
  })
  const className = `高一1班${suffix}`
  const class1 = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: className,
    studentCount: 45,
  })
  const class2 = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: `高一2班${suffix}`,
    studentCount: 44,
  })

  const teacherAName = `数学老师${suffix}`
  const teacherA = await apiCall(context, token, 'post', '/teachers', {
    name: teacherAName,
    employeeNo: `D1-${suffix}`,
    subject: '数学',
  })
  const teacherBName = `语文老师${suffix}`
  const teacherB = await apiCall(context, token, 'post', '/teachers', {
    name: teacherBName,
    employeeNo: `D2-${suffix}`,
    subject: '语文',
  })
  const teacherC = await apiCall(context, token, 'post', '/teachers', {
    name: `英语老师${suffix}`,
    employeeNo: `D3-${suffix}`,
    subject: '英语',
  })

  const courseAName = `数学A${suffix}`
  const courseBName = `语文A${suffix}`
  const courseCName = `英语A${suffix}`
  const math = await apiCall(context, token, 'post', '/courses', {
    name: courseAName,
    subject: '数学',
    allowConsecutive: false,
  })
  const chinese = await apiCall(context, token, 'post', '/courses', {
    name: courseBName,
    subject: '语文',
    allowConsecutive: false,
  })
  const english = await apiCall(context, token, 'post', '/courses', {
    name: courseCName,
    subject: '英语',
    allowConsecutive: false,
  })

  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: math.id,
    teacherId: teacherA.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: chinese.id,
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
    courseId: chinese.id,
    teacherId: teacherB.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class2.id,
    courseId: english.id,
    teacherId: teacherC.id,
    weeklyLessons: 1,
  })

  const draftId = await solveDraft(context, semester.id)
  const solved = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const byKey = new Map<string, any>()
  for (const lesson of solved.lessons) {
    byKey.set(`${lesson.classId}-${lesson.courseId}`, lesson)
  }
  const class1Math = byKey.get(`${class1.id}-${math.id}`)
  const class1Chinese = byKey.get(`${class1.id}-${chinese.id}`)
  const class1English = byKey.get(`${class1.id}-${english.id}`)
  const class2Chinese = byKey.get(`${class2.id}-${chinese.id}`)
  const class2English = byKey.get(`${class2.id}-${english.id}`)
  if (!class1Math || !class1Chinese || !class1English || !class2Chinese || !class2English) {
    throw new Error('solver did not create every seeded draft lesson')
  }

  const place = async (lessonId: number, key: string) => {
    await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lessonId}`, {
      periodId: periodIds[key],
      force: true,
    })
  }
  await place(class1Math.id, '1-1')
  if (options.ambiguous) {
    await place(class1Chinese.id, '2-2')
    await place(class1English.id, '2-2')
  } else {
    await place(class1Chinese.id, '1-2')
    await place(class1English.id, '1-3')
  }
  if (options.blockedOverlapTarget) {
    // Direct swap is illegal (teacher B already has class 2 at the source slot),
    // while the target slot also carries a foreign lesson. Auto-search must still
    // find the 3-cycle; foreign lessons there are context, not a cross-class target.
    await place(class2Chinese.id, '1-1')
    await place(class2English.id, '1-2')
  } else if (options.overlapTarget) {
    // Keep a foreign lesson on the target slot (real timetables do) but move the
    // other class's Chinese lesson away from the source slot so A/B swap is legal.
    await place(class2Chinese.id, '2-2')
    await place(class2English.id, '1-2')
  } else {
    await place(class2Chinese.id, '1-1')
    await place(class2English.id, '2-1')
  }

  return {
    semesterId: semester.id,
    draftId,
    classId: class1.id,
    className,
    otherClassId: class2.id,
    teacherAName,
    teacherBName,
    lessonAId: class1Math.id,
    lessonBId: class1Chinese.id,
    lessonCId: class1English.id,
    class2ChineseId: class2Chinese.id,
    class2EnglishId: class2English.id,
    courseAName,
    courseBName,
    courseCName,
    periodA: periodIds['1-1'],
    periodB: periodIds['1-2'],
    periodC: periodIds['1-3'],
    emptyPeriod: periodIds['1-4'],
    crossPeriod: periodIds['2-1'],
    ambiguousPeriod: periodIds['2-2'],
  }
}

// ------------------------------------------------------------ 票 06 草稿锁定
/**
 * One class with three same-course math lessons on Monday plus one Chinese and
 * one English lesson, and a second class with two lessons. The three math
 * lessons share course/teacher/class and differ only by `lessonIndex`, which is
 * exactly the "same course twice a week" case locking must not confuse.
 */
export interface DraftLockScenario {
  semesterId: number
  draftId: number
  classId: number
  className: string
  otherClassId: number
  otherClassName: string
  mathTeacherName: string
  mathCourseName: string
  chineseCourseName: string
  mathLessonIds: number[]
  chineseLessonId: number
  englishLessonId: number
  otherChineseLessonId: number
  otherEnglishLessonId: number
  mondayPeriodIds: number[]
  emptyPeriod: number
}

export async function seedDraftLockScenario(context: APIRequestContext): Promise<DraftLockScenario> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E锁定-${suffix}`,
    startDate: SEED_MONDAY_ISO,
    endDate: SEED_END_ISO,
  })
  await apiCall(context, token, 'post', `/semesters/${semester.id}/set-current`)
  await apiCall(context, token, 'post', `/semesters/${semester.id}/periods/default`, {
    periodCount: 6,
    days: [1, 2],
  })
  const periodRows = await apiCall(context, token, 'get', `/semesters/${semester.id}/periods`)
  const periodIds: Record<string, number> = {}
  for (const row of periodRows) {
    periodIds[`${row.dayOfWeek}-${row.periodNo}`] = row.id
  }

  const grade = await apiCall(context, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `锁定高一${suffix}`,
    sortOrder: 1,
  })
  const className = `锁定1班${suffix}`
  const class1 = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: className,
    studentCount: 45,
  })
  const otherClassName = `锁定2班${suffix}`
  const class2 = await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: otherClassName,
    studentCount: 44,
  })

  const mathTeacherName = `锁定数学${suffix}`
  const mathTeacher = await apiCall(context, token, 'post', '/teachers', {
    name: mathTeacherName,
    employeeNo: `L1-${suffix}`,
    subject: '数学',
  })
  const chineseTeacher = await apiCall(context, token, 'post', '/teachers', {
    name: `锁定语文${suffix}`,
    employeeNo: `L2-${suffix}`,
    subject: '语文',
  })
  const englishTeacher = await apiCall(context, token, 'post', '/teachers', {
    name: `锁定英语${suffix}`,
    employeeNo: `L3-${suffix}`,
    subject: '英语',
  })

  const mathCourseName = `锁定数学课${suffix}`
  const chineseCourseName = `锁定语文课${suffix}`
  const math = await apiCall(context, token, 'post', '/courses', {
    name: mathCourseName,
    subject: '数学',
    allowConsecutive: false,
  })
  const chinese = await apiCall(context, token, 'post', '/courses', {
    name: chineseCourseName,
    subject: '语文',
    allowConsecutive: false,
  })
  const english = await apiCall(context, token, 'post', '/courses', {
    name: `锁定英语课${suffix}`,
    subject: '英语',
    allowConsecutive: false,
  })

  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: math.id,
    teacherId: mathTeacher.id,
    weeklyLessons: 3,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: chinese.id,
    teacherId: chineseTeacher.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class1.id,
    courseId: english.id,
    teacherId: englishTeacher.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class2.id,
    courseId: chinese.id,
    teacherId: chineseTeacher.id,
    weeklyLessons: 1,
  })
  await apiCall(context, token, 'post', '/assignments', {
    classId: class2.id,
    courseId: english.id,
    teacherId: englishTeacher.id,
    weeklyLessons: 1,
  })

  const draftId = await solveDraft(context, semester.id)
  const solved = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
  const mathLessons = solved.lessons
    .filter((lesson: any) => lesson.classId === class1.id && lesson.courseId === math.id)
    .sort((a: any, b: any) => a.id - b.id)
  const chineseLesson = solved.lessons.find(
    (lesson: any) => lesson.classId === class1.id && lesson.courseId === chinese.id,
  )
  const englishLesson = solved.lessons.find(
    (lesson: any) => lesson.classId === class1.id && lesson.courseId === english.id,
  )
  const otherChineseLesson = solved.lessons.find(
    (lesson: any) => lesson.classId === class2.id && lesson.courseId === chinese.id,
  )
  const otherEnglishLesson = solved.lessons.find(
    (lesson: any) => lesson.classId === class2.id && lesson.courseId === english.id,
  )
  if (
    mathLessons.length !== 3 ||
    !chineseLesson ||
    !englishLesson ||
    !otherChineseLesson ||
    !otherEnglishLesson
  ) {
    throw new Error('solver did not create every seeded lock lesson')
  }

  const place = async (lessonId: number, key: string) => {
    await apiCall(context, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lessonId}`, {
      periodId: periodIds[key],
      force: true,
    })
  }
  const mondayPeriodIds = [
    periodIds['1-1'],
    periodIds['1-3'],
    periodIds['1-5'],
  ]
  await place(mathLessons[0].id, '1-1')
  await place(mathLessons[1].id, '1-3')
  await place(mathLessons[2].id, '1-5')
  await place(chineseLesson.id, '1-2')
  await place(englishLesson.id, '2-1')
  await place(otherChineseLesson.id, '2-2')
  await place(otherEnglishLesson.id, '2-3')

  return {
    semesterId: semester.id,
    draftId,
    classId: class1.id,
    className,
    otherClassId: class2.id,
    otherClassName,
    mathTeacherName,
    mathCourseName,
    chineseCourseName,
    mathLessonIds: mathLessons.map((lesson: any) => lesson.id),
    chineseLessonId: chineseLesson.id,
    englishLessonId: englishLesson.id,
    otherChineseLessonId: otherChineseLesson.id,
    otherEnglishLessonId: otherEnglishLesson.id,
    mondayPeriodIds,
    emptyPeriod: periodIds['2-6'],
  }
}
