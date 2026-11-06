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
 * 票 04：教师分组从建组到规则、教师列表、删除影响的一条主路径。
 * 规则落库只存 `teacherGroupId`，展开成成员是后端快照的职责（04a 集成测试）。
 */
test.describe('排课规则：教师分组', () => {
  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed

  test.beforeAll(async () => {
    api = await newContext()
    token = await apiLogin(api)
    seed = await seedConstraintScenario(api)
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

  async function chooseDays(page: Page, labels: string[]) {
    const select = form(page).getByTestId('rule-days')
    await select.click()
    const dropdown = page.locator('.el-select-dropdown:visible').last()
    for (const label of labels) {
      await dropdown.getByText(label, { exact: true }).first().click()
    }
    await page.keyboard.press('Escape')
    await expect(dropdown).toBeHidden()
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

  test('建组、写规则、教师列表后缀与筛选、删组连带规则', async ({ page }) => {
    const suffix = Math.random().toString(36).slice(2, 7)
    const groupName = `教导主任${suffix}`
    const [firstTeacher, secondTeacher] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    // 1. 规则页「分组」区：新建组并选两名教师。
    await page.getByTestId('rule-groups-toggle').click()
    await expect(page.getByTestId('rule-group-section')).toBeVisible()
    await page.getByTestId('rule-group-new').click()
    await page.getByTestId('rule-group-name-input').fill(groupName)
    await page.getByTestId('rule-group-members').click()
    const memberDropdown = page.locator('.el-select-dropdown:visible').last()
    await memberDropdown.getByText(firstTeacher.name, { exact: true }).click()
    await memberDropdown.getByText(secondTeacher.name, { exact: true }).click()
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
        response.url().includes(`/api/rule-groups/`) &&
        response.request().method() === 'PUT' &&
        response.status() === 200,
    )
    await page.getByTestId('rule-group-save').click()
    const group = await (await createdResponse).json()
    await updatedResponse
    const groupRow = page.getByTestId(`rule-group-row-${group.id}`)
    await expect(groupRow).toBeVisible()
    await expect(groupRow).toContainText(firstTeacher.name)
    await expect(groupRow).toContainText(secondTeacher.name)

    // 2. 新建规则：主语选教师组，周三第 5–8 节不上课。
    await page.getByTestId('rule-new').click()
    await expect(form(page)).toBeVisible()
    await pickOption(form(page), 'rule-subject', `教师组 · ${groupName}`)
    await chooseDays(page, ['周三'])
    for (const periodNo of [5, 6, 7, 8]) {
      await form(page).getByTestId(`rule-period-${periodNo}`).click()
    }
    await pickOption(form(page), 'rule-type', '不上课')
    const sentence = `${groupName} 周三第 5、6、7、8 节 不上课`
    await expect(form(page).getByTestId('rule-preview')).toContainText(sentence)
    await form(page).getByTestId('rule-submit').click()
    await expect(page.getByTestId('rule-sentence')).toHaveText(sentence)

    await saveRules(page)

    // 3. 落库是组引用，不是展开后的成员。
    let saved = await state()
    let teacherRule = saved.configs.find(
      (config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE',
    )
    expect(teacherRule.entries).toHaveLength(4)
    expect(
      teacherRule.entries.every(
        (entry: { params: Record<string, unknown> }) =>
          entry.params.teacherGroupId === group.id && !('teacherId' in entry.params),
      ),
    ).toBe(true)
    expect(new Set(teacherRule.entries.map((entry: { groupKey: string }) => entry.groupKey)).size)
      .toBe(1)

    await page.reload()
    await expect(page.getByText(sentence)).toBeVisible()

    // 4. 改组成员（API）后规则仍引用组，句子不变。
    await apiCall(api, token, 'put', `/rule-groups/${group.id}`, {
      type: 'TEACHER',
      name: groupName,
      members: [{ teacherId: firstTeacher.id }],
    })
    await page.reload()
    await expect(page.getByText(sentence)).toBeVisible()
    saved = await state()
    teacherRule = saved.configs.find(
      (config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE',
    )
    expect(teacherRule.entries.every(
      (entry: { params: Record<string, unknown> }) => entry.params.teacherGroupId === group.id,
    )).toBe(true)

    // 5. 教师列表显示组后缀，并可按组筛选。
    await page.goto('/data/teachers')
    await expect(page.getByTestId('teacher-group-filter')).toBeVisible()
    const firstRow = page.locator('.el-table__row').filter({ hasText: firstTeacher.name })
    await expect(firstRow).toContainText(`· ${groupName}`)
    await page.getByTestId('teacher-group-filter').click()
    const groupDropdown = page.locator('.el-select-dropdown:visible').last()
    await groupDropdown.getByText(groupName, { exact: true }).click()
    await expect(page.locator('.el-table__row').filter({ hasText: firstTeacher.name })).toBeVisible()
    await expect(page.locator('.el-table__row').filter({ hasText: secondTeacher.name }))
      .toHaveCount(0)

    // 6. 删除组先列影响，选「连同规则一起删除」后规则消失。
    await page.goto('/rules')
    await page.getByTestId('rule-groups-toggle').click()
    await expect(page.getByTestId(`rule-group-row-${group.id}`)).toBeVisible()
    await page.getByTestId(`rule-group-delete-${group.id}`).click()
    const dialog = page.locator('.el-message-box')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('删除后这些规则会失效')
    await expect(dialog).toContainText('E2E约束')
    await expect(dialog).toContainText('教师禁排时段')
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
        (config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE',
      ),
    ).toBe(false)

    // 7. 组和规则卡都进入 7 天撤销的「最近删除」。
    await page.goto('/settings/recycle-bin')
    const rows = page.locator('.el-table__row')
    await expect(rows.filter({ hasText: groupName }).first()).toContainText('分组')
    await expect(rows.filter({ hasText: '排课规则' }).first()).toBeVisible()
  })
})
