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
 * 票 08：排课规则页用四槽位句子创建时段型规则。断言只落在卡片文案和
 * `/constraints/state` 上——两者是同一份数据的读写两面。
 */
test.describe('排课规则：句子积木', () => {
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

  async function chooseDay(page: Page, label: string) {
    await pickOption(form(page), 'rule-day', label)
  }

  async function addSchoolRule(page: Page) {
    await page.getByTestId('rule-new').click()
    await expect(form(page)).toBeVisible()
    await pickOption(form(page), 'rule-type', '全校/班级 的 课 不排')
    await chooseDay(page, '周五')
    await form(page).getByTestId('rule-period-7').click()
    await expect(form(page).getByTestId('rule-preview')).toContainText(
      '全校 的 课 在 周五第 7 节 不排',
    )
    await form(page).getByTestId('rule-submit').click()
  }

  async function addTeacherAfternoonRule(page: Page, teacherName: string) {
    await page.getByTestId('rule-new').click()
    await expect(form(page)).toBeVisible()
    await pickOption(form(page), 'rule-type', '教师 的 课 不排')
    await pickOption(form(page), 'rule-teacher', teacherName)
    await chooseDay(page, '周五')
    await form(page).getByTestId('rule-afternoon').click()
    await expect(form(page).getByTestId('rule-preview')).toContainText(
      `${teacherName} 的 课 在 周五下午 不排`,
    )
    await form(page).getByTestId('rule-submit').click()
  }

  async function addCourseAmountRule(
    page: Page,
    typeLabel: string,
    courseName: string,
    amount: number,
  ) {
    await page.getByTestId('rule-new').click()
    await expect(form(page)).toBeVisible()
    await pickOption(form(page), 'rule-type', typeLabel)
    await pickOption(form(page), 'rule-course', courseName)
    const input = form(page).getByTestId('rule-amount').locator('input')
    await input.fill(String(amount))
    await input.press('Tab')
    await form(page).getByTestId('rule-submit').click()
  }

  /** Waits for the versioned PUT and the reload that clears the dirty flag. */
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

  /** Ticket 10: delete asks for confirmation after showing the rule's impact. */
  async function confirmDelete(page: Page) {
    const dialog = page.locator('.el-message-box')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('影响')
    await expect(dialog).toContainText('删除后不可恢复')
    await dialog.locator('.el-message-box__btns .el-button--primary').click()
    await expect(dialog).toBeHidden()
  }

  test('创建全校与教师两条规则，保存后刷新仍在', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    await addSchoolRule(page)
    await expect(page.getByTestId('rule-card')).toHaveCount(1)
    await expect(page.getByTestId('rule-sentence')).toHaveText('全校 的 课 在 周五第 7 节 不排')

    const teacherName = seed.teachers[0].name
    await addTeacherAfternoonRule(page, teacherName)
    await expect(page.getByTestId('rule-card')).toHaveCount(2)

    await saveRules(page)

    const saved = await state()
    const school = saved.configs.find(
      (config: { type: string }) => config.type === 'CLASS_UNAVAILABLE',
    )
    expect(school.entries).toHaveLength(1)
    expect(school.entries[0].params).toEqual({ dayOfWeek: 5, periodNo: 7 })
    expect(school.entries[0].groupKey).toMatch(/^pt-/)
    expect(school.hard).toBe(true)

    const teacher = saved.configs.find(
      (config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE',
    )
    expect(teacher.entries).toHaveLength(4)
    expect(teacher.entries.map((entry: { params: { periodNo: number } }) => entry.params.periodNo)).toEqual([
      5, 6, 7, 8,
    ])
    expect(
      teacher.entries.every(
        (entry: { params: { teacherId: number } }) => entry.params.teacherId === seed.teachers[0].id,
      ),
    ).toBe(true)
    expect(new Set(teacher.entries.map((entry: { groupKey: string }) => entry.groupKey)).size).toBe(1)

    await page.reload()
    await expect(page.getByTestId('rule-card')).toHaveCount(2)
    await expect(page.getByText('全校 的 课 在 周五第 7 节 不排')).toBeVisible()
    await expect(page.getByText(`${teacherName} 的 课 在 周五下午 不排`)).toBeVisible()
  })

  test('停用一张卡片、删除另一张，其它类型规则不受影响', async ({ page }) => {
    // 同一学期可能已经有前一条用例保存的分组行；带版本号才能整份替换。
    const before = await state()
    await apiCall(
      api,
      token,
      'put',
      '/constraints',
      {
        configs: [
          {
            type: 'CLASS_UNAVAILABLE',
            weight: 100,
            hard: true,
            enabled: true,
            entries: [
              {
                params: { dayOfWeek: 5, periodNo: 7 },
                enabled: true,
                hard: true,
                groupKey: 'pt-e2e-school',
              },
            ],
          },
          {
            type: 'TEACHER_UNAVAILABLE',
            weight: 100,
            hard: true,
            enabled: true,
            entries: [5, 6, 7, 8].map((periodNo) => ({
              params: {
                teacherId: seed.teachers[0].id,
                dayOfWeek: 5,
                periodNo,
              },
              enabled: true,
              hard: true,
              groupKey: 'pt-e2e-teacher',
            })),
          },
          {
            type: 'TEACHER_CONFLICT',
            params: {},
            weight: 100,
            hard: true,
            enabled: true,
          },
        ],
      },
      { semesterId: seed.semesterId, baseVersion: before.version },
    )

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-card')).toHaveCount(2)
    await expect(page.getByTestId('rule-other-note')).toContainText('1 条其它类型规则')

    const schoolCard = page
      .getByTestId('rule-card')
      .filter({ hasText: '全校 的 课 在 周五第 7 节 不排' })
    await schoolCard.getByTestId('rule-toggle').click()
    await expect(schoolCard.getByTestId('rule-disabled-tag')).toBeVisible()
    await saveRules(page)

    let saved = await state()
    const schoolAfterDisable = saved.configs.find(
      (config: { type: string }) => config.type === 'CLASS_UNAVAILABLE',
    )
    expect(schoolAfterDisable.entries[0].enabled).toBe(false)
    const teacherAfterDisable = saved.configs.find(
      (config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE',
    )
    expect(teacherAfterDisable.entries.every((entry: { enabled: boolean }) => entry.enabled)).toBe(
      true,
    )

    // 编辑卡片：把全校规则从周五第 7 节改成第 6 节，组标识保持不变。
    await schoolCard.getByTestId('rule-edit').click()
    const editForm = form(page)
    await expect(editForm).toBeVisible()
    await editForm.getByTestId('rule-period-6').click()
    await editForm.getByTestId('rule-period-7').click()
    await expect(editForm.getByTestId('rule-preview')).toContainText('全校 的 课 在 周五第 6 节 不排')
    await editForm.getByTestId('rule-submit').click()
    await saveRules(page)

    saved = await state()
    const edited = saved.configs.find(
      (config: { type: string }) => config.type === 'CLASS_UNAVAILABLE',
    )
    expect(edited.entries[0].params.periodNo).toBe(6)
    // 旧单句卡（pt-<uuid>）在第一次编辑后升级为 <cardId>:<句子序号>。
    expect(edited.entries[0].groupKey).toBe('pt-e2e-school:0')

    const teacherCard = page
      .getByTestId('rule-card')
      .filter({ hasText: `${seed.teachers[0].name} 的 课 在 周五下午 不排` })
    await teacherCard.getByTestId('rule-delete').click()
    await confirmDelete(page)
    await expect(page.getByTestId('rule-card')).toHaveCount(1)
    await saveRules(page)

    saved = await state()
    expect(
      saved.configs.some((config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE'),
    ).toBe(false)
    expect(
      saved.configs.find((config: { type: string }) => config.type === 'CLASS_UNAVAILABLE')
        .entries,
    ).toHaveLength(1)
    expect(
      saved.configs.some((config: { type: string }) => config.type === 'TEACHER_CONFLICT'),
    ).toBe(true)

    await page.goto('/rules/advanced')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await expect(page.getByTestId('constraint-nav-item')).toHaveCount(19)
  })

  test('创建数量型、形态型与预设规则，刷新后仍在', async ({ page }) => {
    // 从空配置开始，避免前一条用例的分组行影响卡片数量。
    const before = await state()
    await apiCall(
      api,
      token,
      'put',
      '/constraints',
      { configs: [] },
      { semesterId: seed.semesterId, baseVersion: before.version },
    )

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')
    await expect(page.getByTestId('rule-builder')).toBeVisible()

    const math = seed.courses[0]
    const chinese = seed.courses[1]

    await addCourseAmountRule(page, '课程 每天最多 N 节', math.name, 1)
    await expect(page.getByTestId('rule-card')).toHaveCount(1)
    await expect(page.getByTestId('rule-sentence')).toHaveText(`${math.name} 每天最多 1 节`)

    await addCourseAmountRule(page, '课程 连堂 N 节', chinese.name, 2)
    await expect(page.getByTestId('rule-card')).toHaveCount(2)
    await expect(page.getByText(`${chinese.name} 连堂 2 节`)).toBeVisible()

    await page.getByTestId('rule-new').click()
    await pickOption(form(page), 'rule-type', '体育 不在最后一节')
    await expect(form(page).getByTestId('rule-preview')).toContainText('体育 不在最后一节')
    await form(page).getByTestId('rule-submit').click()
    await expect(page.getByTestId('rule-card')).toHaveCount(3)

    await saveRules(page)

    const saved = await state()
    const courseMax = saved.configs.find(
      (config: { type: string }) => config.type === 'COURSE_MAX_PER_DAY',
    )
    expect(courseMax.entries).toHaveLength(1)
    expect(courseMax.entries[0].params).toEqual({ courseId: math.id, maxPerDay: 1 })

    const consecutive = saved.configs.find(
      (config: { type: string }) => config.type === 'CONSECUTIVE_LESSONS',
    )
    expect(consecutive.entries[0].params).toEqual({
      courseId: chinese.id,
      minConsecutive: 2,
    })

    const peLast = saved.configs.find(
      (config: { type: string }) => config.type === 'PE_LAST_PERIOD_AVOID',
    )
    expect(peLast.entries[0].params).toEqual({})

    await page.reload()
    await expect(page.getByTestId('rule-card')).toHaveCount(3)
    await expect(page.getByText(`${math.name} 每天最多 1 节`)).toBeVisible()
    await expect(page.getByText(`${chinese.name} 连堂 2 节`)).toBeVisible()
    await expect(page.getByText('体育 不在最后一节')).toBeVisible()
  })
})
