import { expect, test, type ElementHandle, type Page } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool, solveDraft, uiLogin } from './helpers'
import { capture } from './screenshots'

type Api = Awaited<ReturnType<typeof newContext>>

/**
 * Toast wording is product copy and only ever a supporting signal: the assertions that
 * matter read the blockers, the button state and the API. Kept here so a rewording is one edit.
 */
const TOAST_PUBLISHED = /课表已发布/

/** A toast scoped to its own container, so a page-wide text lookup cannot win. */
function toast(page: Page, text: RegExp) {
  return page.locator('.el-message').filter({ hasText: text })
}

async function fireDrag(page: Page, node: ElementHandle, type: string) {
  await page.evaluate(
    ({ element, eventType }) => {
      const dataTransfer = new DataTransfer()
      element.dispatchEvent(new DragEvent(eventType, { bubbles: true, cancelable: true, dataTransfer }))
    },
    { element: node, eventType: type },
  )
}

async function dropOn(page: Page, target: ElementHandle) {
  await fireDrag(page, target, 'dragenter')
  await fireDrag(page, target, 'dragover')
  await fireDrag(page, target, 'drop')
}

/** A solved draft, ready to publish with no extra prerequisites. */
async function seedSolvedDraft(api: Api) {
  const seed = await seedSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)
  const token = await apiLogin(api)
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(detail.lessons.length).toBeGreaterThan(0)
  return { draftId, token, lessons: detail.lessons as Array<{ id: number; periodId: number | null }> }
}

test('场景 2：草稿可以直接发布', async ({ page }) => {
  const api = await newContext()
  const { draftId, token, lessons } = await seedSolvedDraft(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/workbench/drafts/${draftId}`)
  await expect(page.locator('.lesson').first()).toBeVisible()

  // No publishing blocker stands in the way.
  await expect(page.getByTestId('publish-blockers')).toHaveCount(0)
  const publish = page.getByRole('button', { name: '发布课表' })
  await expect(publish).toBeEnabled()

  const before = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}/validation`)
  expect(before.publishable).toBe(true)
  expect(before.blockers).toEqual([])
  expect(before.hardConflicts).toBe(0)
  const draft = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(draft.lessons.length).toBe(lessons.length)
  await capture(page, 'draft_publishable')

  await publish.click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(toast(page, TOAST_PUBLISHED).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: '发布课表' })).toHaveCount(0)
  const published = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(published.draft.status).toBe('PUBLISHED')
})

test('场景 2b：硬冲突仍然阻止发布', async ({ page }) => {
  const api = await newContext()
  const { draftId, token, lessons } = await seedSolvedDraft(api)
  // Force a second lesson of the same class into the first lesson's slot. The whole-draft
  // check must still refuse this.
  await apiCall(api, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lessons[1].id}`, {
    periodId: lessons[0].periodId,
    force: true,
  })

  const validation = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}/validation`)
  expect(validation.hardConflicts).toBeGreaterThan(0)
  expect(validation.publishable).toBe(false)
  expect(validation.blockers.map((blocker: { code: string }) => blocker.code)).toContain(
    'HARD_CONFLICTS',
  )

  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/workbench/drafts/${draftId}`)
  await expect(page.locator('.lesson').first()).toBeVisible()
  await expect(page.locator('[data-testid=publish-blocker][data-code=HARD_CONFLICTS]')).toBeVisible()
  await expect(page.getByRole('button', { name: '发布课表' })).toBeDisabled()
})

test('场景 1b：草稿拖拽冲突高亮与移动', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/workbench/drafts/${draftId}`)
  await expect(page.locator('.lesson').first()).toBeVisible()

  const source = await page.locator('.cell:has(.lesson)').first().locator('.lesson').elementHandle()
  const undoButton = page.getByRole('button', { name: /撤销/ })
  const waitForReload = () =>
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && response.url().endsWith(`/api/timetable-drafts/${draftId}`),
    )
  expect(source).not.toBeNull()
  await expect(undoButton).toBeDisabled()

  // The old "swap candidate drawer" path is gone: an occupied same-class cell is now a
  // direct exchange, covered end to end in draft-drag.spec.ts. This scenario keeps the
  // drag-target highlighting and the empty-cell move/undo contract.
  await fireDrag(page, source!, 'dragstart')
  await expect(page.locator('.cell.invalid-target').first()).toBeVisible({ timeout: 20_000 })
  await fireDrag(page, source!, 'dragend')

  await fireDrag(page, source!, 'dragstart')
  await expect(page.locator('.cell.valid-target:not(:has(.lesson))').first()).toBeVisible({
    timeout: 20_000,
  })
  const freeCell = await page.locator('.cell.valid-target:not(:has(.lesson))').first().elementHandle()
  expect(freeCell).not.toBeNull()
  const firstMoveReload = waitForReload()
  await dropOn(page, freeCell!)
  await firstMoveReload
  await expect(undoButton).toBeEnabled()

  const undoReload = waitForReload()
  await undoButton.click()
  await undoReload
  await expect(undoButton).toBeDisabled()

  const sourceAfterUndo = await page.locator('.cell:has(.lesson)').first().locator('.lesson').elementHandle()
  expect(sourceAfterUndo).not.toBeNull()
  await fireDrag(page, sourceAfterUndo!, 'dragstart')
  await expect(page.locator('.cell.valid-target:not(:has(.lesson))').first()).toBeVisible({
    timeout: 20_000,
  })
  const freeCellAfterUndo = await page
    .locator('.cell.valid-target:not(:has(.lesson))')
    .first()
    .elementHandle()
  expect(freeCellAfterUndo).not.toBeNull()
  const secondMoveReload = waitForReload()
  await dropOn(page, freeCellAfterUndo!)
  await secondMoveReload
  await expect(undoButton).toBeEnabled()

  const keyboardUndoReload = waitForReload()
  await page.keyboard.press('Control+z')
  await expect(undoButton).toBeDisabled()
  await keyboardUndoReload
})

test('场景 1：录入数据 → 自动排课 → 发布 → 查看课表', async ({ page }) => {
  const api = await newContext()
  await seedSchool(api)

  await uiLogin(page, 'admin', 'admin123')

  await page.goto('/workbench')
  await page.getByRole('button', { name: '开始排课' }).click()
  await expect(page.getByText('已完成').first()).toBeVisible({ timeout: 150_000 })

  await page.getByRole('button', { name: '打开草稿' }).first().click()
  await expect(page).toHaveURL(/\/workbench\/drafts\/\d+/)
  await expect(page.locator('.lesson').first()).toBeVisible()
  // The meta line shows the teacher only, no separator tail.
  await expect(page.locator('.grid .lesson .lesson-meta').first()).not.toContainText('·')

  await page.getByRole('button', { name: '发布课表' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(toast(page, TOAST_PUBLISHED).first()).toBeVisible({ timeout: 30_000 })

  await page.goto('/workbench/timetable')
  await expect(page.locator('.lesson').first()).toBeVisible()
  await expect(page.locator('.grid .lesson .lesson-meta').first()).not.toContainText('·')
})

test('场景 3：未启用钉钉时系统完整可用', async ({ page }) => {
  const api = await newContext()
  const token = await apiLogin(api)
  const config = await apiCall(api, token, 'get', '/integrations/dingtalk/config')
  expect(config.enabled).toBe(false)

  await seedSchool(api)
  await uiLogin(page, 'admin', 'admin123')

  await page.goto('/workbench/timetable')
  await expect(page.getByRole('heading', { name: '课表', exact: true })).toBeVisible()

  await page.goto('/settings/general')
  await expect(page.getByText('钉钉集成')).toBeVisible()
})
