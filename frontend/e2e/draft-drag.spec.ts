import { expect, test, type Page, type Response } from '@playwright/test'
import { capture } from './screenshots'
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

/**
 * Drops A on B and waits for the automatic plan panel. A full-suite run can lose the
 * first pointer sequence under load; retrying the no-write search is safe because a
 * direct swap adds a conflict and neither attempt changes the timetable.
 */
async function openPlanPanel(page: Page, seed: DraftDragScenario): Promise<void> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const search = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith(`/api/timetable-drafts/${seed.draftId}/cycle-plans/search`),
    )
    await drag(page, seed, seed.periodB)
    const response = await search
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.status).toBe('FOUND')
    expect(body.plans.length).toBeGreaterThan(0)
    try {
      await page.getByTestId('cycle-plan-0').waitFor({ state: 'visible', timeout: 5_000 })
      return
    } catch {
      // The panel did not render in time; retry the no-write search once.
    }
  }
  await expect(page.getByTestId('cycle-plan-0')).toBeVisible()
}

test('同班交换不受同一时间其他班课程影响（真实课表常见）', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api, { overlapTarget: true })
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  const consoleErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })

  let changeResponse: Response | null = null
  try {
    const change = page
      .waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().endsWith(`/api/timetable-drafts/${seed.draftId}/changes`),
        { timeout: 8_000 },
      )
      .catch(() => null)
    await drag(page, seed, seed.periodB)
    changeResponse = await change
    expect(changeResponse?.status()).toBe(200)
    await expect(gridCell(page, seed.periodB)).toContainText(seed.courseAName)
    await expect(gridCell(page, seed.periodA)).toContainText(seed.courseBName)
  } finally {
    await capture(page, 'draft-drag-overlap')
  }
  expect(consoleErrors).toEqual([])
})

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

test('拖回自身不写入；班级视角可移到别班占用的时间；教师视角跨班被拒', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  await dragCardToCell(page, lessonCard(page, seed.lessonAId), gridCell(page, seed.periodA))
  await expect(lessonCard(page, seed.lessonAId)).toBeVisible()

  // The target slot has another class's lesson, but it is invisible in class view.
  // Moving there is a normal move, not a cross-class exchange.
  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await drag(page, seed, seed.crossPeriod)
  expect((await change).status()).toBe(200)
  await reload
  const moved = await draftDetail(api, seed.draftId)
  expect(positionOf(moved, seed.lessonAId)).toBe(seed.crossPeriod)

  const undoReload = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: /^撤销/ }).click()
  await undoReload
  expect(positionOf(await draftDetail(api, seed.draftId), seed.lessonAId)).toBe(seed.periodA)

  // In teacher view the target card belongs to another class, so the explicit
  // cross-class entry is required and nothing may be written.
  await selectTeacher(page, seed.teacherBName)
  const beforeCross = await draftDetail(api, seed.draftId)
  await dragCardToCell(page, lessonCard(page, seed.lessonBId), gridCell(page, seed.periodA))
  await expect(page.getByText('跨班落点只能通过换课入口调整教师')).toBeVisible()
  const afterCross = await draftDetail(api, seed.draftId)
  expect(afterCross.draft.version).toBe(beforeCross.draft.version)
  expect(positionOf(afterCross, seed.lessonBId)).toBe(seed.periodB)
  expect(positionOf(afterCross, seed.lessonCId)).toBe(seed.periodC)
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

  await openPlanPanel(page, seed)
  await expect(page.getByRole('button', { name: /帮我找/ })).toHaveCount(0)

  const scoped = page.locator('.el-dialog:visible')
  await scoped.getByRole('button', { name: '取消' }).click()
  await expect(page.getByTestId('cycle-plan-0')).toHaveCount(0)
  await expect(page.locator('.el-dialog:visible')).toHaveCount(0)
  const unchanged = await draftDetail(api, seed.draftId)
  expect(positionOf(unchanged, seed.lessonAId)).toBe(seed.periodA)
  expect(positionOf(unchanged, seed.lessonBId)).toBe(seed.periodB)

  await openPlanPanel(page, seed)
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

  await openPlanPanel(page, seed)
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

test('教师视角拖拽与点击/键盘选择位置都能完成同一次移动', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherAName)

  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await drag(page, seed, seed.emptyPeriod)
  expect((await change).status()).toBe(200)
  await reload

  let detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonAId)).toBe(seed.emptyPeriod)

  const undoReload = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: /^撤销/ }).click()
  await undoReload

  await lessonCard(page, seed.lessonAId).focus()
  await page.keyboard.press('Enter')
  await expect(page.locator('.controls .el-tag')).toContainText(seed.courseAName)
  await page.getByRole('button', { name: '选择位置' }).click()
  await expect(page.locator('.period-chip').first()).toBeVisible()

  const keyboardChange = waitForChanges(page, seed.draftId)
  const keyboardReload = waitForDraftReload(page, seed.draftId)
  await page.locator(`.period-chip[data-period-id="${seed.emptyPeriod}"]`).click()
  expect((await keyboardChange).status()).toBe(200)
  await keyboardReload

  detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonAId)).toBe(seed.emptyPeriod)
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
