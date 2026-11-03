import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  pickOption,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
} from './helpers'

/**
 * 票 10：一张卡 2–3 句、例外句、影响预览、复制/停用/删除、按“对谁”分组与搜索。
 * 断言落在 `/constraints/state`（真实落库的 groupKey 与参数）和卡片的可见状态上。
 */
test.describe('排课规则：完整积木交互', () => {
  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed
  let classA: { id: number; name: string }
  let classB: { id: number; name: string }
  let classC: { id: number; name: string }

  test.beforeAll(async () => {
    api = await newContext()
    token = await apiLogin(api)
    seed = await seedConstraintScenario(api)
    const suffix = Math.random().toString(36).slice(2, 8)
    const grade = await apiCall(api, token, 'post', `/grades?semesterId=${seed.semesterId}`, {
      name: `九年级${suffix}`,
      sortOrder: 1,
    })
    const makeClass = async (name: string) => apiCall(
      api,
      token,
      'post',
      `/classes?semesterId=${seed.semesterId}`,
      { gradeId: grade.id, name: `${name}${suffix}`, studentCount: 45 },
    )
    classA = await makeClass('九（1）班')
    classB = await makeClass('九（2）班')
    classC = await makeClass('九（3）班')
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  async function state() {
    return apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId: seed.semesterId,
    })
  }

  async function resetRules() {
    const before = await state()
    await apiCall(
      api,
      token,
      'put',
      '/constraints',
      { configs: [] },
      { semesterId: seed.semesterId, baseVersion: before.version },
    )
  }

  function form(page: Page) {
    return page.getByTestId('rule-form')
  }

  async function saveRules(page: Page) {
    const saved = page.waitForResponse(
      (response) =>
        response.url().includes('/api/constraints') &&
        response.request().method() === 'PUT' &&
        response.status() === 200,
    )
    await page.getByTestId('rule-save').click()
    await saved
    await expect(page.getByTestId('rule-save')).toBeDisabled()
  }

  async function confirmDelete(page: Page) {
    const dialog = page.locator('.el-message-box')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('删除后不可恢复')
    await dialog.locator('.el-message-box__btns .el-button--primary').click()
    await expect(dialog).toBeHidden()
  }

  /** A multi-select stays open after choosing, so it cannot reuse pickOption. */
  async function pickException(page: Page, testId: string, name: string) {
    const select = form(page).getByTestId(testId)
    await select.click()
    const dropdown = page.locator('.el-select-dropdown:visible').last()
    await dropdown.getByText(name, { exact: true }).first().click()
    await page.keyboard.press('Escape')
    await expect(dropdown).toBeHidden()
  }

  test('一张卡两句：全校不排 + 除了九（2）班', async ({ page }) => {
    await resetRules()
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    await page.getByTestId('rule-new').click()
    const editor = form(page)
    await pickOption(editor, 'rule-day', '周一')
    await editor.getByTestId('rule-period-3').click()

    await editor.getByTestId('rule-add-sentence').click()
    await expect(editor.getByTestId('rule-sentence-block-1')).toBeVisible()
    await pickOption(editor, 'rule-day-1', '周二')
    await editor.getByTestId('rule-period-3-1').click()
    await pickException(page, 'rule-exceptions-1', classB.name)

    await expect(editor.getByTestId('rule-preview')).toContainText('周一第 3 节 不排')
    await expect(editor.getByTestId('rule-preview')).toContainText('除了')
    await editor.getByTestId('rule-submit').click()

    await expect(page.getByTestId('rule-card')).toHaveCount(1)
    await expect(page.getByTestId('rule-sentence-line-0')).toContainText('周一第 3 节')
    await expect(page.getByTestId('rule-sentence-line-1')).toContainText('除了')

    await saveRules(page)

    const saved = await state()
    const config = saved.configs.find(
      (item: { type: string }) => item.type === 'CLASS_UNAVAILABLE',
    )
    const entries = config.entries as Array<{
      params: Record<string, number>
      groupKey: string
    }>
    const groupKeys = Array.from(new Set(entries.map((entry) => entry.groupKey)))
    expect(groupKeys).toHaveLength(2)
    const firstKey = groupKeys.find((key) => key.endsWith(':0')) as string
    const secondKey = groupKeys.find((key) => key.endsWith(':1')) as string
    expect(firstKey).toMatch(/^pt-/)
    expect(secondKey?.startsWith(`${firstKey.split(':')[0]}:`)).toBe(true)

    const firstRows = entries.filter((entry) => entry.groupKey === firstKey)
    expect(firstRows).toHaveLength(1)
    expect(firstRows[0].params).toEqual({ dayOfWeek: 1, periodNo: 3 })

    const secondRows = entries.filter((entry) => entry.groupKey === secondKey)
    expect(secondRows).toHaveLength(2)
    expect(secondRows.map((entry) => entry.params.classId).sort((a, b) => a - b))
      .toEqual([classA.id, classC.id].sort((a, b) => a - b))
    expect(secondRows.every((entry) => entry.params.dayOfWeek === 2
      && entry.params.periodNo === 3)).toBe(true)

    await page.reload()
    await expect(page.getByTestId('rule-card')).toHaveCount(1)
    await expect(page.getByTestId('rule-sentence-1')).toContainText('除了')
  })

  test('影响预览、复制、停用、删除，并按对谁分组与搜索', async ({ page }) => {
    await resetRules()
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    const teacherName = seed.teachers[0].name
    await page.getByTestId('rule-new').click()
    const editor = form(page)
    await pickOption(editor, 'rule-type', '教师 的 课 不排')
    await pickOption(editor, 'rule-teacher', teacherName)
    await pickOption(editor, 'rule-day', '周五')
    await editor.getByTestId('rule-afternoon').click()
    await editor.getByTestId('rule-submit').click()

    await page.getByTestId('rule-new').click()
    await pickOption(form(page), 'rule-day', '周五')
    await form(page).getByTestId('rule-period-7').click()
    await form(page).getByTestId('rule-submit').click()

    await expect(page.getByTestId('rule-card')).toHaveCount(2)
    await expect(page.getByTestId('rule-group-TEACHER')).toBeVisible()
    await expect(page.getByTestId('rule-group-SCHOOL')).toBeVisible()

    // 搜索只按句子/名称过滤，分组标题仍在。
    const search = page.getByTestId('rule-search')
    await search.fill(teacherName)
    await expect(page.getByTestId('rule-card')).toHaveCount(1)
    await expect(page.getByTestId('rule-group-TEACHER')).toBeVisible()
    await expect(page.getByTestId('rule-group-SCHOOL')).toHaveCount(0)
    await search.fill('')
    await expect(page.getByTestId('rule-card')).toHaveCount(2)

    // 影响预览：教师规则至少报告这位教师和课节数。
    const teacherCard = page
      .getByTestId('rule-card')
      .filter({ hasText: `${teacherName} 的 课 在 周五下午 不排` })
      .first()
    await teacherCard.getByTestId('rule-impact').click()
    await expect(page.getByTestId('rule-impact-summary')).toBeVisible()
    await expect(page.getByTestId('rule-impact-counts')).toContainText('位教师')
    await page.getByTestId('rule-impact-close').click()
    await expect(page.getByTestId('rule-impact-summary')).toHaveCount(0)

    await teacherCard.getByTestId('rule-duplicate').click()
    await expect(page.getByTestId('rule-card')).toHaveCount(3)
    await saveRules(page)

    let saved = await state()
    let teacherEntries = saved.configs.find(
      (item: { type: string }) => item.type === 'TEACHER_UNAVAILABLE',
    ).entries as Array<{ enabled: boolean; groupKey: string }>
    expect(new Set(teacherEntries.map((entry) => entry.groupKey)).size).toBe(2)
    expect(teacherEntries).toHaveLength(8)

    // 停用原卡：只有这张卡的分组变成 enabled=false。
    const original = page
      .getByTestId('rule-card')
      .filter({ hasText: `${teacherName} 的 课 在 周五下午 不排` })
      .first()
    await original.getByTestId('rule-toggle').click()
    await expect(original.getByTestId('rule-disabled-tag')).toBeVisible()
    await saveRules(page)

    saved = await state()
    teacherEntries = saved.configs.find(
      (item: { type: string }) => item.type === 'TEACHER_UNAVAILABLE',
    ).entries as Array<{ enabled: boolean; groupKey: string }>
    const groups = Array.from(new Set(teacherEntries.map((entry) => entry.groupKey)))
    expect(groups).toHaveLength(2)
    expect(groups.filter((group) =>
      teacherEntries.filter((entry) => entry.groupKey === group)
        .every((entry) => entry.enabled === false))).toHaveLength(1)

    // 删除复制出来的卡：确认弹窗先展示影响，删除后另一张卡不受影响。
    const copies = page
      .getByTestId('rule-card')
      .filter({ hasText: `${teacherName} 的 课 在 周五下午 不排` })
    await copies.last().getByTestId('rule-delete').click()
    await confirmDelete(page)
    await expect(page.getByTestId('rule-card')).toHaveCount(2)
    await saveRules(page)

    saved = await state()
    teacherEntries = saved.configs.find(
      (item: { type: string }) => item.type === 'TEACHER_UNAVAILABLE',
    ).entries as Array<{ enabled: boolean; groupKey: string }>
    expect(new Set(teacherEntries.map((entry) => entry.groupKey)).size).toBe(1)
    expect(teacherEntries.every((entry) => entry.enabled === false)).toBe(true)
    expect(saved.configs.some(
      (item: { type: string }) => item.type === 'CLASS_UNAVAILABLE',
    )).toBe(true)
  })
})
