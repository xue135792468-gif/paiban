import { expect, test, type Page } from '@playwright/test'
import { capture } from './screenshots'
import {
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
} from './helpers'

/**
 * 放下/取消后回到教师视图有渲染节拍：CI 负载下偶发晚一拍，用 toPass 重试，不放宽断言。
 * `hiddenLessonId` 是同班其他老师的课，回教师视图后必须重新消失。
 */
async function expectBackToTeacherView(page: Page, hiddenLessonId: number) {
  await expect(async () => {
    await expect(page.locator('.grid-card .grid')).not.toHaveClass(/drag-class-view/, {
      timeout: 2000,
    })
    await expect(page.locator('.grid-card .cell.foreign-busy')).toHaveCount(0, { timeout: 2000 })
    await expect(lessonCard(page, hiddenLessonId)).toHaveCount(0, { timeout: 2000 })
  }).toPass({ timeout: 20_000 })
}

/** 数本页发出的写请求，用于「取消拖动不应落库」这类反向断言。 */
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
 * 教师视图换课：点一节课进入同班换课模式，绿格可换、灰格不可换，
 * 点绿格预览并确认后两节课互换；全程只请求 INTRA_CLASS。
 *
 * 种子复用拖拽场景：class1 有数学/语文/英语，class2 的语文由同一位语文老师任教，
 * 因此教师视图里能看到一节"不同班"的课；class2 语文与 class1 数学同处周一第 1 节，
 * 使 class1 数学成为"对方有课"的灰格。
 */
test('教师视图点课出现绿/灰格，点绿格预览确认后完成同班换课', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)

  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherBName)

  // 教师视图默认只显示所选教师的课，且卡片带班级徽标。
  const sourceCard = lessonCard(page, seed.lessonBId)
  await expect(sourceCard).toBeVisible()
  await expect(sourceCard).toContainText(seed.className)
  await expect(sourceCard).toContainText(seed.teacherBName)
  await expect(lessonCard(page, seed.lessonAId)).toHaveCount(0)

  const candidatesUrl = (lessonId: number) =>
    `/api/timetable-drafts/${seed.draftId}/lessons/${lessonId}/swap-candidates`

  const candidates = page.waitForResponse(
    (response) => response.request().method() === 'GET' && response.url().includes(candidatesUrl(seed.lessonBId)),
  )
  await sourceCard.click()
  const candidateResponse = await candidates
  expect(candidateResponse.status()).toBe(200)
  const params = new URL(candidateResponse.url()).searchParams
  expect(params.get('type')).toBe('INTRA_CLASS')
  expect(params.get('includeUnavailable')).toBe('true')

  // 换课模式把源课节所属班级的课并进网格：绿=可换，灰=不可换。
  await expect(lessonCard(page, seed.lessonAId)).toBeVisible()
  await expect(gridCell(page, seed.periodC)).toHaveClass(/swap-swappable/)
  await expect(gridCell(page, seed.periodA)).toHaveClass(/swap-blocked/)
  await expect(gridCell(page, seed.periodA)).toHaveAttribute('title', /此时在/)
  await expect(lessonCard(page, seed.class2ChineseId)).toHaveClass(/swap-foreign/)
  await expect(lessonCard(page, seed.class2ChineseId)).toHaveAttribute(
    'title',
    '不同班，只能同班换课',
  )
  await capture(page, 'teacher-view-swap-targets')

  // Esc 退出，再点回来重新加载候选。
  await page.keyboard.press('Escape')
  await expect(page.locator('.grid-card .cell.swap-swappable')).toHaveCount(0)
  await expect(lessonCard(page, seed.lessonAId)).toHaveCount(0)

  const reCandidates = page.waitForResponse(
    (response) => response.request().method() === 'GET' && response.url().includes(candidatesUrl(seed.lessonBId)),
  )
  await lessonCard(page, seed.lessonBId).click()
  expect((await reCandidates).status()).toBe(200)
  await expect(gridCell(page, seed.periodC)).toHaveClass(/swap-swappable/)

  // 点绿格 -> 预览两张小课表 -> 确认。
  await gridCell(page, seed.periodC).click()
  const preview = page.getByTestId('dual-timetable-preview')
  await expect(preview).toBeVisible()

  const swap = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith(`/api/timetable-drafts/${seed.draftId}/swap`),
  )
  const reload = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: '确认换课' }).click()
  const swapResponse = await swap
  expect(swapResponse.status()).toBe(200)
  const swapBody = await swapResponse.json()
  expect(swapBody.saved).toBe(true)
  expect(swapBody.type).toBe('INTRA_CLASS')
  expect(JSON.parse(swapResponse.request().postData() ?? '{}')).toMatchObject({
    lessonId: seed.lessonBId,
    targetLessonId: seed.lessonCId,
  })
  await reload

  // 前端网格已互换，且换课模式随刷新退出；英语不是这位教师的课，留在班级视图核对。
  await expect(gridCell(page, seed.periodC)).toContainText(seed.courseBName)
  await expect(page.locator('.grid-card .cell.swap-swappable')).toHaveCount(0)

  // 接口回查与班级视图看到的结果一致。
  const detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonBId)).toBe(seed.periodC)
  expect(positionOf(detail, seed.lessonCId)).toBe(seed.periodB)

  await page.getByText('按行政班', { exact: true }).click()
  await selectClass(page, seed.className)
  await expect(gridCell(page, seed.periodC)).toContainText(seed.courseBName)
  await expect(gridCell(page, seed.periodB)).toContainText(seed.courseCName)
  await capture(page, 'teacher-view-swap-after')
})

/**
 * 票 17：教师视图拖起一节课时，网格临时切成该课所属班级的浅色课表，
 * 同班其他老师的课都看得见；该教师在别的班有课的节次标灰并写明原因。
 * 放到同班空节后，换课结果与班级视图一致，然后回到原教师课表。
 *
 * 种子同上一个用例：语文老师(class1 第 2 节)在 class2 第 1 节还有一节语文课，
 * 所以拖起时 class1 第 1 节就是「他班有课」的灰格，不需要另外补种子数据。
 */
test('教师视图拖起时切成该班浅色课表，放下后回到原教师课表', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherBName)

  const source = lessonCard(page, seed.lessonBId)
  const target = gridCell(page, seed.emptyPeriod)
  await expect(source).toBeVisible()
  // 拖起之前，教师课表里看不到同班其他老师的课。
  await expect(lessonCard(page, seed.lessonAId)).toHaveCount(0)

  // 真实鼠标拖拽（与 draft-drag.spec 同一条管线）：按住语文课卡，移到同班空节上方，先不松手。
  const busyReason = new RegExp(`${seed.teacherBName}在.+有课`)
  await dragCardToCell(page, source, target, { release: false })

  // 拖起后网格立刻变成该班的浅色课表：同班其他老师的课都渲染出来。
  await expect(page.locator('.grid-card .grid')).toHaveClass(/drag-class-view/)
  await expect(source).toBeVisible()
  await expect(lessonCard(page, seed.lessonAId)).toBeVisible()
  await expect(lessonCard(page, seed.lessonCId)).toBeVisible()

  // 该教师在 class2 第 1 节有课：标灰并写明原因（title / aria / 可见文字都可读）。
  const busyCell = gridCell(page, seed.periodA)
  await expect(busyCell).toHaveClass(/foreign-busy/)
  await expect(busyCell).toHaveAttribute('title', busyReason)
  await expect(busyCell).toHaveAttribute('aria-label', busyReason)
  await expect(busyCell).toContainText(busyReason)
  await capture(page, 'teacher-view-drag-class')

  // 松手：走班级视图同一套 drop，落库后回到原教师课表。
  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await page.mouse.up()
  expect((await change).status()).toBe(200)
  await reload

  await expectBackToTeacherView(page, seed.lessonAId)
  await expect(page.locator('.controls .el-select')).toContainText(seed.teacherBName)
  await expect(source).toBeVisible()

  const detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonBId)).toBe(seed.emptyPeriod)
})

/**
 * 票 17 验收：拖到同班另一节「已经有课」的格子上走的是换课分支，
 * 两节课互换位置，放下后回到原教师课表且所选教师不变。
 */
test('教师视图拖到同班另一节课上完成互换', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherBName)

  // lessonB = 语文(第 2 节)，periodC 上是英语，同一行政班 -> 走 swap 分支。
  const source = lessonCard(page, seed.lessonBId)
  const target = gridCell(page, seed.periodC)
  await expect(source).toBeVisible()
  await expect(lessonCard(page, seed.lessonCId)).toHaveCount(0)

  const change = waitForChanges(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await dragCardToCell(page, source, target)
  const response = await change
  expect(response.status()).toBe(200)
  expect((await response.json()).saved).toBe(true)
  const posted = JSON.parse(response.request().postData() ?? '{}')
  const changedIds = (posted.changes ?? []).map((item: { lessonId: number }) => item.lessonId)
  expect(changedIds.sort()).toEqual([seed.lessonBId, seed.lessonCId].sort())
  await reload

  await expectBackToTeacherView(page, seed.lessonAId)
  await expect(page.locator('.controls .el-select')).toContainText(seed.teacherBName)
  // 语文课换到第 3 节，仍然挂在原教师名下。
  await expect(gridCell(page, seed.periodC)).toContainText(seed.courseBName)

  const detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonBId)).toBe(seed.periodC)
  expect(positionOf(detail, seed.lessonCId)).toBe(seed.periodB)
})

/**
 * 票 17 验收：拖起后中途放弃（拖出网格松手）不落库，
 * 视图立刻回到原教师课表，所选教师不变。
 */
test('教师视图拖动中途放弃会回到原视图且不写库', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherBName)

  const writes = trackWrites(page, seed.draftId)
  const source = lessonCard(page, seed.lessonBId)
  await expect(source).toBeVisible()

  await dragCardToCell(page, source, page.locator('.grid-card .corner'), { release: false })
  await expect(page.locator('.grid-card .grid')).toHaveClass(/drag-class-view/)
  await expect(lessonCard(page, seed.lessonAId)).toBeVisible()

  // 在表头（不是任何一个可落点格子）松手：等于取消，不发任何写请求。
  await page.mouse.up()

  await expectBackToTeacherView(page, seed.lessonAId)
  await expect(page.locator('.controls .el-select')).toContainText(seed.teacherBName)
  await expect(source).toBeVisible()
  // 松手到断言之间留一个渲染节拍，确认没有迟到的写请求。
  await page.waitForTimeout(500)
  expect(writes).toEqual([])

  // 课表没有被改动。
  const detail = await draftDetail(api, seed.draftId)
  expect(positionOf(detail, seed.lessonBId)).toBe(seed.periodB)
})

/**
 * 票 17 验收：「他班有课」的灰格只是提醒，不是禁用。
 * 该格对应的本班格仍可放下（成功换课或弹出联动方案都算通过，只要前端没拦）。
 */
test('教师视图他班占用的灰格仍可作为落点', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftDragScenario(api)
  await openDraft(page, seed.draftId)
  await selectTeacher(page, seed.teacherBName)

  const source = lessonCard(page, seed.lessonBId)
  const busyCell = gridCell(page, seed.periodA)
  await expect(source).toBeVisible()

  await dragCardToCell(page, source, busyCell, { release: false })

  // 拖起后该格标灰并写明原因，但既不是 aria-disabled 也不是禁用光标。
  const busyReason = new RegExp(`${seed.teacherBName}在.+有课`)
  await expect(busyCell).toHaveClass(/foreign-busy/)
  await expect(busyCell).toContainText(busyReason)
  await expect(busyCell).not.toHaveAttribute('aria-disabled', 'true')
  const cursor = await busyCell.evaluate((node) => getComputedStyle(node).cursor)
  expect(cursor).not.toBe('not-allowed')

  // 放下：drop 必须真的发到服务端（直接换课或转去联动方案搜索），不能在前端被拒。
  const change = page
    .waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith(`/api/timetable-drafts/${seed.draftId}/changes`),
      { timeout: 20_000 },
    )
    .catch(() => null)
  const planSearch = page
    .waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/timetable-drafts/${seed.draftId}/cycle-plans/search`),
      { timeout: 20_000 },
    )
    .catch(() => null)
  await page.mouse.up()

  const accepted = await Promise.race([change, planSearch])
  expect(accepted, '他班占用格放下后必须走到服务端，不能被前端挡住').toBeTruthy()
  expect(accepted!.status()).toBe(200)

  // 不管服务端选哪条路，前端都要回到原教师课表。
  await expectBackToTeacherView(page, seed.lessonAId)
  await expect(page.locator('.controls .el-select')).toContainText(seed.teacherBName)
  await expect(source).toBeVisible()
})
