import { expect, test, type ElementHandle, type Page } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool, uiLogin } from './helpers'
import { capture } from './screenshots'

type Api = Awaited<ReturnType<typeof newContext>>

/**
 * Toast wording is product copy and only ever a supporting signal: the assertions that
 * matter read the blockers, the button state and the API. Kept here so a rewording is one edit.
 */
const TOAST_ROOMS_FILLED = /已新建 1 间教室/
const TOAST_PUBLISHED = /课表已发布/

/**
 * A toast by its own container. The same wording also appears in the notification centre
 * ("课表已发布" is a notification title), so a page-wide text lookup can resolve to the
 * hidden notification instead of the message that was just shown.
 */
function toast(page: Page, text: RegExp) {
  return page.locator('.el-message').filter({ hasText: text })
}

async function solveDraft(api: Api, semesterId: number): Promise<number> {
  const token = await apiLogin(api)
  const job = await apiCall(api, token, 'post', `/scheduling-jobs?semesterId=${semesterId}`, {
    maxSeconds: 5,
  })
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    const current = await apiCall(api, token, 'get', `/scheduling-jobs/${job.id}`)
    if (current.status === 'COMPLETED') return current.draftId as number
    if (current.status === 'FAILED' || current.status === 'CANCELLED') {
      throw new Error(`scheduling failed: ${current.errorMessage}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error('scheduling timed out')
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

/**
 * A solved draft whose lessons all lost their room, i.e. the shape a real workbook
 * import leaves behind (ADR-0026: importing never invents rooms). The solver refuses to
 * run without any room at all, so the rooms are cleared afterwards through the same
 * lesson write path the editor uses.
 */
async function seedDraftWithoutRooms(api: Api) {
  const seed = await seedSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)
  const token = await apiLogin(api)
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(detail.lessons.length).toBeGreaterThan(0)
  for (const lesson of detail.lessons) {
    await apiCall(api, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
      periodId: lesson.periodId,
      roomId: null,
      force: true,
    })
  }
  const classes = await apiCall(api, token, 'get', `/classes?semesterId=${seed.semesterId}`)
  return {
    draftId,
    token,
    className: classes[0].name as string,
    lessonCount: detail.lessons.length as number,
  }
}

/** Rooms are global, so the count is how a duplicate creation shows up. */
async function roomCount(api: Api, token: string): Promise<number> {
  return (await apiCall(api, token, 'get', '/rooms')).length
}

test('场景 2：整草稿检查指出缺教室，一键补教室后即可发布', async ({ page }) => {
  const api = await newContext()
  const { draftId, token, className, lessonCount } = await seedDraftWithoutRooms(api)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/admin/drafts/${draftId}`)
  await expect(page.locator('.lesson').first()).toBeVisible()

  // The whole-draft check reports the gap the publisher would refuse (ticket 13), so
  // the page has no green "passed" state and the publish button stays disabled.
  const missingRooms = page.locator('[data-testid=publish-blocker][data-code=MISSING_ROOMS]')
  await expect(missingRooms).toBeVisible()
  await expect(missingRooms).toContainText(`有 ${lessonCount} 节课还没有分配教室`)
  const publish = page.getByRole('button', { name: '发布课表' })
  await expect(publish).toBeDisabled()

  const before = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}/validation`)
  expect(before.publishable).toBe(false)
  expect(before.blockers.map((blocker: { code: string }) => blocker.code)).toContain('MISSING_ROOMS')

  // The preview names the class, how the capacity is derived and how many lessons it covers.
  // The footer lives outside the body element that carries the test id, so the scope is
  // the whole dialog that contains it.
  const dialog = page.locator('.el-dialog').filter({ has: page.getByTestId('room-fill-dialog') })
  await page.getByTestId('blocker-action-MISSING_ROOMS').click()
  await expect(dialog.getByTestId('room-fill-dialog')).toBeVisible()
  const planned = dialog.locator('.el-table__body tr').first()
  await expect(planned).toContainText(className)
  await expect(planned).toContainText('-固定教室')
  await expect(planned).toContainText('新建')
  await expect(planned.locator('td').nth(3)).toHaveText('45 人 · 45 座')
  await expect(planned.locator('td').nth(4)).toHaveText(String(lessonCount))
  await capture(page, 'room_fill_dialog_before_confirm')

  // Opening the preview writes nothing, and reopening it does not stack up rooms.
  const roomsBefore = await roomCount(api, token)
  await dialog.getByRole('button', { name: '取消' }).click()
  await expect(dialog).toBeHidden()
  await page.getByTestId('blocker-action-MISSING_ROOMS').click()
  await expect(dialog.locator('.el-table__body tr')).toHaveCount(1)
  expect(await roomCount(api, token)).toBe(roomsBefore)

  await dialog.getByTestId('room-fill-confirm').click()
  await expect(toast(page, TOAST_ROOMS_FILLED).first()).toBeVisible()

  // The blocker is gone, publishing is allowed, and the room exists exactly once.
  await expect(page.getByTestId('publish-blockers')).toHaveCount(0)
  await expect(publish).toBeEnabled()
  expect(await roomCount(api, token)).toBe(roomsBefore + 1)
  const after = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}/validation`)
  expect(after.publishable).toBe(true)
  expect(after.blockers).toEqual([])
  const filled = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(filled.lessons.every((lesson: { roomId: number | null }) => lesson.roomId !== null)).toBe(true)
  await capture(page, 'room_fill_applied_draft_state')

  await publish.click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(toast(page, TOAST_PUBLISHED).first()).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: '发布课表' })).toHaveCount(0)
  const published = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  expect(published.draft.status).toBe('PUBLISHED')
  expect(await roomCount(api, token)).toBe(roomsBefore + 1)
})

test('场景 2b：重复执行补教室不会重复建室', async ({ page }) => {
  const api = await newContext()
  const { draftId, token } = await seedDraftWithoutRooms(api)
  const before = await roomCount(api, token)

  // The preview is read-only: it may be asked for as often as the page likes.
  const plan = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}/room-fill/plan`)
  expect(plan.applicable).toBe(true)
  expect(plan.classes).toHaveLength(1)
  expect(plan.createdRooms).toBe(1)
  expect(await roomCount(api, token)).toBe(before)

  const first = await apiCall(api, token, 'post', `/timetable-drafts/${draftId}/room-fill`)
  expect(first.createdRooms).toBe(1)
  const after = await roomCount(api, token)
  expect(after).toBe(before + 1)

  // A second confirmation has no empty rooms left to fill, so nothing is created again.
  const second = await apiCall(api, token, 'post', `/timetable-drafts/${draftId}/room-fill`)
  expect(second.createdRooms).toBe(0)
  expect(second.assignedLessons).toBe(0)
  expect(await roomCount(api, token)).toBe(after)

  // Nothing user-visible changed on the page either: no blocker, publishing allowed.
  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/admin/drafts/${draftId}`)
  await expect(page.locator('.lesson').first()).toBeVisible()
  await expect(page.getByTestId('publish-blockers')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '发布课表' })).toBeEnabled()
})

test('场景 1b：草稿拖拽冲突高亮与移动', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  const draftId = await solveDraft(api, seed.semesterId)

  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/admin/drafts/${draftId}`)
  await expect(page.locator('.lesson').first()).toBeVisible()

  const source = await page.locator('.cell:has(.lesson)').first().locator('.lesson').elementHandle()
  const conflictCell = await page.locator('.cell:has(.lesson)').nth(1).elementHandle()
  const undoButton = page.getByRole('button', { name: /撤销/ })
  const waitForReload = () =>
    page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && response.url().endsWith(`/api/timetable-drafts/${draftId}`),
    )
  expect(source).not.toBeNull()
  expect(conflictCell).not.toBeNull()
  await expect(undoButton).toBeDisabled()

  await fireDrag(page, source!, 'dragstart')
  await expect(page.locator('.cell.invalid-target').first()).toBeVisible({ timeout: 20_000 })
  await dropOn(page, conflictCell!)
  await expect(page.getByText('换课候选')).toBeVisible()
  await page.locator('.el-drawer__close-btn').click()
  await expect(page.getByText('换课候选')).toBeHidden()

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

  await page.goto('/admin/scheduling')
  await page.getByRole('button', { name: '开始排课' }).click()
  await expect(page.getByText('已完成').first()).toBeVisible({ timeout: 150_000 })

  await page.getByRole('button', { name: '打开草稿' }).first().click()
  await expect(page).toHaveURL(/\/admin\/drafts\/\d+/)
  await expect(page.locator('.lesson').first()).toBeVisible()

  await page.getByRole('button', { name: '发布课表' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(toast(page, TOAST_PUBLISHED).first()).toBeVisible({ timeout: 30_000 })

  await page.goto('/timetable')
  await expect(page.locator('.lesson').first()).toBeVisible()
})

test('场景 3：未启用钉钉时系统完整可用', async ({ page }) => {
  const api = await newContext()
  const token = await apiLogin(api)
  const config = await apiCall(api, token, 'get', '/integrations/dingtalk/config')
  expect(config.enabled).toBe(false)

  await seedSchool(api)
  await uiLogin(page, 'admin', 'admin123')

  await page.goto('/timetable')
  await expect(page.getByRole('heading', { name: '课表', exact: true })).toBeVisible()

  await page.goto('/notifications')
  await expect(page.getByRole('heading', { name: '通知中心' })).toBeVisible()

  await page.goto('/admin/settings')
  await expect(page.getByText('钉钉集成')).toBeVisible()
})
