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

/** Solves the seeded semester and publishes the resulting draft. */
export async function solveAndPublish(context: APIRequestContext, seed: SeedResult): Promise<number> {
  const token = await apiLogin(context)
  const job = await apiCall(
    context,
    token,
    'post',
    `/scheduling-jobs?semesterId=${seed.semesterId}`,
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
  await apiCall(context, token, 'post', `/timetable-drafts/${draftId}/publish`)
  return draftId
}

export async function classEntries(
  context: APIRequestContext,
  classId: number,
): Promise<Array<{ id: number; dayOfWeek: number; periodNo: number; courseName: string }>> {
  const token = await apiLogin(context)
  return apiCall(context, token, 'get', `/timetable/class/${classId}`)
}
