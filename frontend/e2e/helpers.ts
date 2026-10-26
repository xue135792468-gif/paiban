import { request, type APIRequestContext, type Page } from '@playwright/test'

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
}

/**
 * Semester + timetable + two teachers for the constraint page. The constraint
 * specs assert entry cards by teacher name, so the seeded names are returned
 * (synthetic names only — never a real teacher).
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
  return { semesterId: semester.id, teachers }
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
