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
 * 撤销之后 UI 需要重新落定：全量套件跑到后段时偶发「第二次拖拽丢了第一次 pointer
 * 序列」，零写入的请求可以安全重试一次（照 openPlanPanel 的先例）。
 * 第二轮重拖时若第一轮其实已经换过，落点就是这张卡自己的格，前端不发写请求，
 * 因此重试不会造成重复交换。
 */
async function dragAfterUndo(page: Page, seed: DraftDragScenario, toPeriodId: number) {
  // 先等撤销后的网格状态收敛（课卡回到原位），再发起拖拽。
  await expect(async () => {
    expect(await gridCell(page, seed.periodA).innerText()).toContain(seed.courseAName)
  }).toPass({ timeout: 20_000 })
  for (let attempt = 0; attempt < 2; attempt += 1) {
    // 两个等待都挂了 catch：本轮若没等到响应，留下的 pending promise 不能变成
    // unhandled rejection（waitForResponse 在未配置 actionTimeout 时不会自己超时）。
    const change = waitForChanges(page, seed.draftId).catch(() => null)
    const reload = waitForDraftReload(page, seed.draftId).catch(() => null)
    await drag(page, seed, toPeriodId)
    // 用短的 race 判定这次拖拽有没有真的发出写请求；没有就重试一次。
    const posted = await Promise.race([change, page.waitForTimeout(8_000).then(() => null)])
    if (posted) {
      expect(posted.status()).toBe(200)
      await reload
      return
    }
    // 没写入：不消费 change/reload，直接重试下一轮拖拽。
  }
  throw new Error('撤销后的拖拽连续两次都没有发出写请求')
}

/** 数本页发出的写请求，用于「拖到别处取消不应落库」这类反向断言。 */
function trackWrites(page: Page, draftId: number) {
  const posts: string[] = []
  page.on('request', (request) => {
    if (request.method() !== 'POST') return
    const url = request.url()
    if (url.endsWith(`/api/timetable-drafts/${draftId}/changes`)) posts.push(url)
    if (url.includes(`/api/timetable-drafts/${draftId}/cycle-plans/search`)) posts.push(url)
  })
  return posts
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

  await dragAfterUndo(page, seed, seed.periodC)

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

test('拖回自身不写入；班级视角可移到别班占用的时间；教师视角拖动切同班课表后按班级视图规则换课', async ({
  page,
}) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  // periodA 上同时坐着 class2 的语文（同一位语文老师），直接互换会撞教师冲突、转去弹联动方案；
  // 先把 class2 的语文挪到 2-2（等价于 overlapTarget 的布置），让 periodA 只剩同班数学，
  // 第 4 段验的才是「同班互换本身」而不是冲突方案。
  const seedToken = await apiLogin(api)
  await apiCall(
    api,
    seedToken,
    'patch',
    `/timetable-drafts/${seed.draftId}/lessons/${seed.class2ChineseId}`,
    { periodId: seed.ambiguousPeriod, force: true },
  )
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

  // 票 17 之后教师视角的拖动语义变了：拖起一节课时网格临时切成该课所属班级的浅色课表
  // （同班其他老师的课可见），放下后与班级视图完全一致，只在同班内换（见新增用例守护跨班边界）。
  await selectTeacher(page, seed.teacherBName)
  await expect(lessonCard(page, seed.lessonAId)).toHaveCount(0)

  const before = await draftDetail(api, seed.draftId)
  await dragCardToCell(page, lessonCard(page, seed.lessonBId), gridCell(page, seed.periodA), {
    release: false,
  })

  // 拖起中：网格变成 class1 的浅色班级课表，同班其他老师的课都渲染出来。
  await expect(page.locator('.grid-card .grid')).toHaveClass(/drag-class-view/)
  await expect(lessonCard(page, seed.lessonAId)).toBeVisible()
  await expect(lessonCard(page, seed.lessonCId)).toBeVisible()
  expect(await page.locator('.grid-card .lesson').count()).toBeGreaterThanOrEqual(3)

  // 松手落到同班格（periodA 上是 class1 数学）→ 走班级视图同一套同班互换。
  const swapChange = waitForChanges(page, seed.draftId)
  const swapReload = waitForDraftReload(page, seed.draftId)
  await page.mouse.up()
  expect((await swapChange).status()).toBe(200)
  await swapReload

  const after = await draftDetail(api, seed.draftId)
  expect(positionOf(after, seed.lessonBId)).toBe(seed.periodA)
  expect(positionOf(after, seed.lessonAId)).toBe(seed.periodB)
  expect(after.draft.version).toBeGreaterThan(before.draft.version)

  // 放下后回到原教师课表：浅色班级课表消失，同班其他老师的课重新隐藏，所选教师不变。
  await expect(page.locator('.grid-card .grid')).not.toHaveClass(/drag-class-view/)
  await expect(lessonCard(page, seed.lessonAId)).toHaveCount(0)
  await expect(page.locator('.controls .el-select')).toContainText(seed.teacherBName)
  await expect(lessonCard(page, seed.lessonBId)).toBeVisible()
})

test('教师视角拖动仍不允许跨班换人：拖起只显示源课所属班级，拖出取消零写入', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherBName)

  const before = await draftDetail(api, seed.draftId)
  const writes = trackWrites(page, seed.draftId)

  // 拖起 class1 的语文课：网格切成 class1 的浅色课表，class2 的课（哪怕是同一位语文老师的）
  // 根本不渲染，所以「拖到别班的课上去换人」这个落点不存在——票 17 的「只在同班内换」。
  await dragCardToCell(page, lessonCard(page, seed.lessonBId), gridCell(page, seed.periodA), {
    release: false,
  })
  await expect(page.locator('.grid-card .grid')).toHaveClass(/drag-class-view/)
  await expect(lessonCard(page, seed.lessonAId)).toBeVisible()
  await expect(lessonCard(page, seed.class2ChineseId)).toHaveCount(0)
  await expect(lessonCard(page, seed.class2EnglishId)).toHaveCount(0)

  // 移到表头松手等于取消：不发写请求，课表不变，视图回到原教师课表。
  const cornerBox = await page.locator('.grid-card .corner').boundingBox()
  expect(cornerBox).toBeTruthy()
  await page.mouse.move(cornerBox!.x + cornerBox!.width / 2, cornerBox!.y + cornerBox!.height / 2, {
    steps: 5,
  })
  await page.mouse.up()

  await expect(page.locator('.grid-card .grid')).not.toHaveClass(/drag-class-view/)
  await expect(lessonCard(page, seed.lessonAId)).toHaveCount(0)
  await expect(page.locator('.controls .el-select')).toContainText(seed.teacherBName)
  await expect(lessonCard(page, seed.lessonBId)).toBeVisible()
  // 松手到断言之间留一个渲染节拍，确认没有迟到的写请求。
  await page.waitForTimeout(500)
  expect(writes).toEqual([])

  const after = await draftDetail(api, seed.draftId)
  expect(after.draft.version).toBe(before.draft.version)
  expect(positionOf(after, seed.lessonBId)).toBe(seed.periodB)
  expect(positionOf(after, seed.lessonAId)).toBe(seed.periodA)
  expect(positionOf(after, seed.class2ChineseId)).toBe(seed.periodA)
  expect(positionOf(after, seed.class2EnglishId)).toBe(seed.crossPeriod)
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
  const seed = await seedDraftDragScenario(api, { blockedOverlapTarget: true })
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

test('拖拽、点击与键盘三种入口都能完成同一次移动', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)

  // 教师视角只做拖拽：票 13 把「点课」改成了同班换课模式（绿/灰格），
  // 那里不再有「已选课程」标签和移动目标选择；换课行为见 teacher-view-swap.spec.ts。
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
  expect(positionOf(await draftDetail(api, seed.draftId), seed.lessonAId)).toBe(seed.periodA)

  // 点击与键盘选择位置在班级视角完成——入口变了，移动本身仍是同一次。
  await page.getByText('按行政班', { exact: true }).click()
  await selectClass(page, seed.className)
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
