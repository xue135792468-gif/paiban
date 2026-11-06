import { expect, test, type APIRequestContext } from '@playwright/test'
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
 * 票 06：时间组从预建、规则「什么时候」到作息列表后缀/筛选的一条主路径。
 * 规则落库只存 `courseGroupId` + `timeGroupId`，展开成具体课节是后端快照的职责。
 */
test.describe('排课规则：时间分组', () => {
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

  async function timeGroups() {
    return apiCall(api, token, 'get', '/rule-groups', undefined, {
      type: 'TIME',
      semesterId: seed.semesterId,
    })
  }

  test('预建上午/下午、规则选时间组、作息后缀与筛选', async ({ page }) => {
    const suffix = Math.random().toString(36).slice(2, 7)

    // 1. 每天 8 节：1–4 节上午（12:00 前），5–8 节下午。后端按作息重算预建组。
    const times = [
      [1, '08:00', '08:45'],
      [2, '08:55', '09:40'],
      [3, '09:55', '10:40'],
      [4, '10:50', '11:35'],
      [5, '14:00', '14:45'],
      [6, '14:55', '15:40'],
      [7, '15:55', '16:40'],
      [8, '16:50', '17:35'],
    ] as const
    await apiCall(api, token, 'put', `/semesters/${seed.semesterId}/periods`, {
      slots: times.map(([periodNo, startTime, endTime]) => ({
        periodNo,
        label: `第${periodNo}节`,
        startTime: `${startTime}:00`,
        endTime: `${endTime}:00`,
        days: [1, 2, 3, 4, 5],
      })),
    })

    const seededGroups = await timeGroups()
    const morning = seededGroups.find((group: any) => group.name === '上午')
    const afternoon = seededGroups.find((group: any) => group.name === '下午')
    expect(morning).toBeTruthy()
    expect(afternoon).toBeTruthy()
    expect(morning.autoFilled).toBe(true)
    expect(morning.members).toHaveLength(20)
    expect(afternoon.members).toHaveLength(20)

    // 2. 课程组「技能课」通过 API 建好，规则主语直接引用它。
    const courseGroupName = `技能课${suffix}`
    const courseGroup = await apiCall(api, token, 'post', '/rule-groups', {
      type: 'COURSE',
      name: courseGroupName,
      members: [{ courseId: seed.courses[0].id }],
    })

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    // 3. 规则页「分组」区能看到时间分组和预建标记。
    await page.getByTestId('rule-groups-toggle').click()
    await expect(page.getByTestId('rule-time-group-section')).toBeVisible()
    const morningRow = page.getByTestId(`rule-time-group-row-${morning.id}`)
    await expect(morningRow).toContainText('上午')
    await expect(morningRow).toContainText('预建')

    // 4. 拼出「技能课 不排在 上午」并保存。
    await page.getByTestId('rule-new').click()
    const form = page.getByTestId('rule-form')
    await expect(form).toBeVisible()
    await pickOption(form, 'rule-subject', `课程组 · ${courseGroupName}`)
    await pickOption(form, 'rule-type', '不排课')
    await pickOption(form, 'rule-time-group', '时间组 · 上午')
    const sentence = `${courseGroupName} 不排在 上午`
    await expect(form.getByTestId('rule-preview')).toContainText(sentence)
    await form.getByTestId('rule-submit').click()
    await expect(page.getByTestId('rule-sentence')).toHaveText(sentence)

    const savedResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/constraints') &&
        response.request().method() === 'PUT' &&
        response.status() === 200,
    )
    await page.getByTestId('rule-save').click()
    await savedResponse
    await expect(page.getByTestId('rule-save')).toBeDisabled()

    // 5. 落库是一行组引用，不写具体课程、星期或节次。
    const savedState = await state()
    const rule = savedState.configs.find(
      (config: { type: string }) => config.type === 'COURSE_UNAVAILABLE',
    )
    expect(rule.entries).toHaveLength(1)
    const params = rule.entries[0].params
    expect(params.courseGroupId).toBe(courseGroup.id)
    expect(params.timeGroupId).toBe(morning.id)
    expect(params).not.toHaveProperty('courseId')
    expect(params).not.toHaveProperty('dayOfWeek')
    expect(params).not.toHaveProperty('periodNo')

    // 6. 刷新后句子仍显示组名。
    await page.reload()
    await expect(page.getByText(sentence)).toBeVisible()

    // 7. 编辑上午组去掉一节：组名不变，规则句子不变，成员少一个。
    const remaining = morning.members
      .filter((member: any) => !(member.dayOfWeek === 1 && member.periodNo === 1))
      .map((member: any) => ({
        dayOfWeek: member.dayOfWeek,
        periodNo: member.periodNo,
      }))
    await apiCall(api, token, 'put', `/rule-groups/${morning.id}`, {
      type: 'TIME',
      name: '上午',
      semesterId: seed.semesterId,
      members: remaining,
    })
    const editedGroups = await timeGroups()
    expect(editedGroups.find((group: any) => group.id === morning.id).members).toHaveLength(19)
    await page.reload()
    await expect(page.getByText(sentence)).toBeVisible()

    // 8. 作息页显示时间组后缀，并可按组筛选。
    await page.goto(`/data/semesters/${seed.semesterId}/schedule`)
    await expect(page.getByTestId('schedule-time-group-1')).toContainText('上午')
    await expect(page.getByTestId('schedule-time-group-5')).toContainText('下午')
    await page.getByTestId('schedule-group-filter').click()
    const dropdown = page.locator('.el-select-dropdown:visible').last()
    await dropdown.getByText('上午', { exact: true }).click()
    await expect(page.getByTestId('schedule-time-group-1')).toBeVisible()
    await expect(page.getByTestId('schedule-time-group-5')).toHaveCount(0)
  })
})
