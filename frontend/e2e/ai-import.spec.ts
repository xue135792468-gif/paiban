import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test'
import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { apiCall, apiLogin, uiLogin } from './helpers'
import { buildXlsx } from './xlsx'

/**
 * End-to-end coverage for the generic AI import.
 *
 * The AI provider is a local stub: it speaks the OpenAI-compatible
 * `POST /v1/chat/completions` shape and answers with a fixed `{mappings, rows,
 * explanation}` payload. No test ever reaches a real model, so results are
 * deterministic and the suite stays offline.
 */

interface ImportSeed {
  suffix: string
  semesterId: number
  classOne: string
  classTwo: string
  teacherOne: string
  teacherTwo: string
  courseOne: string
  courseTwo: string
  room: string
  importedTeacherOne: string
  importedTeacherTwo: string
}

let api: APIRequestContext
let seed: ImportSeed
let stub: http.Server
let stubBaseUrl = ''
let providerId: number | null = null
let timetablePayload: Record<string, unknown> = {}
let teacherPayload: Record<string, unknown> = {}
/**
 * Behaviour of the stub for batched (`第 N/M 批`) prompts: artificial latency leaves a
 * window to observe PARSING, and a listed chunk answers 400 to produce a partial result.
 */
let batchControl: { latencyMs: number; failChunks: number[] } = { latencyMs: 0, failChunks: [] }
/** One payload per batch, indexed by batch number minus one. */
let batchPayloads: Record<string, unknown>[] = []

test.beforeAll(async () => {
  api = await request.newContext()
  seed = await seedImportSchool(api)
  timetablePayload = timetablePreview(seed)
  teacherPayload = teacherPreview(seed)
  stub = await startStub(() => timetablePayload, () => teacherPayload)
  const port = (stub.address() as AddressInfo).port
  stubBaseUrl = `http://127.0.0.1:${port}`
  await configureImportProvider(api, stubBaseUrl)
})

test.afterAll(async () => {
  // onboarding.spec.ts (which runs later, alphabetically) only shows the wizard when
  // the *current* semester has no periods and no classes. Leave a blank current
  // semester behind so this file's fixtures do not hide the first-login state.
  if (seed) {
    try {
      const token = await apiLogin(api)
      const blank = await apiCall(api, token, 'post', '/semesters', {
        name: `E2E 清理学期-${seed.suffix}`,
        startDate: '2026-02-01',
        endDate: '2026-06-30',
      })
      await apiCall(api, token, 'post', `/semesters/${blank.id}/set-current`)
    } catch {
      // Cleanup must not turn a passing suite into a failing one.
    }
  }
  if (providerId !== null) {
    try {
      await apiCall(api, await apiLogin(api), 'delete', `/ai-providers/${providerId}`)
    } catch {
      // The provider is test-only; a failed cleanup must not hide test results.
    }
  }
  await new Promise<void>((resolve) => stub.close(() => resolve()))
  await api.dispose()
})

test('目标卡片写清能处理、输出与限制，未支持目标为禁用态', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')

  await expect(page.getByTestId('target-card')).toHaveCount(6)

  const teacher = page.locator('[data-testid=target-card][data-target=teacher]')
  await expect(teacher).toContainText('能处理')
  await expect(teacher).toContainText('输出')
  await expect(teacher).toContainText('限制')
  await expect(teacher).toContainText('教师名单、通讯录、任课表')
  await expect(teacher).toContainText('教师档案')

  const timetable = page.locator('[data-testid=target-card][data-target=timetable]')
  await expect(timetable).toContainText('能处理')
  await expect(timetable).toContainText('输出')
  await expect(timetable).toContainText('限制')
  await expect(timetable).toContainText('年级课表、班级课表')
  await expect(timetable).toContainText('课表草稿')
  await expect(timetable).toContainText('只生成草稿')

  for (const code of ['admin-class', 'course', 'room', 'assignment']) {
    const card = page.locator(`[data-testid=target-card][data-target=${code}]`)
    await expect(card).toHaveAttribute('aria-disabled', 'true')
    await expect(card).toContainText('即将支持')
  }
})

test('排课页的「导入课表」入口预选课表目标', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/scheduling')
  await page.getByRole('button', { name: '导入课表' }).click()

  await expect(page).toHaveURL(/\/admin\/ai-import\?target=timetable/)
  await expect(page.locator('[data-target=timetable]')).toContainText('已选择')
})

test('课表导入：网格预览、班级切换、未匹配映射、生成草稿', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  await expect(page.getByText('AI 已就绪')).toBeVisible()

  await page.locator('[data-target=timetable]').click()
  await expect(page.locator('[data-target=timetable]')).toContainText('已选择')

  await uploadExcel(page, 'timetable.xlsx', buildXlsx(timetableSheet(seed), {
    sheetName: '高一年级课表',
    merges: ['A1:F1'],
  }))
  await page.getByRole('button', { name: '开始解析' }).click()

  // Preview: read-only grid for the first class, one row per lesson.
  const summary = page.getByTestId('timetable-summary')
  await expect(summary).toBeVisible({ timeout: 60_000 })
  await expect(summary).toContainText('共 4 节，1 节待映射')

  const lessons = page.locator('.timetable-preview .grid .lesson')
  await expect(lessons).toHaveCount(3)
  await expect(lessons.first()).toContainText(seed.courseOne)
  await expect(lessons.first()).toContainText(seed.teacherOne)

  // Class switch shows the other class' timetable.
  await page.getByTestId('timetable-view-select').click()
  await pickDropdownOption(page, seed.classTwo)
  await expect(lessons).toHaveCount(1)
  await expect(lessons.first()).toContainText(seed.courseTwo)
  await expect(lessons.first()).toContainText(seed.teacherTwo)

  await page.getByTestId('timetable-view-select').click()
  await pickDropdownOption(page, seed.classOne)
  await expect(lessons).toHaveCount(3)

  // The unmatched course blocks the commit until it is mapped to a system course.
  const mapping = page.getByTestId('mapping-step')
  await expect(mapping).toBeVisible()
  await expect(mapping).toContainText(`物理-${seedSuffix(seed)}`)
  const commit = page.locator('.footer-actions').getByRole('button', { name: /确认入库/ })
  await expect(commit).toBeDisabled()

  await page.getByTestId('resolve-courseName').click()
  await pickDropdownOption(page, seed.courseOne)
  await expect(page.getByTestId('mapping-clear')).toBeVisible()
  await expect(summary).toContainText('共 4 节，0 节待映射')
  // The selected class keeps three lessons; the unmatched one now shows the mapped course.
  await expect(lessons).toHaveCount(3)
  await expect(page.locator('.timetable-preview .lesson').filter({ hasText: seed.courseOne })).toHaveCount(2)
  await expect(page.locator('.timetable-preview .grid')).not.toContainText(`物理-${seed.suffix}`)
  await expect(commit).toBeEnabled()

  await commit.click()
  await page.waitForURL(/\/admin\/drafts\/\d+/)
  await expect(page.locator('.lesson').first()).toBeVisible()
  await expect(page.locator('.lesson').first()).toContainText(seed.courseOne)

  const draftId = Number(page.url().match(/\/admin\/drafts\/(\d+)/)?.[1])
  const token = await apiLogin(api)
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(detail.lessons).toHaveLength(4)
  expect(detail.lessons.map((lesson: { courseName: string }) => lesson.courseName)).toContain(
    seed.courseOne,
  )
})

test('教师导入回归：上传、预览行、入库', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  await expect(page.getByText('AI 已就绪')).toBeVisible()
  await expect(page.locator('[data-target=teacher]')).toContainText('已选择')

  await uploadExcel(page, 'teachers.xlsx', buildXlsx(teacherSheet(seed), { sheetName: '教师名单' }))
  await page.getByRole('button', { name: '开始解析' }).click()

  const table = page.getByTestId('preview-rows-table')
  await expect(table).toBeVisible({ timeout: 60_000 })
  await expect(table).toContainText(seed.importedTeacherOne)
  await expect(table).toContainText(seed.importedTeacherTwo)

  await page.getByRole('button', { name: '全部接受并入库' }).click()
  await expect(page.getByText('已成功入库 2 条教师数据')).toBeVisible({ timeout: 30_000 })

  const token = await apiLogin(api)
  const teachers = await apiCall(api, token, 'get', '/teachers')
  const names = (teachers as Array<{ name: string }>).map((teacher) => teacher.name)
  expect(names).toContain(seed.importedTeacherOne)
  expect(names).toContain(seed.importedTeacherTwo)
})

test('解析失败给出可操作提示，而不是通用「操作失败」', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  await expect(page.getByText('AI 已就绪')).toBeVisible()

  await uploadExcel(page, 'broken.xlsx', Buffer.from('this is not a spreadsheet', 'utf8'))
  await page.getByRole('button', { name: '开始解析' }).click()

  const error = page.locator('.el-message--error')
  await expect(error).toBeVisible({ timeout: 30_000 })
  await expect(error).toContainText('无法读取表格文件')
  await expect(error).not.toContainText('操作失败，请稍后重试')
})

test('课表解析后切页再回来，预览仍在', async ({ page }) => {
  await startTimetableImport(page, timetableSheet(seed), { merges: ['A1:F1'] })
  const summary = page.getByTestId('timetable-summary')
  await expect(summary).toContainText('共 4 节，1 节待映射')
  await expect(page).toHaveURL(/session=\d+/)

  // In-app navigation (SPA pushState) followed by browser back (popstate): no reload,
  // so the preview has to come back from the session in the URL.
  await page.getByRole('menuitem', { name: '自动排课' }).click()
  await expect(page).toHaveURL(/\/admin\/scheduling/)
  await page.goBack()

  await expect(summary).toBeVisible()
  await expect(summary).toContainText('共 4 节，1 节待映射')
  await expect(page.locator('.timetable-preview .grid .lesson')).toHaveCount(3)
  // The restored page keeps the session in the URL, so another refresh still works.
  await expect(page).toHaveURL(/session=\d+/)
})

test('F5 刷新后预览仍在', async ({ page }) => {
  await startTimetableImport(page, timetableSheet(seed), { merges: ['A1:F1'] })
  await expect(page.getByTestId('timetable-summary')).toBeVisible()
  await expect(page).toHaveURL(/session=\d+/)

  await page.reload()

  const summary = page.getByTestId('timetable-summary')
  await expect(summary).toContainText('共 4 节，1 节待映射')
  await expect(page.locator('.timetable-preview .grid .lesson')).toHaveCount(3)
  await expect(page).toHaveURL(/session=\d+/)
})

test('空页面显示上次导入入口并可继续', async ({ page }) => {
  await startTimetableImport(page, timetableSheet(seed), { merges: ['A1:F1'] })

  // A full page load without the session query: only localStorage knows the session.
  await page.goto('/admin/ai-import')

  const card = page.getByTestId('last-session-card')
  await expect(card).toBeVisible()
  await expect(card).toContainText('timetable.xlsx')
  await expect(card).toContainText('待确认')

  await page.getByTestId('continue-last-session').click()

  await expect(page.getByTestId('timetable-summary')).toContainText('共 4 节')
  await expect(page).toHaveURL(/session=\d+/)
})

test('八（1）推断为八年级并随班级创建', async ({ page }) => {
  const newClass = `八（1）-${seed.suffix}`
  const sheet = [
    ['班级', '星期', '节次', '课程', '教师', '教室'],
    [seed.classOne, '周一', '第1节', seed.courseOne, seed.teacherOne, seed.room],
    [newClass, '周一', '第2节', seed.courseTwo, seed.teacherTwo, seed.room],
  ]

  await withTimetablePayload(inferredGradePreview(seed, newClass), async () => {
    await startTimetableImport(page, sheet)

    const mapping = page.getByTestId('mapping-step')
    await expect(mapping).toContainText(newClass)
    const gradeHint = mapping.getByTestId('grade-hint')
    await expect(gradeHint).toContainText('八年级')
    await expect(gradeHint).toContainText('新建')

    // Select the unmatched class only: its grade and class are created on confirm.
    await mapping
      .locator('.el-table__body tr')
      .first()
      .locator('td.el-table-column--selection .el-checkbox')
      .click()
    await expect(mapping.getByTestId('batch-student-count').locator('input')).toHaveValue('45')

    await page.getByRole('button', { name: /批量创建所选/ }).click()
    const confirm = page.locator('.el-message-box')
    await expect(confirm).toContainText('批量创建')
    await confirm.getByRole('button', { name: '确定' }).click()

    await expect(page.getByTestId('mapping-clear')).toBeVisible()
    await page.locator('.footer-actions').getByRole('button', { name: /确认入库/ }).click()
    await page.waitForURL(/\/admin\/drafts\/\d+/)
  })

  const token = await apiLogin(api)
  const grades = await apiCall(api, token, 'get', `/grades?semesterId=${seed.semesterId}`)
  expect((grades as Array<{ name: string }>).map((grade) => grade.name)).toContain('八年级')

  const classes = await apiCall(api, token, 'get', `/classes?semesterId=${seed.semesterId}`)
  const created = (classes as Array<{ name: string; gradeName: string; studentCount: number }>).find(
    (adminClass) => adminClass.name === newClass,
  )
  expect(created?.gradeName).toBe('八年级')
  expect(created?.studentCount).toBe(45)
})

// ------------------------------------------- 八期：后台解析 / 停止 / 部分结果 / 放弃

test('大课表后台解析：显示批次进度，全部完成自动进入预览', async ({ page }) => {
  await withBatchControl({ latencyMs: 400 }, async () => {
    batchPayloads = batchPayloadsFor(batchedSheet())
    await startBatchedImport(page)

    // The upload answers at once with a PARSING session; the page shows which batch
    // of how many has landed instead of a spinner on the parse button.
    const progress = page.getByTestId('import-progress')
    await expect(progress).toBeVisible()
    await expect(progress).toContainText('解析中')
    await expect(page.getByTestId('progress-count')).toHaveText(/已完成 \d+\/4 批/)
    await expect(page.getByTestId('stop-parsing')).toBeVisible()

    // No refresh, no extra click: the last batch turns the session into a plain preview.
    const summary = page.getByTestId('timetable-summary')
    await expect(summary).toBeVisible({ timeout: 30_000 })
    await expect(summary).toContainText('共 4 节')
    await expect(page.getByTestId('import-progress')).toHaveCount(0)
    // The grid shows one class at a time; each class holds two of the four lessons.
    await expect(page.locator('.timetable-preview .grid .lesson')).toHaveCount(2)
    await expect(page).toHaveURL(/session=\d+/)
  })
})

test('解析中停止：已完成批次保留，可以直接继续入库', async ({ page }) => {
  await withBatchControl({ latencyMs: 1200 }, async () => {
    batchPayloads = batchPayloadsFor(batchedSheet())
    await startBatchedImport(page)

    // Stop after the first batch has landed, so there is something to keep.
    await expect(page.getByTestId('progress-count')).toHaveText(/已完成 [1-3]\/4 批/, {
      timeout: 30_000,
    })
    await page.getByTestId('stop-parsing').click()
    await expect(page.locator('.el-message--success')).toContainText('已停止解析，保留已完成')

    // What parsed is a normal preview: it can be committed without re-parsing.
    const summary = page.getByTestId('timetable-summary')
    await expect(summary).toBeVisible({ timeout: 20_000 })
    const kept = Number((await summary.innerText()).match(/共\s*(\d+)\s*节/)?.[1])
    expect(kept).toBeGreaterThan(0)
    expect(kept).toBeLessThan(4)

    const commit = page.locator('.footer-actions').getByRole('button', { name: /确认入库/ })
    await expect(commit).toBeEnabled()
    await commit.click()
    await page.waitForURL(/\/admin\/drafts\/\d+/)

    const draftId = Number(page.url().match(/\/admin\/drafts\/(\d+)/)?.[1])
    const detail = await apiCall(api, await apiLogin(api), 'get', `/timetable-drafts/${draftId}`)
    expect(detail.lessons).toHaveLength(kept)
  })
})

test('单批失败进入部分结果，重试后得到完整预览并入库', async ({ page }) => {
  await withBatchControl({ failChunks: [2] }, async () => {
    batchPayloads = batchPayloadsFor(batchedSheet())
    await startBatchedImport(page)

    // One batch failed: the classes that did parse stay on screen and the commit is held.
    const panel = page.getByTestId('partial-result-panel')
    await expect(panel).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('partial-summary')).toContainText('已解析 3/4 批')
    await expect(page.getByTestId('partial-success')).toContainText('已解析 3 节课')

    const failed = page.getByTestId('partial-failed')
    // Batch 2 is the second data row, and its class is what the panel names.
    await expect(failed).toContainText(batchedSheet()[3][0])
    await expect(failed).toContainText('解析失败')
    await expect(failed).toContainText('第 2/4 批')

    const commit = page.locator('.footer-actions').getByRole('button', { name: /先重试或放弃/ })
    await expect(commit).toBeDisabled()

    // The provider recovers; retrying re-runs only the failed class.
    batchControl.failChunks = []
    await page.getByTestId('partial-retry').click()
    await expect(page.locator('.el-message--success')).toContainText('重试成功', {
      timeout: 60_000,
    })

    const summary = page.getByTestId('timetable-summary')
    await expect(summary).toBeVisible()
    await expect(summary).toContainText('共 4 节')
    await expect(page.getByTestId('partial-result-panel')).toHaveCount(0)

    await page.locator('.footer-actions').getByRole('button', { name: /确认入库/ }).click()
    await page.waitForURL(/\/admin\/drafts\/\d+/)

    const draftId = Number(page.url().match(/\/admin\/drafts\/(\d+)/)?.[1])
    const detail = await apiCall(api, await apiLogin(api), 'get', `/timetable-drafts/${draftId}`)
    expect(detail.lessons).toHaveLength(4)
  })
})

test('放弃导入：不生成草稿，?session= 恢复入口不再继续该会话', async ({ page }) => {
  await withBatchControl({ latencyMs: 1500 }, async () => {
    batchPayloads = batchPayloadsFor(batchedSheet())
    const token = await apiLogin(api)
    const draftsBefore = (await apiCall(api, token, 'get', '/timetable-drafts')).length

    await startBatchedImport(page)
    const sessionId = Number(page.url().match(/session=(\d+)/)?.[1])
    const started = await apiCall(api, token, 'get', `/ai/import/sessions/${sessionId}`)
    expect(started.status).toBe('PARSING')

    await page.getByTestId('abandon-import').click()
    const confirm = page.locator('.el-message-box')
    await expect(confirm).toContainText('放弃后本次导入会直接作废')
    await confirm.getByRole('button', { name: '放弃导入' }).click()

    await expect(page.locator('.el-message--info')).toContainText('已放弃本次导入，未写入任何数据')

    // Back to an empty import page: no preview, no session in the URL, nothing to continue.
    await expect(page.getByTestId('target-card')).toHaveCount(6)
    await expect(page.getByTestId('timetable-summary')).toHaveCount(0)
    await expect(page).not.toHaveURL(/session=/)
    await expect(page.getByTestId('last-session-card')).toHaveCount(0)
    expect(await page.evaluate(() => localStorage.getItem('paiban.aiImport.lastSession'))).toBeNull()

    // Server side: the session is closed and the business tables were never touched.
    const abandoned = await apiCall(api, token, 'get', `/ai/import/sessions/${sessionId}`)
    expect(abandoned.status).toBe('ABANDONED')
    const draftsAfter = await apiCall(api, token, 'get', '/timetable-drafts')
    expect(draftsAfter.length).toBe(draftsBefore)

    // A bookmarked `?session=` must not resurrect it either.
    await page.goto(`/admin/ai-import?session=${sessionId}`)
    await expect(page.locator('.el-message--error')).toContainText('该导入会话已不可用')
    await expect(page.getByTestId('target-card')).toHaveCount(6)
    await expect(page).not.toHaveURL(/session=/)
  })
})

// --------------------------------------------------------------------- fixtures

/** Logs in, opens the import page and selects the timetable target. */
async function openTimetableImport(page: Page): Promise<void> {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  await expect(page.getByText('AI 已就绪')).toBeVisible()
  await page.locator('[data-target=timetable]').click()
  await expect(page.locator('[data-target=timetable]')).toContainText('已选择')
}

/** Runs the timetable happy path up to the parsed preview. */
async function startTimetableImport(
  page: Page,
  rows: string[][],
  options: { merges?: string[] } = {},
): Promise<void> {
  await openTimetableImport(page)
  await uploadExcel(
    page,
    'timetable.xlsx',
    buildXlsx(rows, { sheetName: '高一年级课表', merges: options.merges }),
  )
  await page.getByRole('button', { name: '开始解析' }).click()
  await expect(page.getByTestId('timetable-summary')).toBeVisible({ timeout: 60_000 })
}

/**
 * The backend parses a timetable row by row once it has more than three data rows
 * (two rows of title/header are skipped first), so this sheet is four batches. Every
 * row maps onto seeded classes, courses and teachers, which keeps the commit gate out
 * of the way of the progress assertions.
 */
function batchedSheet(): string[][] {
  return [
    ['高一年级课程表', '', '', '', '', ''],
    ['班级', '星期', '节次', '课程', '教师', '教室'],
    [seed.classOne, '周一', '第1节', seed.courseOne, seed.teacherOne, seed.room],
    [seed.classTwo, '周一', '第1节', seed.courseTwo, seed.teacherTwo, seed.room],
    [seed.classOne, '周一', '第2节', seed.courseTwo, seed.teacherTwo, seed.room],
    [seed.classTwo, '周一', '第2节', seed.courseOne, seed.teacherOne, seed.room],
  ]
}

/** One lesson per batch, the way a real provider answers a `第 N/M 批` prompt. */
function batchPayloadsFor(sheet: string[][]): Record<string, unknown>[] {
  const header = sheet[1]
  const data = sheet.slice(2)
  return data.map((row, index) => ({
    mappings: [
      {
        source: header.join(' / '),
        target: 'className / dayOfWeek / periodNo / courseName / teacherName / roomName',
        note: '表头与列顺序已识别',
      },
    ],
    rows: [
      {
        values: {
          className: row[0],
          dayOfWeek: row[1],
          periodNo: row[2],
          courseName: row[3],
          teacherName: row[4],
          roomName: row[5],
        },
      },
    ],
    explanation: `第 ${index + 1}/${data.length} 批（${row[0]}）已识别。`,
  }))
}

/** Uploads the batched sheet and waits for the PARSING session with its progress panel. */
async function startBatchedImport(page: Page): Promise<void> {
  await openTimetableImport(page)
  await uploadExcel(
    page,
    'timetable-batched.xlsx',
    buildXlsx(batchedSheet(), { sheetName: '高一年级课表' }),
  )
  await page.getByRole('button', { name: '开始解析' }).click()
  await expect(page.getByTestId('import-progress')).toBeVisible({ timeout: 30_000 })
  await expect(page).toHaveURL(/session=\d+/)
}

/** Swaps the stub's timetable answer for one test, restoring it even on failure. */
async function withTimetablePayload<T>(
  payload: Record<string, unknown>,
  run: () => Promise<T>,
): Promise<T> {
  const previous = timetablePayload
  timetablePayload = payload
  try {
    return await run()
  } finally {
    timetablePayload = previous
  }
}

/** Sets the batch stub's behaviour for one test and always restores the defaults. */
async function withBatchControl<T>(
  control: { latencyMs?: number; failChunks?: number[] },
  run: () => Promise<T>,
): Promise<T> {
  batchControl = { latencyMs: 0, failChunks: [], ...control }
  try {
    return await run()
  } finally {
    batchControl = { latencyMs: 0, failChunks: [] }
    batchPayloads = []
  }
}

async function seedImportSchool(context: APIRequestContext): Promise<ImportSeed> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const semester = await apiCall(context, token, 'post', '/semesters', {
    name: `E2E导入学期-${suffix}`,
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
  const grade = await apiCall(context, token, 'post', `/grades?semesterId=${semester.id}`, {
    name: `高一-${suffix}`,
    sortOrder: 1,
  })

  const names: ImportSeed = {
    suffix,
    semesterId: semester.id,
    classOne: `高一1班-${suffix}`,
    classTwo: `高一2班-${suffix}`,
    teacherOne: `张老师-${suffix}`,
    teacherTwo: `李老师-${suffix}`,
    courseOne: `语文-${suffix}`,
    courseTwo: `数学-${suffix}`,
    room: `101教室-${suffix}`,
    importedTeacherOne: `导入教师甲-${suffix}`,
    importedTeacherTwo: `导入教师乙-${suffix}`,
  }

  await apiCall(context, token, 'post', '/teachers', {
    name: names.teacherOne,
    employeeNo: `T1-${suffix}`,
  })
  await apiCall(context, token, 'post', '/teachers', {
    name: names.teacherTwo,
    employeeNo: `T2-${suffix}`,
  })
  await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: names.classOne,
    studentCount: 45,
  })
  await apiCall(context, token, 'post', `/classes?semesterId=${semester.id}`, {
    gradeId: grade.id,
    name: names.classTwo,
    studentCount: 42,
  })
  await apiCall(context, token, 'post', '/courses', {
    name: names.courseOne,
    subject: '语文',
    allowConsecutive: false,
  })
  await apiCall(context, token, 'post', '/courses', {
    name: names.courseTwo,
    subject: '数学',
    allowConsecutive: false,
  })
  await apiCall(context, token, 'post', '/rooms', {
    name: names.room,
    capacity: 50,
    type: 'NORMAL',
  })
  return names
}

/** The sheet is deliberately messy: a merged title row sits above the header. */
function timetableSheet(seedData: ImportSeed): string[][] {
  return [
    ['高一年级课程表', '', '', '', '', ''],
    ['班级', '星期', '节次', '课程', '教师', '教室'],
    [seedData.classOne, '周一', '第1节', seedData.courseOne, seedData.teacherOne, seedData.room],
    [seedData.classOne, '周一', '第2节', seedData.courseTwo, seedData.teacherTwo, seedData.room],
    [seedData.classOne, '周二', '第1节', `物理-${seedSuffix(seedData)}`, seedData.teacherOne, seedData.room],
    [seedData.classTwo, '周一', '第1节', seedData.courseTwo, seedData.teacherTwo, seedData.room],
  ]
}

function teacherSheet(seedData: ImportSeed): string[][] {
  return [
    ['姓名', '工号', '电话', '学科'],
    [seedData.importedTeacherOne, `E1-${seedSuffix(seedData)}`, '13800000001', '语文'],
    [seedData.importedTeacherTwo, `E2-${seedSuffix(seedData)}`, '', '数学'],
  ]
}

function timetablePreview(seedData: ImportSeed): Record<string, unknown> {
  const values = (
    className: string,
    dayOfWeek: string,
    periodNo: string,
    courseName: string,
    teacherName: string,
  ) => ({
    className,
    dayOfWeek,
    periodNo,
    courseName,
    teacherName,
    roomName: seedData.room,
  })
  return {
    mappings: [
      { source: '标题行', target: '', note: '第一行是课表标题，已跳过' },
      { source: '班级 / 星期 / 节次', target: 'className / dayOfWeek / periodNo', note: '表头与列顺序已识别' },
    ],
    rows: [
      { values: values(seedData.classOne, '周一', '第1节', seedData.courseOne, seedData.teacherOne) },
      { values: values(seedData.classOne, '周一', '第2节', seedData.courseTwo, seedData.teacherTwo) },
      {
        values: values(
          seedData.classOne,
          '周二',
          '第1节',
          `物理-${seedSuffix(seedData)}`,
          seedData.teacherOne,
        ),
      },
      { values: values(seedData.classTwo, '周一', '第1节', seedData.courseTwo, seedData.teacherTwo) },
    ],
    explanation: '识别为高一年级课表，一行一节课。',
  }
}

/** Two rows: a seeded class plus one whose name only implies its grade. */
function inferredGradePreview(seedData: ImportSeed, newClass: string): Record<string, unknown> {
  const values = (className: string, periodNo: string, courseName: string, teacherName: string) => ({
    className,
    dayOfWeek: '周一',
    periodNo,
    courseName,
    teacherName,
    roomName: seedData.room,
  })
  return {
    mappings: [
      { source: '班级 / 星期 / 节次', target: 'className / dayOfWeek / periodNo', note: '表头与列顺序已识别' },
    ],
    rows: [
      { values: values(seedData.classOne, '第1节', seedData.courseOne, seedData.teacherOne) },
      { values: values(newClass, '第2节', seedData.courseTwo, seedData.teacherTwo) },
    ],
    explanation: '识别为班级课表，一行一节课。',
  }
}

function teacherPreview(seedData: ImportSeed): Record<string, unknown> {
  return {
    mappings: [{ source: '姓名 / 工号 / 电话 / 学科', target: 'name / employeeNo / phone / subject', note: '表头已识别' }],
    rows: [
      {
        values: {
          name: seedData.importedTeacherOne,
          employeeNo: `E1-${seedSuffix(seedData)}`,
          phone: '13800000001',
          email: '',
          subject: '语文',
        },
      },
      {
        values: {
          name: seedData.importedTeacherTwo,
          employeeNo: `E2-${seedSuffix(seedData)}`,
          phone: '',
          email: '',
          subject: '数学',
        },
      },
    ],
    explanation: '识别为教师名单，姓名与学科已对应。',
  }
}

async function startStub(
  timetable: () => Record<string, unknown>,
  teacher: () => Record<string, unknown>,
): Promise<http.Server> {
  const server = http.createServer(async (req, res) => {
    if (req.method !== 'POST' || !req.url?.endsWith('/chat/completions')) {
      res.writeHead(404).end()
      return
    }
    const body = JSON.parse(await readBody(req))
    const prompt = (body.messages ?? [])
      .map((message: { content?: unknown }) =>
        typeof message.content === 'string' ? message.content : JSON.stringify(message.content),
      )
      .join('\n')
    const isTimetable = prompt.includes('目标对象：timetable') || prompt.includes('"target":"timetable"')
    // A chunked parse asks one batch at a time; those prompts say which one it is.
    const batch = /"chunk":"(\d+)\/(\d+)"/.exec(prompt)
    if (batch) {
      const index = Number(batch[1])
      if (batchControl.latencyMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, batchControl.latencyMs))
      }
      if (batchControl.failChunks.includes(index)) {
        // 4xx is not retried by the gateway, so the failure is the batch's own.
        res.writeHead(400, { 'Content-Type': 'application/json' })
        res.end(
          JSON.stringify({ error: { message: `stub 无法解析第 ${index} 批`, type: 'invalid_request_error' } }),
        )
        return
      }
      sendCompletion(res, body, JSON.stringify(batchPayloads[index - 1] ?? timetablePayload))
      return
    }
    const content = JSON.stringify(isTimetable ? timetable() : teacher())
    sendCompletion(res, body, content)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  return server
}

/** Writes one OpenAI-compatible chat completion carrying `content`. */
function sendCompletion(
  res: http.ServerResponse,
  request: { model?: string },
  content: string,
): void {
  res.writeHead(200, { 'Content-Type': 'application/json' })
  res.end(
    JSON.stringify({
      id: 'stub-chat-completion',
      object: 'chat.completion',
      model: request.model ?? 'stub-model',
      choices: [
        {
          index: 0,
          message: { role: 'assistant', content },
          finish_reason: 'stop',
        },
      ],
      usage: { prompt_tokens: 12, completion_tokens: 34, total_tokens: 46 },
    }),
  )
}

async function readBody(req: http.IncomingMessage): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of req) {
    chunks.push(Buffer.from(chunk))
  }
  return Buffer.concat(chunks).toString('utf8')
}

async function configureImportProvider(context: APIRequestContext, baseUrl: string): Promise<void> {
  const token = await apiLogin(context)
  const suffix = Math.random().toString(36).slice(2, 8)
  const provider = await apiCall(context, token, 'post', '/ai-providers', {
    name: `E2E Stub ${suffix}`,
    type: 'custom',
    protocol: 'openai',
    baseUrl: `${baseUrl}/v1`,
    apiKey: 'stub-key',
    enabled: true,
    models: [
      {
        modelName: 'stub-model',
        displayName: 'Stub Model',
        visionCapable: false,
        enabled: true,
      },
    ],
  })
  providerId = provider.id
  await apiCall(context, token, 'put', '/ai-task-config', {
    mode: 'custom',
    tasks: [{ taskType: 'data_import', providerId: provider.id, modelId: provider.models[0].id }],
  })
}

async function uploadExcel(page: Page, name: string, buffer: Buffer): Promise<void> {
  await page.locator('input[type=file]').first().setInputFiles({
    name,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer,
  })
  await expect(page.getByText(`已选择：${name}`)).toBeVisible()
}

/** Element Plus teleports its dropdown; only one is open at a time in these flows. */
async function pickDropdownOption(page: Page, label: string): Promise<void> {
  await page
    .locator('.el-select-dropdown:visible .el-select-dropdown__item')
    .filter({ hasText: label })
    .first()
    .click()
}

function seedSuffix(seedData: ImportSeed): string {
  return seedData.suffix
}
