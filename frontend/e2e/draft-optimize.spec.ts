import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  API,
  apiLogin,
  draftDetail,
  lockLessons,
  newContext,
  openDraft,
  seedDraftLockScenario,
  selectClass,
} from './helpers'

/**
 * Ticket 08: continue optimisation from the current draft, the job panel and the
 * source/new comparison.
 *
 * `resultPublishable=false` is not reproduced here on purpose: building a draft
 * whose post-solve validation is guaranteed to fail (e.g. a locked lesson that
 * must conflict with a hard rule) is timing- and data-dependent. The job panel's
 * two result states are covered deterministically by
 * `src/components/draft/OptimizationJobPanel.spec.ts`; this spec never asserts
 * "publishable" from a job status.
 */

interface JobView {
  id: number
  status: string
  draftId: number | null
  sourceDraftId: number | null
}

async function listJobs(api: APIRequestContext, semesterId: number): Promise<JobView[]> {
  const token = await apiLogin(api)
  const response = await api.get(`${API}/scheduling-jobs?semesterId=${semesterId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(response.status()).toBe(200)
  return response.json()
}

async function startFromDialog(page: Page, maxSeconds: string): Promise<void> {
  const dialog = page.getByTestId('optimization-launch-dialog')
  await expect(dialog).toBeVisible()
  const seconds = page.getByTestId('optimization-max-seconds').locator('input')
  await seconds.fill(maxSeconds)
  await seconds.press('Tab')
  await page.getByTestId('optimization-launch-confirm').click()
  await expect(page.getByTestId('optimization-job-panel')).toBeVisible()
}

test('从当前草稿继续优化：原稿保留、锁继承、比较自动配对', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await lockLessons(api, seed, [seed.mathLessonIds[0]], true)
  const before = await draftDetail(api, seed.draftId)

  const postRequests: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/api/scheduling-jobs')) {
      postRequests.push(request.url())
    }
  })

  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)
  await expect(page.getByTestId('optimization-entry')).toContainText('继承锁定 1 节')

  await page.getByTestId('optimization-continue').click()
  const dialog = page.getByTestId('optimization-launch-dialog')
  const sourceDraftName = before.draft.name
  expect(sourceDraftName).toBeTruthy()
  await expect(dialog).toContainText(sourceDraftName!)
  await expect(dialog).toContainText('锁定 1 节会继承')
  await startFromDialog(page, '3')

  await expect(page.getByTestId('optimization-status')).toContainText('已完成', { timeout: 60_000 })
  await expect(page).toHaveURL(new RegExp(`job=\\d+`))
  expect(postRequests).toHaveLength(1)

  await page.getByTestId('optimization-open-draft').click()
  await expect(page).not.toHaveURL(new RegExp(`/workbench/drafts/${seed.draftId}$`))
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
  const newDraftId = Number(new URL(page.url()).pathname.split('/').pop())
  expect(newDraftId).not.toBe(seed.draftId)

  // The source draft is untouched: same version and the same single lock.
  const afterSource = await draftDetail(api, seed.draftId)
  expect(afterSource.draft.version).toBe(before.draft.version)
  expect(afterSource.lessons.filter((lesson) => lesson.locked).map((lesson) => lesson.id)).toEqual([
    seed.mathLessonIds[0],
  ])

  // The new draft inherits the lock by stable identity, and it survives a reload.
  const afterNew = await draftDetail(api, newDraftId)
  const lockedNew = afterNew.lessons.filter((lesson) => lesson.locked)
  expect(lockedNew).toHaveLength(1)
  expect(lockedNew[0].assignmentId).toBe(afterSource.lessons.find((lesson) => lesson.id === seed.mathLessonIds[0])?.assignmentId)
  expect(lockedNew[0].periodId).toBe(
    afterSource.lessons.find((lesson) => lesson.id === seed.mathLessonIds[0])?.periodId,
  )
  await page.reload()
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
  await selectClass(page, seed.className)
  await expect(page.locator('.grid-card .lesson[data-locked="true"]')).toHaveCount(1)

  // The comparison is paired A=source/B=new straight from the query.
  await page.goto(`/workbench/drafts/${newDraftId}?compare=${seed.draftId}`)
  const diff = page.getByTestId('compare-diff')
  await expect(diff).toBeVisible()
  await expect(diff).toContainText('未锁课节变化')
  await expect(diff).toContainText('锁定课节 1 节保持不变')
  await expect(page.getByTestId('compare-locked-warning')).toHaveCount(0)
})

test('全部课节锁定时入口禁用并说明原因，不产生任务', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  const all = await draftDetail(api, seed.draftId)
  await lockLessons(
    api,
    seed,
    all.lessons.map((lesson) => lesson.id),
    true,
  )
  const jobsBefore = await listJobs(api, seed.semesterId)

  await openDraft(page, seed.draftId)
  await expect(page.getByTestId('optimization-all-locked')).toContainText(
    '全部课节已锁定，没有可优化的课程',
  )
  await expect(page.getByTestId('optimization-continue')).toBeDisabled()

  const jobsAfter = await listJobs(api, seed.semesterId)
  expect(jobsAfter).toHaveLength(jobsBefore.length)
})

test('运行中刷新带 ?job= 只恢复轮询，不重复启动任务', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  const token = await apiLogin(api)
  const created = await api
    .post(`${API}/scheduling-jobs?semesterId=${seed.semesterId}`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { maxSeconds: 60, sourceDraftId: seed.draftId },
    })
    .then((response) => response.json())

  try {
    const posts: string[] = []
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/api/scheduling-jobs')) {
        posts.push(request.url())
      }
    })

    const jobsBefore = await listJobs(api, seed.semesterId)
    await openDraft(page, seed.draftId)
    await page.goto(`/workbench/drafts/${seed.draftId}?job=${created.id}`)

    await expect(page.getByTestId('optimization-job-panel')).toBeVisible()
    await expect(page.getByTestId('optimization-status')).toContainText(/运行中|排队中/)
    await page.waitForTimeout(1500)
    await expect(page.getByTestId('optimization-job-panel')).toBeVisible()

    expect(posts).toHaveLength(0)
    const jobsAfter = await listJobs(api, seed.semesterId)
    expect(jobsAfter).toHaveLength(jobsBefore.length)
    expect(jobsAfter.filter((job) => job.id === created.id)).toHaveLength(1)
  } finally {
    await api.delete(`${API}/scheduling-jobs/${created.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  }
})

test('入口可用键盘 Tab/Enter 打开对话框并发起', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)

  await openDraft(page, seed.draftId)
  await page.getByTestId('optimization-continue').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('optimization-launch-dialog')).toBeVisible()

  await page.getByTestId('optimization-max-seconds').locator('input').fill('1')
  await page.getByTestId('optimization-max-seconds').locator('input').press('Tab')
  await page.getByTestId('optimization-launch-confirm').focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('optimization-job-panel')).toBeVisible()
  await expect(page.getByTestId('optimization-status')).toContainText('已完成', { timeout: 60_000 })
})
