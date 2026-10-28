import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test'
import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { apiCall, apiLogin, uiLogin } from './helpers'
import { capture } from './screenshots'
import { buildWorkbookXlsx, buildXlsx } from './xlsx'

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
/**
 * Whole-workbook extraction pacing. A latency keeps the session in PARSING long enough
 * for the page (and a reload) to observe the background progress.
 */
let workbookControl: { latencyMs: number } = { latencyMs: 0 }
/**
 * How many chat completions the stub has answered. Read either side of an action to
 * prove the action re-used the saved preview instead of paying for another parse.
 */
let chatRequests = 0
/** Toast wording is product copy: one place, and only ever a supporting signal. */
const TOAST_PERIODS_APPENDED = /已补充 1 节课时/

/** A toast by its own container, so a matching notification title can never win. */
function toast(page: Page, text: RegExp) {
  return page.locator('.el-message').filter({ hasText: text })
}

test.beforeEach(() => {
  workbookControl = { latencyMs: 0 }
})

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

  await expect(page.getByTestId('target-card')).toHaveCount(7)

  const workbook = page.locator('[data-testid=target-card][data-target=workbook]')
  await expect(workbook).toContainText('整本工作簿')
  await expect(workbook).toContainText('多张工作表')
  await expect(workbook).toContainText('Excel / CSV')
  await expect(workbook).toContainText('已选择')

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

test('排课页的「导入课表」入口默认整本工作簿目标', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/scheduling')
  await page.getByRole('button', { name: '导入课表' }).click()

  await expect(page).toHaveURL(/\/admin\/ai-import$/)
  await expect(page.locator('[data-target=workbook]')).toContainText('已选择')
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
  // Picking a card opts back into the single-target parse this regression covers.
  await page.locator('[data-target=teacher]').click()
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

test('多工作表工作簿：一次识别四类数据、跳过说明表并生成草稿', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  const suffix = seedSuffix(seed)
  const workbook = buildWorkbookXlsx([
    {
      name: '数据源',
      rows: [
        ['班级', '星期', '节次', '课程', '教师'],
        [`高一3班-${suffix}`, '星期一', '1', `导入课程甲-${suffix}`, `导入教师甲-${suffix}`],
        [`高一3班-${suffix}`, '星期一', '2', `导入课程乙-${suffix}`, `导入教师乙-${suffix}`],
        [`高一3班-${suffix}`, '星期五', '7', '', ''],
      ],
    },
    {
      name: '教师名单',
      rows: [
        ['姓名', '手机'],
        [`导入教师甲-${suffix}`, '13800000011'],
      ],
    },
    {
      name: '使用说明',
      rows: [['使用说明'], ['1. 本表由教务系统导出'], ['2. 请勿修改列顺序']],
    },
  ])

  await uploadExcel(page, '真实教务工作簿.xlsx', workbook)
  await page.getByRole('button', { name: '开始解析' }).click()

  await expect(page.getByTestId('workbook-summary')).toBeVisible()
  const sheets = page.getByTestId('workbook-sheets')
  await expect(sheets).toContainText('数据源')
  await expect(sheets).toContainText('教师名单')
  await expect(sheets).toContainText('使用说明')
  await expect(sheets).toContainText('非空单元格少于 5 个')
  await expect(page.getByTestId('workbook-summary')).toContainText('结构已确认')
  await expect(page.getByText('空课位 1 个（没有课程与教师），已跳过')).toBeVisible()
  await expect(page.getByTestId('timetable-summary')).toContainText('共 2 节')

  await page.getByRole('button', { name: '确认入库' }).click()

  await expect(page.getByTestId('commit-result')).toBeVisible()
  await expect(page.getByTestId('commit-categories')).toContainText('课表')
  await expect(page.getByTestId('commit-categories')).toContainText('教师名单')

  await page.getByRole('button', { name: '查看草稿' }).click()
  await expect(page).toHaveURL(/\/admin\/drafts\/\d+/)
})

test('整本工作簿在后台分批解析，刷新后仍能看到进度并走到预览', async ({ page }) => {
  workbookControl = { latencyMs: 2500 }
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  await expect(page.getByText('AI 已就绪')).toBeVisible()

  const suffix = seedSuffix(seed)
  const rows = [['班级', '星期', '节次', '课程', '教师']]
  for (let klass = 1; klass <= 3; klass++) {
    for (const day of ['星期一', '星期二', '星期三']) {
      for (let period = 1; period <= 5; period++) {
        rows.push([
          `后台班${klass}-${suffix}`,
          day,
          String(period),
          `后台课程${suffix}`,
          `后台教师${suffix}`,
        ])
      }
    }
  }
  await uploadExcel(page, '后台工作簿.xlsx', buildWorkbookXlsx([{ name: '数据源', rows }]))
  await page.getByRole('button', { name: '开始解析' }).click()

  const progress = page.getByTestId('import-progress')
  await expect(progress).toBeVisible({ timeout: 30_000 })
  // The upload answered before extraction finished: a progress view, not an empty preview.
  await expect(progress.getByTestId('progress-count')).toContainText('已完成', {
    timeout: 30_000,
  })
  await capture(page, 'workbook_background_progress')
  expect(page.url()).toMatch(/session=\d+/)

  // A refresh restores the PARSING session and keeps polling instead of losing the work.
  await page.reload()
  await expect(page.getByTestId('import-progress')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('timetable-summary')).toBeVisible({ timeout: 120_000 })
  await expect(page.getByTestId('timetable-summary')).toContainText('共 45 节')
  await capture(page, 'workbook_background_preview')
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
    await expect(page.getByTestId('target-card')).toHaveCount(7)
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
    await expect(page.getByTestId('target-card')).toHaveCount(7)
    await expect(page).not.toHaveURL(/session=/)
  })
})

// ------------------------------------------------- 十一期：可疑教师值 / 作息缺节

test('可疑教师值：标黄列在预览里，正常姓名不受影响，仍可确认入库', async ({ page }) => {
  const workbook = buildWorkbookXlsx([
    {
      name: '数据源',
      rows: [
        ['班级', '星期', '节次', '课程', '教师'],
        [seed.classOne, '星期一', '1', '信息科技', '信息技术'],
        [seed.classOne, '星期一', '2', seed.courseOne, '张伟'],
      ],
    },
    {
      name: '教师名单',
      rows: [
        ['姓名', '手机'],
        ['王芳', '13800000012'],
      ],
    },
  ])

  await startWorkbookImport(page, '疑似教师值.xlsx', workbook)

  // The value that is a subject, not a person, is called out; the real-looking names are not.
  const hint = page.getByTestId('teacher-warning-hint')
  await expect(hint).toBeVisible()
  await expect(hint).toContainText('信息技术')
  await expect(hint).not.toContainText('张伟')

  const warnings = page.getByTestId('teacher-warnings-table')
  await expect(warnings).toContainText('信息技术')
  await expect(warnings).toContainText('命中占位词')
  await expect(warnings).not.toContainText('张伟')
  await expect(warnings).not.toContainText('王芳')

  // Exactly the lesson carrying that value is outlined in warning yellow, by row.
  const grid = page.locator('.timetable-preview .grid')
  await expect(grid.locator('.lesson.warning')).toHaveCount(1)
  await expect(grid.locator('.lesson.warning')).toContainText('信息科技')
  await expect(grid.locator('.lesson.warning')).toContainText('信息技术')
  await capture(page, 'suspicious_teacher_warning')

  // A warning is not a blocker: the confirmed import still writes the draft.
  const commit = page.locator('.footer-actions').getByRole('button', { name: /确认入库/ })
  await expect(commit).toBeEnabled()
  await commit.click()
  await expect(page.getByTestId('commit-result')).toBeVisible()
  await expect(page.getByTestId('commit-categories')).toContainText('课表')
  await page.getByRole('button', { name: '查看草稿' }).click()
  await page.waitForURL(/\/admin\/drafts\/\d+/)
  await expect(page.locator('.lesson').first()).toBeVisible()

  const draftId = Number(page.url().match(/\/admin\/drafts\/(\d+)/)?.[1])
  const token = await apiLogin(api)
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(detail.lessons).toHaveLength(2)
  expect(detail.lessons.map((lesson: { courseName: string }) => lesson.courseName)).toContain(
    '信息科技',
  )
  const teacherNames = (await apiCall(api, token, 'get', '/teachers')).map(
    (teacher: { name: string }) => teacher.name,
  )
  expect(teacherNames).toContain('信息技术')
  expect(teacherNames).toContain('张伟')
})

test('作息缺节：可推算项确认补进作息后清单消失并成功入库，全程不重新解析', async ({ page }) => {
  const workbook = buildWorkbookXlsx([
    {
      name: '数据源',
      rows: [
        ['班级', '星期', '节次', '课程', '教师'],
        [seed.classOne, '周一', '第11节', seed.courseOne, seed.teacherOne],
        [seed.classOne, '周一', '第1节', seed.courseTwo, seed.teacherTwo],
      ],
    },
  ])

  await startWorkbookImport(page, '缺节课表.xlsx', workbook)
  // Only the rows the workbook has; the 第11节 row cannot be drawn anywhere yet.
  await expect(page.getByTestId('timetable-summary')).toContainText('共 2 节')

  const panel = page.getByTestId('missing-periods')
  await expect(panel).toBeVisible()
  await expect(panel).toContainText('1 项')
  const item = panel.locator('.el-table__body tr').first()
  await expect(item).toContainText('周一')
  await expect(item).toContainText('第11节')
  await expect(item).toContainText('可自动补')
  await expect(panel.getByTestId('missing-period-check-1-11')).toBeChecked()
  await capture(page, 'missing_periods_panel')

  // The gap list comes from the saved preview, so confirming it must not call the model.
  const callsBefore = chatRequests
  await panel.getByTestId('missing-periods-apply').click()
  const confirm = page.locator('.el-message-box')
  await expect(confirm).toContainText('补齐作息缺少的节次')
  await expect(confirm).toContainText('周一 第11节')
  await confirm.getByRole('button', { name: '确认补节' }).click()

  await expect(toast(page, TOAST_PERIODS_APPENDED).first()).toBeVisible()
  // The one gap is closed: the panel is gone rather than showing a green "all done".
  await expect(page.getByTestId('missing-periods')).toHaveCount(0)
  expect(chatRequests).toBe(callsBefore)

  // The appended period really exists, and the import now writes a complete draft.
  const token = await apiLogin(api)
  const periods = await apiCall(api, token, 'get', `/semesters/${seed.semesterId}/periods`)
  expect(
    periods.some(
      (period: { dayOfWeek: number; periodNo: number }) =>
        period.dayOfWeek === 1 && period.periodNo === 11,
    ),
  ).toBe(true)

  const commit = page.locator('.footer-actions').getByRole('button', { name: /确认入库/ })
  await expect(commit).toBeEnabled()
  await commit.click()
  await expect(page.getByTestId('commit-result')).toBeVisible()
  await expect(page.getByTestId('commit-categories')).toContainText('课表')
  await page.getByRole('button', { name: '查看草稿' }).click()
  await page.waitForURL(/\/admin\/drafts\/\d+/)

  const draftId = Number(page.url().match(/\/admin\/drafts\/(\d+)/)?.[1])
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(detail.lessons).toHaveLength(2)
  expect(chatRequests).toBe(callsBefore)
})

test('作息缺节：不可推算的项标注需人工，不提供写入入口', async ({ page }) => {
  const workbook = buildWorkbookXlsx([
    {
      name: '数据源',
      rows: [
        ['班级', '星期', '节次', '课程', '教师'],
        [seed.classOne, '周日', '第20节', seed.courseOne, seed.teacherOne],
      ],
    },
  ])

  await startWorkbookImport(page, '缺节不可推算.xlsx', workbook)

  const panel = page.getByTestId('missing-periods')
  await expect(panel).toBeVisible()
  const item = panel.locator('.el-table__body tr').first()
  await expect(item).toContainText('周日')
  await expect(item).toContainText('第20节')
  await expect(item).toContainText('需人工')
  await expect(item).toContainText('没有可参照的作息时间')

  // Nothing here may be confirmed: no tick, no apply button, only the way to the period form.
  await expect(panel.getByTestId('missing-period-check-7-20')).toHaveCount(0)
  await expect(panel.getByTestId('missing-periods-apply')).toHaveCount(0)
  await expect(panel.getByTestId('missing-periods-manage')).toBeVisible()

  const token = await apiLogin(api)
  const periods = await apiCall(api, token, 'get', `/semesters/${seed.semesterId}/periods`)
  expect(
    periods.some(
      (period: { dayOfWeek: number; periodNo: number }) =>
        period.dayOfWeek === 7 && period.periodNo === 20,
    ),
  ).toBe(false)
})

// --------------------------------------------------------------------- fixtures

/** Uploads a whole workbook, parses it and waits for the merged preview. */
async function startWorkbookImport(page: Page, name: string, workbook: Buffer): Promise<void> {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  await expect(page.getByText('AI 已就绪')).toBeVisible()
  await uploadExcel(page, name, workbook)
  await page.getByRole('button', { name: '开始解析' }).click()
  await expect(page.getByTestId('timetable-summary')).toBeVisible({ timeout: 60_000 })
  await expect(page).toHaveURL(/session=\d+/)
}

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

const WORKBOOK_DATA_MARKER = '数据（JSON）：'

/** What the stub decides a multi-sheet workbook is: one timetable sheet and one list. */
function workbookPlan(): Record<string, unknown> {
  return {
    tool: 'finish',
    args: {
      sheets: [
        {
          sheet: '数据源',
          category: 'timetable',
          columns: {
            className: '班级',
            dayOfWeek: '星期',
            periodNo: '节次',
            courseName: '课程',
            teacherName: '教师',
          },
          note: '一行一节课',
        },
        {
          sheet: '教师名单',
          category: 'teacher',
          columns: { name: '姓名', phone: '手机' },
          note: '教师名单补充联系方式',
        },
      ],
      explanation: '数据源是逐节课的查询结果，教师名单提供联系方式。',
    },
  }
}

/** Copies the batch's mapped columns into fields, the way a cooperating model would. */
function workbookExtraction(prompt: string): string {
  const marker = prompt.indexOf(WORKBOOK_DATA_MARKER)
  const payload = JSON.parse(prompt.slice(marker + WORKBOOK_DATA_MARKER.length).trim()) as {
    columns: Record<string, string>
    rows: string[][]
  }
  const fields = Object.keys(payload.columns)
  const rows = payload.rows.map((cells) => {
    const values: Record<string, string> = {}
    const confidence: Record<string, number> = {}
    fields.forEach((field, index) => {
      values[field] = cells[index] ?? ''
      confidence[field] = 1
    })
    return { values, confidence }
  })
  return JSON.stringify({ rows, explanation: '' })
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
    chatRequests++
    const body = JSON.parse(await readBody(req))
    const prompt = (body.messages ?? [])
      .map((message: { content?: unknown }) =>
        typeof message.content === 'string' ? message.content : JSON.stringify(message.content),
      )
      .join('\n')
    // Phase 11: the workbook pipeline first asks the model to browse with tools, then
    // extracts batch by batch under the mapping it declared.
    if (prompt.includes('只读工具')) {
      sendCompletion(res, body, JSON.stringify(workbookPlan()))
      return
    }
    if (prompt.includes(WORKBOOK_DATA_MARKER)) {
      if (workbookControl.latencyMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, workbookControl.latencyMs))
      }
      sendCompletion(res, body, workbookExtraction(prompt))
      return
    }
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

/**
 * A commit is normally too fast to observe from the page, and the status only becomes
 * COMMITTING while another request owns the session. Stubbing the session read is how the
 * restored page's "入库中" state and its polling are exercised deterministically.
 */
test('入库中的会话恢复后显示禁用按钮，并自动刷新到已入库', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/ai-import')
  const suffix = seedSuffix(seed)
  const workbook = buildWorkbookXlsx([
    {
      name: '数据源',
      rows: [
        ['班级', '星期', '节次', '课程', '教师'],
        [`高一5班-${suffix}`, '星期一', '1', `入库课程-${suffix}`, `入库教师-${suffix}`],
      ],
    },
  ])
  await uploadExcel(page, '入库中状态.xlsx', workbook)
  await page.getByRole('button', { name: '开始解析' }).click()
  await expect(page.getByTestId('workbook-summary')).toBeVisible()
  await expect(page).toHaveURL(/session=\d+/)
  const sessionId = new URL(page.url()).searchParams.get('session')!
  expect(sessionId).toBeTruthy()

  let sessionGets = 0
  await page.route(`**/api/ai/import/sessions/${sessionId}`, async (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    sessionGets += 1
    const response = await route.fetch()
    const body = await response.json()
    await route.fulfill({
      response,
      json: { ...body, status: sessionGets <= 3 ? 'COMMITTING' : 'COMMITTED' },
    })
  })

  await page.goto(`/admin/ai-import?session=${sessionId}&target=workbook`)

  await expect(page.getByTestId('import-status')).toHaveText('入库中')
  await expect(
    page.locator('.footer-actions').getByRole('button', { name: /入库中/ }),
  ).toBeDisabled()
  // The restored page keeps polling, so the transient status settles without a manual reload.
  await expect(page.getByTestId('import-status')).toHaveText('已入库', { timeout: 15000 })
})

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
