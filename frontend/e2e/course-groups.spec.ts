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
 * 票 05：课程分组从建组到规则、课程列表、删除影响的一条主路径。
 * 规则落库只存 `courseGroupId`，展开成成员是后端快照的职责（05a 集成测试）。
 */
test.describe('排课规则：课程分组', () => {
  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed
  let outsiderCourse: { id: number; name: string }

  test.beforeAll(async () => {
    api = await newContext()
    token = await apiLogin(api)
    seed = await seedConstraintScenario(api)
    // A course outside the group makes the list filter observable.
    const suffix = Math.random().toString(36).slice(2, 7)
    outsiderCourse = await apiCall(api, token, 'post', '/courses', {
      name: `音乐${suffix}`,
      subject: '综合',
      allowConsecutive: false,
    })
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  async function state() {
    return apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId: seed.semesterId,
    })
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

  test('建组、写规则、课程列表后缀与筛选、删组连带规则', async ({ page }) => {
    const suffix = Math.random().toString(36).slice(2, 7)
    const groupName = `技能课${suffix}`
    const [firstCourse, secondCourse] = seed.courses

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    // 1. 规则页「分组」区：课程分组面板新建组并选两门课。
    await page.getByTestId('rule-groups-toggle').click()
    await expect(page.getByTestId('rule-group-section-course')).toBeVisible()
    await page.getByTestId('rule-group-new-course').click()
    await page.getByTestId('rule-group-name-input-course').fill(groupName)
    await page.getByTestId('rule-group-members-course').click()
    const memberDropdown = page.locator('.el-select-dropdown:visible').last()
    await memberDropdown.getByText(firstCourse.name, { exact: true }).click()
    await memberDropdown.getByText(secondCourse.name, { exact: true }).click()
    await page.keyboard.press('Escape')
    await expect(memberDropdown).toBeHidden()

    const createdResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/rule-groups') &&
        response.request().method() === 'POST' &&
        response.status() === 200,
    )
    const updatedResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/rule-groups/') &&
        response.request().method() === 'PUT' &&
        response.status() === 200,
    )
    await page.getByTestId('rule-group-save-course').click()
    const group = await (await createdResponse).json()
    await updatedResponse
    const groupRow = page.getByTestId(`rule-group-row-${group.id}-course`)
    await expect(groupRow).toBeVisible()
    await expect(groupRow).toContainText(firstCourse.name)
    await expect(groupRow).toContainText(secondCourse.name)

    // 2. 新建规则：主语选课程组，要求「每天最多 1 节」。
    await page.getByTestId('rule-new').click()
    await expect(form(page)).toBeVisible()
    await pickOption(form(page), 'rule-subject', `课程组 · ${groupName}`)
    await pickOption(form(page), 'rule-type', '每天最多 N 节')
    const amount = form(page).getByTestId('rule-amount').locator('input')
    await amount.fill('1')
    await amount.blur()
    const sentence = `${groupName} 每天最多 1 节`
    await expect(form(page).getByTestId('rule-preview')).toContainText(sentence)
    await form(page).getByTestId('rule-submit').click()
    await expect(page.getByTestId('rule-sentence')).toHaveText(sentence)

    await saveRules(page)

    // 3. 落库是组引用，不是展开后的成员。
    let saved = await state()
    let courseRule = saved.configs.find(
      (config: { type: string }) => config.type === 'COURSE_MAX_PER_DAY',
    )
    // The stored row is one group reference; expansion to courses happens in the
    // backend snapshot (05a integration tests cover that).
    expect(courseRule.entries).toHaveLength(1)
    expect(
      courseRule.entries.every(
        (entry: { params: Record<string, unknown> }) =>
          entry.params.courseGroupId === group.id && !('courseId' in entry.params),
      ),
    ).toBe(true)
    expect(new Set(courseRule.entries.map((entry: { groupKey: string }) => entry.groupKey)).size)
      .toBe(1)

    await page.reload()
    await expect(page.getByText(sentence)).toBeVisible()

    // 4. 改组成员（API）后规则仍引用组，句子不变。
    await apiCall(api, token, 'put', `/rule-groups/${group.id}`, {
      type: 'COURSE',
      name: groupName,
      members: [{ courseId: firstCourse.id }],
    })
    await page.reload()
    await expect(page.getByText(sentence)).toBeVisible()
    saved = await state()
    courseRule = saved.configs.find(
      (config: { type: string }) => config.type === 'COURSE_MAX_PER_DAY',
    )
    expect(courseRule.entries.every(
      (entry: { params: Record<string, unknown> }) =>
        entry.params.courseGroupId === group.id,
    )).toBe(true)

    // 5. 课程列表显示组后缀，并可按组筛选。
    await page.goto('/data/courses')
    await expect(page.getByTestId('course-group-filter')).toBeVisible()
    const firstRow = page.locator('.el-table__row').filter({ hasText: firstCourse.name })
    await expect(firstRow).toContainText(`· ${groupName}`)
    await page.getByTestId('course-group-filter').click()
    const groupDropdown = page.locator('.el-select-dropdown:visible').last()
    await groupDropdown.getByText(groupName, { exact: true }).click()
    await expect(page.locator('.el-table__row').filter({ hasText: firstCourse.name })).toBeVisible()
    await expect(page.locator('.el-table__row').filter({ hasText: outsiderCourse.name }))
      .toHaveCount(0)

    // 6. 删除组先列影响，选「连同规则一起删除」后规则消失。
    await page.goto('/rules')
    await page.getByTestId('rule-groups-toggle').click()
    await expect(page.getByTestId(`rule-group-row-${group.id}-course`)).toBeVisible()
    await page.getByTestId(`rule-group-delete-${group.id}-course`).click()
    const dialog = page.locator('.el-message-box')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('删除后这些规则会失效')
    await expect(dialog).toContainText('课程一天最多 N 节')
    const deleted = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/rule-groups/${group.id}`) &&
        response.request().method() === 'DELETE' &&
        response.status() === 200,
    )
    await dialog.getByRole('button', { name: '连同规则一起删除' }).click()
    await deleted
    await expect(page.getByTestId('rule-card')).toHaveCount(0)

    const after = await state()
    expect(
      after.configs.some(
        (config: { type: string }) => config.type === 'COURSE_MAX_PER_DAY',
      ),
    ).toBe(false)

    // 7. 组和规则卡都进入 7 天撤销的「最近删除」。
    await page.goto('/settings/recycle-bin')
    const rows = page.locator('.el-table__row')
    await expect(rows.filter({ hasText: groupName }).first()).toContainText('分组')
    await expect(rows.filter({ hasText: '排课规则' }).first()).toBeVisible()
  })
})
