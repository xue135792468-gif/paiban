import { expect, test } from '@playwright/test'
import {
  API,
  apiLogin,
  draftDetail,
  dragCardToCell,
  gridCell,
  lessonCard,
  lockLessons,
  newContext,
  openDraft,
  openLesson,
  positionOf,
  seedDraftLockScenario,
  selectClass,
  selectTeacher,
  uiLogin,
  waitForDraftReload,
  waitForLocks,
} from './helpers'

test('单节锁定后出现锁标记，刷新仍在；解锁后标记消失', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  await openLesson(page, seed.mathLessonIds[0])
  await expect(page.locator('.controls .el-tag')).toContainText(seed.mathCourseName)

  const put = waitForLocks(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await page.getByTestId('lock-single').click()
  expect((await put).status()).toBe(200)
  await reload
  await expect(lessonCard(page, seed.mathLessonIds[0])).toHaveAttribute('data-locked', 'true')
  await expect(lessonCard(page, seed.mathLessonIds[0])).toContainText('锁')

  await page.reload()
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
  await selectClass(page, seed.className)
  await expect(lessonCard(page, seed.mathLessonIds[0])).toHaveAttribute('data-locked', 'true')

  await openLesson(page, seed.mathLessonIds[0])
  const unlock = waitForLocks(page, seed.draftId)
  const unlockReload = waitForDraftReload(page, seed.draftId)
  await page.getByTestId('unlock-single').click()
  expect((await unlock).status()).toBe(200)
  await unlockReload
  await expect(lessonCard(page, seed.mathLessonIds[0])).not.toHaveAttribute('data-locked', 'true')
})

test('同一门课一周多节，只锁被选中的那一节', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  await openLesson(page, seed.mathLessonIds[1])
  const put = waitForLocks(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await page.getByTestId('lock-single').click()
  expect((await put).status()).toBe(200)
  await reload

  await expect(lessonCard(page, seed.mathLessonIds[1])).toHaveAttribute('data-locked', 'true')
  await expect(lessonCard(page, seed.mathLessonIds[0])).not.toHaveAttribute('data-locked', 'true')
  await expect(lessonCard(page, seed.mathLessonIds[2])).not.toHaveAttribute('data-locked', 'true')

  const detail = await draftDetail(api, seed.draftId)
  const locked = detail.lessons.filter((lesson) => lesson.locked).map((lesson) => lesson.id)
  expect(locked).toEqual([seed.mathLessonIds[1]])
})

test('多选两节一起锁定，确认框写明范围与数量，多选模式不触发拖拽', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  await page.getByTestId('toggle-selection').click()
  await expect(page.getByTestId('selected-count')).toContainText('已选 0 节')
  await lessonCard(page, seed.mathLessonIds[0]).click()
  await lessonCard(page, seed.chineseLessonId).click()
  await expect(page.getByTestId('selected-count')).toContainText('已选 2 节')

  const put = waitForLocks(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await page.getByRole('button', { name: '锁定所选（2）' }).click()
  const box = page.locator('.el-message-box:visible')
  await expect(box).toContainText(seed.className)
  await expect(box).toContainText('所选课节')
  await expect(box).toContainText('2 节')
  await expect(box).toContainText('锁定')
  await box.getByRole('button', { name: '确定' }).click()
  expect((await put).status()).toBe(200)
  await reload

  await expect(lessonCard(page, seed.mathLessonIds[0])).toHaveAttribute('data-locked', 'true')
  await expect(lessonCard(page, seed.chineseLessonId)).toHaveAttribute('data-locked', 'true')
  await expect(lessonCard(page, seed.mathLessonIds[1])).not.toHaveAttribute('data-locked', 'true')

  const before = await draftDetail(api, seed.draftId)
  let changesRequested = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/changes')) {
      changesRequested += 1
    }
  })
  // 多选模式下拖拽是空操作：交互零副作用，整体重试（CI 下 pointerup 偶发丢失），
  // 以 API 里的位置与版本不变为准，不只依赖 UI 文案。
  const chinesePeriod = positionOf(before, seed.chineseLessonId)
  await expect(async () => {
    await dragCardToCell(
      page,
      lessonCard(page, seed.chineseLessonId),
      gridCell(page, seed.emptyPeriod),
    )
    await page.waitForTimeout(300)
    const after = await draftDetail(api, seed.draftId)
    expect(positionOf(after, seed.chineseLessonId)).toBe(chinesePeriod)
    expect(after.draft.version).toBe(before.draft.version)
  }).toPass({ timeout: 20_000 })
  expect(changesRequested).toBe(0)
})

test('按天与全班批量锁定数量准确；教师视角的范围项禁用', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  await page.getByTestId('toggle-selection').click()
  await page.getByTestId('scope-menu').click()
  await page
    .locator('.el-dropdown-menu:visible .el-dropdown-menu__item')
    .filter({ hasText: '锁定周一' })
    .click()

  const dayBox = page.locator('.el-message-box:visible')
  await expect(dayBox).toContainText(seed.className)
  await expect(dayBox).toContainText('周一')
  await expect(dayBox).toContainText('4 节')
  await expect(dayBox).toContainText('锁定')
  const dayPut = waitForLocks(page, seed.draftId)
  const dayReload = waitForDraftReload(page, seed.draftId)
  await dayBox.getByRole('button', { name: '确定' }).click()
  expect((await dayPut).status()).toBe(200)
  await dayReload
  await expect(page.locator('.grid-card .lesson[data-locked="true"]')).toHaveCount(4)

  await page.getByTestId('scope-menu').click()
  await page
    .locator('.el-dropdown-menu:visible .el-dropdown-menu__item')
    .filter({ hasText: '锁定全班' })
    .click()
  const classBox = page.locator('.el-message-box:visible')
  await expect(classBox).toContainText(seed.className)
  await expect(classBox).toContainText('全班')
  await expect(classBox).toContainText('5 节')
  const classPut = waitForLocks(page, seed.draftId)
  const classReload = waitForDraftReload(page, seed.draftId)
  await classBox.getByRole('button', { name: '确定' }).click()
  expect((await classPut).status()).toBe(200)
  await classReload
  await expect(page.locator('.grid-card .lesson[data-locked="true"]')).toHaveCount(5)

  await selectTeacher(page, seed.mathTeacherName)
  await page.getByTestId('scope-menu').click()
  await expect(
    page.getByText('教师视角只能按所选锁定，请切换到「按行政班」'),
  ).toBeVisible()
  await expect(
    page.locator('.el-dropdown-menu:visible .el-dropdown-menu__item').filter({ hasText: '锁定周一' }),
  ).toHaveCount(0)
  await expect(page.getByText('按天/全班需切到「按行政班」')).toBeVisible()
})

test('混合无效批次整批拒绝，不出现部分锁定', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  const token = await apiLogin(api)
  const before = await draftDetail(api, seed.draftId)
  const response = await api.put(`${API}/timetable-drafts/${seed.draftId}/lessons/locks`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      lessonIds: [seed.mathLessonIds[0], 999999999],
      locked: true,
      expectedVersion: before.draft.version,
    },
  })
  expect(response.status()).toBe(400)
  expect((await response.json()).message).toContain('非法课节 id')

  const after = await draftDetail(api, seed.draftId)
  expect(after.lessons.some((lesson) => lesson.locked)).toBe(false)
  expect(after.draft.version).toBe(before.draft.version)
  await expect(lessonCard(page, seed.mathLessonIds[0])).not.toHaveAttribute('data-locked', 'true')
})

test('锁定课不能拖拽、换课或调整位置，提示先解锁且不写入', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await lockLessons(api, seed, [seed.mathLessonIds[0]], true)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)
  await expect(lessonCard(page, seed.mathLessonIds[0])).toHaveAttribute('data-locked', 'true')
  const before = await draftDetail(api, seed.draftId)
  const lockedPeriod = positionOf(before, seed.mathLessonIds[0])

  // 锁定课拖拽是空操作：整体重试（pointerup 偶发丢失），以提示 + API 位置/版本
  // 不变为准，不只依赖 toast。
  await expect(async () => {
    await dragCardToCell(
      page,
      lessonCard(page, seed.mathLessonIds[0]),
      gridCell(page, seed.emptyPeriod),
    )
    await expect(page.getByText('该课节已锁定，请先解锁后再移动').first()).toBeVisible()
    const after = await draftDetail(api, seed.draftId)
    expect(positionOf(after, seed.mathLessonIds[0])).toBe(lockedPeriod)
    expect(after.draft.version).toBe(before.draft.version)
  }).toPass({ timeout: 20_000 })

  await openLesson(page, seed.mathLessonIds[0])
  await page.getByRole('button', { name: '跨班换人', exact: true }).click()
  await expect(page.getByText('该课节已锁定，先解锁后再换课')).toBeVisible()
  await expect(page.getByTestId('swap-candidate-panel')).toHaveCount(0)

  await page.getByRole('button', { name: '选择位置' }).click()
  await expect(page.getByText('该课节已锁定，先解锁后再选择位置')).toBeVisible()
  await expect(page.locator('.period-chip')).toHaveCount(0)

  const after = await draftDetail(api, seed.draftId)
  expect(after.draft.version).toBe(before.draft.version)
})

test('键盘可完成选择、上锁与解锁，刷新后仍保留', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)

  await page.getByTestId('toggle-selection').click()
  const card = lessonCard(page, seed.mathLessonIds[0])
  await card.focus()
  await page.keyboard.press('Space')
  await expect(page.getByTestId('selected-count')).toContainText('已选 1 节')

  await page.getByRole('button', { name: '锁定所选（1）' }).focus()
  await page.keyboard.press('Enter')
  const box = page.locator('.el-message-box:visible')
  await expect(box).toContainText('1 节')
  const put = waitForLocks(page, seed.draftId)
  const reload = waitForDraftReload(page, seed.draftId)
  await box.getByRole('button', { name: '确定' }).focus()
  await page.keyboard.press('Enter')
  expect((await put).status()).toBe(200)
  await reload
  await expect(lessonCard(page, seed.mathLessonIds[0])).toHaveAttribute('data-locked', 'true')

  await page.reload()
  await expect(page.locator('.grid-card .cell').first()).toBeVisible()
  await selectClass(page, seed.className)
  await expect(lessonCard(page, seed.mathLessonIds[0])).toHaveAttribute('data-locked', 'true')

  await page.getByTestId('toggle-selection').click()
  const reloadedCard = lessonCard(page, seed.mathLessonIds[0])
  await reloadedCard.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('selected-count')).toContainText('已选 1 节')
  await page.getByRole('button', { name: '解锁所选（1）' }).focus()
  await page.keyboard.press('Enter')
  const unlockBox = page.locator('.el-message-box:visible')
  const unlock = waitForLocks(page, seed.draftId)
  const unlockReload = waitForDraftReload(page, seed.draftId)
  await unlockBox.getByRole('button', { name: '确定' }).focus()
  await page.keyboard.press('Enter')
  expect((await unlock).status()).toBe(200)
  await unlockReload
  await expect(lessonCard(page, seed.mathLessonIds[0])).not.toHaveAttribute('data-locked', 'true')
})

test('含锁定课节的草稿删除要求确认，确认后锁定随草稿一起移除', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await lockLessons(api, seed, [seed.mathLessonIds[0]], true)
  await openDraft(page, seed.draftId)
  await selectClass(page, seed.className)
  await expect(lessonCard(page, seed.mathLessonIds[0])).toHaveAttribute('data-locked', 'true')

  await page.getByRole('button', { name: '删除', exact: true }).click()
  const box = page.locator('.el-message-box:visible')
  await expect(box).toContainText('含 1 节锁定课节')
  await expect(box).toContainText('一并移除')
  // 票 04：草稿是软删除，删除后可在「最近删除」恢复 7 天（不再是「不可恢复」）。
  await expect(box).toContainText('最近删除')
  await expect(box).toContainText('7 天')

  await box.getByRole('button', { name: '取消' }).click()
  await expect(page.locator('.el-message-box:visible')).toHaveCount(0)
  const stillThere = await draftDetail(api, seed.draftId)
  expect(stillThere.draft.id).toBe(seed.draftId)
  expect(stillThere.lessons.find((lesson) => lesson.id === seed.mathLessonIds[0])?.locked).toBe(true)

  await page.getByRole('button', { name: '删除', exact: true }).click()
  const confirmBox = page.locator('.el-message-box:visible')
  await confirmBox.getByRole('button', { name: '解锁并删除' }).click()
  await expect(page).toHaveURL(/\/workbench$/)

  const token = await apiLogin(api)
  const gone = await api.get(`${API}/timetable-drafts/${seed.draftId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(gone.status()).toBe(404)
})

test('排课列表页删除含锁草稿也走确认，取消保留、确认后一并移除', async ({ page }) => {
  const api = await newContext()
  const seed = await seedDraftLockScenario(api)
  await lockLessons(api, seed, [seed.mathLessonIds[0]], true)
  const draftName = (await draftDetail(api, seed.draftId)).draft.name ?? ''
  expect(draftName).not.toBe('')

  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/workbench')
  const row = page
    .locator('.el-table__row')
    .filter({ hasText: draftName })
    .filter({ has: page.getByRole('button', { name: '删除', exact: true }) })
  await expect(row).toBeVisible()

  await row.getByRole('button', { name: '删除', exact: true }).click()
  const box = page.locator('.el-message-box:visible')
  await expect(box).toContainText('含 1 节锁定课节')
  await expect(box).toContainText('一并移除')
  await box.getByRole('button', { name: '取消' }).click()
  await expect(page.locator('.el-message-box:visible')).toHaveCount(0)
  await expect(row).toBeVisible()

  await row.getByRole('button', { name: '删除', exact: true }).click()
  const confirmBox = page.locator('.el-message-box:visible')
  await confirmBox.getByRole('button', { name: '解锁并删除' }).click()
  await expect(row).toHaveCount(0)

  const token = await apiLogin(api)
  const gone = await api.get(`${API}/timetable-drafts/${seed.draftId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(gone.status()).toBe(404)
})
