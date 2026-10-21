import { expect, test, type ElementHandle, type Page } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool, uiLogin } from './helpers'

async function solveDraft(api: Awaited<ReturnType<typeof newContext>>, semesterId: number): Promise<number> {
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
  await expect(page.getByText('冲突原因')).toBeVisible()
  await page.getByRole('button', { name: '关闭' }).click()

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
  await expect(page.getByText(/课表已发布/).first()).toBeVisible({ timeout: 30_000 })

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
