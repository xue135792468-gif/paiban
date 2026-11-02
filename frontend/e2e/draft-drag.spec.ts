import { expect, test, type Page } from '@playwright/test'
import {
  API,
  apiCall,
  apiLogin,
  draftDetail,
  dragCardToCell,
  gridCell,
  lessonCard,
  newContext,
  openDraft,
  positionOf,
  seedDraftDragScenario,
  selectClass,
  selectTeacher,
  waitForChanges,
  waitForDraftReload,
  type DraftDragScenario,
} from './helpers'

async function drag(page: Page, seed: DraftDragScenario, toPeriodId: number) {
  await dragCardToCell(page, lessonCard(page, seed.lessonAId), gridCell(page, toPeriodId))
}

test('拖到同班有课格一次交换，不打开候选抽屉，重载后保持，一次撤销恢复', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await drag(page, seed, seed.periodC)
  expect((await change).status()).toBe(200)
  await reload

  await expect(gridCell(page, seed.periodC)).toContainText(seed.courseAName)
  await expect(gridCell(page, seed.periodA)).toContainText(seed.courseCName)
  await expect(page.getByTestId('swap-candidate-panel')).toHaveCount(0)

  const undoReload = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: /^撤销/ }).click()
  await undoReload
  await expect(gridCell(page, seed.periodA)).toContainText(seed.courseAName)
  await expect(gridCell(page, seed.periodC)).toContainText(seed.courseCName)

  const secondChange = waitForChanges(page, seed.draftId)
  const secondReload = waitForDraftReload(page, seed.draftId)
  await drag(page, seed, seed.periodC)
  expect((await secondChange).status()).toBe(200)
  await secondReload

  await page.reload()
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
  await selectClass(page, seed.className)
  await expect(gridCell(page, seed.periodC)).toContainText(seed.courseAName)
  await expect(gridCell(page, seed.periodA)).toContainText(seed.courseCName)

  const detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonAId)).toBe(seed.periodC)
  expect(positionOf(detail, seed.lessonCId)).toBe(seed.periodA)
})

test('拖到空格直接移动，原位留空，重载后保持', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await drag(page, seed, seed.emptyPeriod)
  expect((await change).status()).toBe(200)
  await reload

  await expect(gridCell(page, seed.emptyPeriod)).toContainText(seed.courseAName)
  await expect(lessonCard(page, seed.lessonAId)).toHaveCount(1)
  expect(await gridCell(page, seed.periodA).innerText()).not.toContain(seed.courseAName)

  await page.reload()
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
  await selectClass(page, seed.className)
  await expect(gridCell(page, seed.emptyPeriod)).toContainText(seed.courseAName)

  const detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonAId)).toBe(seed.emptyPeriod)
})

test('拖回自身与跨班落点被明确拒绝且课表不变', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)
  const before = await draftDetail(api, seed.draftId)

  await dragCardToCell(page, lessonCard(page, seed.lessonAId), gridCell(page, seed.periodA))
  await expect(lessonCard(page, seed.lessonAId)).toBeVisible()

  await drag(page, seed, seed.crossPeriod)
  await expect(page.getByText('跨班落点只能通过换课入口调整教师')).toBeVisible()

  const after = await draftDetail(api, seed.draftId)
  expect(after.draft.version).toBe(before.draft.version)
  expect(positionOf(after, seed.lessonAId)).toBe(seed.periodA)
  expect(positionOf(after, seed.lessonCId)).toBe(seed.periodC)
})

test('同格多课不猜落点，提示先修复且不写入', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api, { ambiguous: true })
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)
  const before = await draftDetail(api, seed.draftId)

  await drag(page, seed, seed.ambiguousPeriod)
  await expect(page.getByText('该格有多节课，请先修复后再拖拽')).toBeVisible()

  const after = await draftDetail(api, seed.draftId)
  expect(after.draft.version).toBe(before.draft.version)
  expect(positionOf(after, seed.lessonAId)).toBe(seed.periodA)
})

test('直接交换失败自动列方案；取消不改课；应用整环后一次撤销整单还原', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  const search = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith(`/api/timetable-drafts/${seed.draftId}/cycle-plans/search`),
  )
  await drag(page, seed, seed.periodB)
  const searchResponse = await search
  expect(searchResponse.status()).toBe(200)
  expect((await searchResponse.json()).status).toBe('FOUND')
  await expect(page.getByTestId('cycle-plan-0')).toBeVisible()
  await expect(page.getByRole('button', { name: /帮我找/ })).toHaveCount(0)

  const scoped = page.locator('.el-dialog:visible')
  await scoped.getByRole('button', { name: '取消' }).click()
  const unchanged = await draftDetail(api, seed.draftId)
  expect(positionOf(unchanged, seed.lessonAId)).toBe(seed.periodA)
  expect(positionOf(unchanged, seed.lessonBId)).toBe(seed.periodB)

  await drag(page, seed, seed.periodB)
  await expect(page.getByTestId('cycle-plan-0')).toBeVisible()
  const apply = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith(`/api/timetable-drafts/${seed.draftId}/cycle-plans/apply`),
  )
  const reload = waitForDraftReload(page, seed.draftId)
  await page.locator('[data-testid="cycle-plan-0"] button').click()
  await page.getByRole('button', { name: '确定' }).click()
  expect((await apply).status()).toBe(200)
  await reload
  await expect(gridCell(page, seed.periodB)).toContainText(seed.courseAName)

  const applied = await draftDetail(api, seed.draftId)
  const movedPeriods = [
    positionOf(applied, seed.lessonAId),
    positionOf(applied, seed.lessonBId),
    positionOf(applied, seed.lessonCId),
  ].sort()
  expect(movedPeriods).toEqual([seed.periodA, seed.periodB, seed.periodC].sort())

  const undoReload = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: /^撤销/ }).click()
  await undoReload

  const restored = await draftDetail(api, seed.draftId)
  expect(positionOf(restored, seed.lessonAId)).toBe(seed.periodA)
  expect(positionOf(restored, seed.lessonBId)).toBe(seed.periodB)
  expect(positionOf(restored, seed.lessonCId)).toBe(seed.periodC)
})

test('应用方案后加锁再撤销，提示先解锁且不误报成功', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  await drag(page, seed, seed.periodB)
  await expect(page.getByTestId('cycle-plan-0')).toBeVisible()
  const apply = page.waitForResponse((response) =>
    response.url().endsWith(`/api/timetable-drafts/${seed.draftId}/cycle-plans/apply`),
  )
  const appliedReload = waitForDraftReload(page, seed.draftId)
  await page.locator('[data-testid="cycle-plan-0"] button').click()
  await page.getByRole('button', { name: '确定' }).click()
  await apply
  await appliedReload

  const token = await apiLogin(api)
  const afterApply = await draftDetail(api, seed.draftId)
  const lockResult = await api.put(
    `${API}/timetable-drafts/${seed.draftId}/lessons/locks`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: { lessonIds: [seed.lessonAId], locked: true, expectedVersion: afterApply.draft.version },
    },
  )
  expect(lockResult.status()).toBe(200)

  const refresh = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: '刷新' }).click()
  await refresh

  const undoResponse = waitForChanges(page, seed.draftId)
  await page.getByRole('button', { name: /^撤销/ }).click()
  const undo = await undoResponse
  expect(undo.status()).toBe(409)
  expect((await undo.json()).message).toContain('请先解锁')
  await expect(page.getByText(/请先解锁/)).toBeVisible()

  const stillApplied = await draftDetail(api, seed.draftId)
  expect(positionOf(stillApplied, seed.lessonAId)).toBe(seed.periodB)
})

test('教师视角拖拽与点击/键盘调整位置都能完成同一次交换', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherAName)

  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await drag(page, seed, seed.periodC)
  expect((await change).status()).toBe(200)
  await reload

  let detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonAId)).toBe(seed.periodC)
  expect(positionOf(detail, seed.lessonCId)).toBe(seed.periodA)

  const undoReload = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: /^撤销/ }).click()
  await undoReload

  await lessonCard(page, seed.lessonAId).focus()
  await page.keyboard.press('Enter')
  await expect(page.locator('.controls .el-tag')).toContainText(seed.courseAName)
  await page.getByRole('button', { name: '调整位置' }).click()
  await expect(page.locator('.period-chip').first()).toBeVisible()

  const keyboardChange = waitForChanges(page, seed.draftId)
  const keyboardReload = waitForDraftReload(page, seed.draftId)
  await page.locator(`.period-chip[data-period-id="${seed.periodC}"]`).click()
  expect((await keyboardChange).status()).toBe(200)
  await keyboardReload

  detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonAId)).toBe(seed.periodC)
  expect(positionOf(detail, seed.lessonCId)).toBe(seed.periodA)
})

test('页面过期时拖拽返回可理解提示并刷新权威状态', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  const token = await apiLogin(api)
  await apiCall(api, token, 'patch', `/timetable-drafts/${seed.draftId}/lessons/${seed.lessonCId}`, {
    periodId: seed.emptyPeriod,
    force: true,
  })

  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await drag(page, seed, seed.periodC)
  const response = await change
  expect(response.status()).toBe(409)
  expect((await response.json()).message).toContain('请刷新')
  await reload
  await expect(page.getByText('课表已被其他人修改，请刷新后重试')).toBeVisible()

  await expect(gridCell(page, seed.emptyPeriod)).toContainText(seed.courseCName)
  await expect(gridCell(page, seed.periodA)).toContainText(seed.courseAName)
})
