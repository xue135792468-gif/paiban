import { expect, test } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  classEntries,
  dateFor,
  newContext,
  seedSchool,
  solveAndPublish,
  uiLogin,
} from './helpers'

/**
 * 票 14：撤回只撤已发布基线。撤回前提示未来调代数；撤回后草稿可编辑并重新发布，
 * 调代行保留在历史里（entry_id 被置空、未来行变已取消）。
 */

test('发布 → 撤回（提示未来调代）→ 编辑 → 重新发布，历史仍在', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  const draftId = await solveAndPublish(api, seed)
  const token = await apiLogin(api)

  // 先造一条未来日期的生效调代，撤回确认框必须点名它。
  // 调代现在直接生效：教师指定代课人提交后立刻是 EFFECTIVE。
  const entries = await classEntries(api, seed.classId)
  const entry = entries.find((item) => item.dayOfWeek != null && item.id != null)
  expect(entry).toBeTruthy()
  const effectiveDate = dateFor(entry!.dayOfWeek)
  const teacherToken = await apiLogin(api, seed.username, seed.password)
  const request = await apiCall(api, teacherToken, 'post', '/substitution-requests', {
    entryId: entry!.id,
    effectiveDate,
    reason: '撤回 e2e：临时有事',
    expectedSubstituteTeacherId: seed.substituteTeacherId,
  })
  expect(request.status).toBe('EFFECTIVE')

  await uiLogin(page, 'admin', 'admin123')
  await page.goto(`/workbench/drafts/${draftId}`)
  await expect(page.locator('.el-tag', { hasText: '已发布' })).toBeVisible()
  await page.getByTestId('withdraw-draft').click()

  const box = page.locator('.el-message-box')
  await expect(box).toContainText('有 1 条未来日期的调代会失效')
  await expect(box).toContainText('记录保留在历史')
  await box.getByRole('button', { name: '撤回为草稿' }).click()

  await expect(page.locator('.el-tag', { hasText: '草稿' })).toBeVisible()
  await expect(page.getByRole('button', { name: '发布课表' })).toBeVisible()

  // 撤回后可编辑：通过编辑器的课节写入路径改一次（同一节，验证不再被只读拦截）。
  const detail = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
  const lesson = detail.lessons[0]
  await apiCall(api, token, 'patch', `/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
    periodId: lesson.periodId,
    force: true,
  })

  await page.reload()
  await expect(page.getByRole('button', { name: '发布课表' })).toBeEnabled()
  await page.getByRole('button', { name: '发布课表' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message').filter({ hasText: /课表已发布/ }).first())
    .toBeVisible({ timeout: 30_000 })

  // 重新发布后必须给出可执行的「重新处理调代」入口，而不只是一句提示。
  await expect(page.getByTestId('reprocess-substitutions'))
    .toContainText('有 1 条撤回时取消的调代需要重新处理')

  const info = await apiCall(api, token, 'get', '/timetable/publish-info')
  expect(info.published).toBe(true)
  expect(info.status).toBe('ACTIVE')

  // 历史行仍在：未来行被取消，entry_id 因基线重建而置空。
  const records = await apiCall(
    api,
    token,
    'get',
    `/substitutions?semesterId=${seed.semesterId}&status=ALL`,
  )
  const history = records.find((row: { requestId: number }) => row.requestId === request.id)
  expect(history).toBeTruthy()
  expect(history.status).toBe('CANCELLED')
  // JSON null may be omitted by the serializer; both shapes mean "entry gone".
  expect(history.entryId ?? null).toBeNull()
  expect(history.className).toBeTruthy()
})
