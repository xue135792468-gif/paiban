import { expect, test } from '@playwright/test'
import { capture } from './screenshots'
import {
  draftDetail,
  gridCell,
  lessonCard,
  newContext,
  openDraft,
  positionOf,
  seedDraftDragScenario,
  selectClass,
  selectTeacher,
  waitForDraftReload,
} from './helpers'

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
