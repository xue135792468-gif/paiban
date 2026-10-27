import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
} from './helpers'
import { capture } from './screenshots'

/**
 * 票 05 前端切片：模板完整保存/套用，以及跨学期行政班引用的显式重新绑定。
 *
 * 用例串行、共用一套合成数据。断言只落在稳定可观察状态上：模板下拉的规模、
 * 顶部「具体条目」计数、条目卡片摘要、失效清单文本与接口返回。
 */
test.describe.serial('约束配置：模板完整套用与跨学期引用', () => {
  const suffix = Math.random().toString(36).slice(2, 8)
  const templateName = `标准学期-${suffix}`
  const crossTemplateName = `跨学期模板-${suffix}`
  const unavailable = '[data-type="TEACHER_UNAVAILABLE"]'

  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed
  /** Second semester, its class, and the class the cross-semester template names. */
  let target: { semesterId: number; classId: number; className: string }
  let sourceClassName: string

  /** The template area is part of the senior-school view (ADR-0018). */
  async function setSchoolType(type: 'junior' | 'senior') {
    await apiCall(api, token, 'put', '/settings', { 'school.type': type })
  }

  test.beforeAll(async () => {
    api = await newContext()
    token = await apiLogin(api)
    await setSchoolType('senior')
    seed = await seedConstraintScenario(api)
    const [first, second] = seed.teachers
    await apiCall(api, token, 'put', `/constraints?semesterId=${seed.semesterId}`, {
      configs: [
        {
          type: 'TEACHER_UNAVAILABLE',
          weight: 100,
          hard: true,
          enabled: true,
          entries: [
            { params: { teacherId: first.id, dayOfWeek: 1, periodNo: 1 } },
            { params: { teacherId: first.id, dayOfWeek: 1, periodNo: 2 } },
            { params: { teacherId: second.id, dayOfWeek: 3, periodNo: 4 } },
          ],
        },
        {
          type: 'TEACHER_MAX_PER_DAY',
          weight: 60,
          hard: false,
          enabled: true,
          entries: [
            { params: { maxPerDay: 6 } },
            { params: { teacherId: first.id, maxPerDay: 3 } },
          ],
        },
      ],
    })

    // The cross-semester template names a class of the *first* semester.
    sourceClassName = `模板班A-${suffix}`
    const grade = await apiCall(api, token, 'post', `/grades?semesterId=${seed.semesterId}`, {
      name: `年级A-${suffix}`,
      sortOrder: 1,
    })
    const sourceClass = await apiCall(
      api,
      token,
      'post',
      `/classes?semesterId=${seed.semesterId}`,
      { gradeId: grade.id, name: sourceClassName, studentCount: 45 },
    )
    await apiCall(api, token, 'put', `/constraints?semesterId=${seed.semesterId}`, {
      configs: [
        {
          type: 'TEACHER_UNAVAILABLE',
          weight: 100,
          hard: true,
          enabled: true,
          entries: [{ params: { teacherId: first.id, dayOfWeek: 1, periodNo: 1 } }],
        },
        {
          type: 'CLASS_MAX_PER_DAY',
          weight: 80,
          hard: false,
          enabled: true,
          entries: [
            { params: { maxPerDay: 8 } },
            { params: { classId: sourceClass.id, maxPerDay: 5 } },
          ],
        },
      ],
    })
    await apiCall(api, token, 'post', `/constraint-templates?semesterId=${seed.semesterId}`, {
      name: crossTemplateName,
    })

    // Restore the five-entry configuration the first case saves as a template.
    await apiCall(api, token, 'put', `/constraints?semesterId=${seed.semesterId}`, {
      configs: [
        {
          type: 'TEACHER_UNAVAILABLE',
          weight: 100,
          hard: true,
          enabled: true,
          entries: [
            { params: { teacherId: first.id, dayOfWeek: 1, periodNo: 1 } },
            { params: { teacherId: first.id, dayOfWeek: 1, periodNo: 2 } },
            { params: { teacherId: second.id, dayOfWeek: 3, periodNo: 4 } },
          ],
        },
        {
          type: 'TEACHER_MAX_PER_DAY',
          weight: 60,
          hard: false,
          enabled: true,
          entries: [
            { params: { maxPerDay: 6 } },
            { params: { teacherId: first.id, maxPerDay: 3 } },
          ],
        },
      ],
    })

    // A second semester with its own class: the only legal rebinding target.
    const semester = await apiCall(api, token, 'post', '/semesters', {
      name: `E2E模板目标-${suffix}`,
      startDate: '2026-02-20',
      endDate: '2026-07-10',
    })
    await apiCall(api, token, 'post', `/semesters/${semester.id}/periods/default`, {
      morningCount: 4,
      afternoonCount: 4,
      eveningCount: 2,
      days: [1, 2, 3, 4, 5],
    })
    const targetGrade = await apiCall(api, token, 'post', `/grades?semesterId=${semester.id}`, {
      name: `年级B-${suffix}`,
      sortOrder: 1,
    })
    const className = `模板班B-${suffix}`
    const adminClass = await apiCall(api, token, 'post', `/classes?semesterId=${semester.id}`, {
      gradeId: targetGrade.id,
      name: className,
      studentCount: 45,
    })
    target = { semesterId: semester.id, classId: adminClass.id, className }
  })

  test.afterAll(async () => {
    await setSchoolType('junior').catch(() => undefined)
    await api.dispose()
  })

  function entryCards(page: Page) {
    return page.getByTestId('constraint-entry')
  }

  function summaryEntries(page: Page) {
    return page.getByTestId('constraint-summary-entries')
  }

  /** The dialog of the moment; closed message boxes linger in the DOM. */
  function messageBox(page: Page) {
    return page.locator('.el-message-box:visible')
  }

  async function pickTemplate(page: Page, name: string, expectedScale?: string) {
    await page.getByTestId('constraint-template-select').click()
    const option = page
      .locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: name })
      .first()
    await expect(option).toBeVisible()
    if (expectedScale) {
      await expect(option).toContainText(expectedScale)
    }
    await option.click()
  }

  test('保存含多条条目的模板，改动后套用即恢复条目与计数', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await expect(summaryEntries(page)).toContainText('具体条目 5 条')

    await page.getByTestId('constraint-template-save').click()
    const prompt = messageBox(page)
    await expect(prompt).toBeVisible()
    await prompt.getByPlaceholder('例如：标准学期模板').fill(templateName)
    await prompt.getByRole('button', { name: '确定' }).click()
    // The saved template is listed with its size: 2 types / 5 entries.
    await pickTemplate(page, templateName, '（2 类 · 5 条）')

    // 改动配置：删掉一条禁排并保存，计数降到 4。
    await page.locator(unavailable).click()
    await expect(entryCards(page)).toHaveCount(3)
    await entryCards(page).first().getByTestId('constraint-entry-remove').click()
    const confirm = messageBox(page)
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '删除' }).click()
    await expect(entryCards(page)).toHaveCount(2)
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
    await expect(summaryEntries(page)).toContainText('具体条目 4 条')

    // 套用模板：先预览，再确认，结果来自服务端响应。
    await page.getByTestId('constraint-template-apply').click()
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '确定' }).click()
    await expect(page.getByTestId('constraint-template-result')).toContainText('恢复 2 类配置')
    await expect(summaryEntries(page)).toContainText('具体条目 5 条')
    await capture(page, 'constraint-template-applied')

    await page.locator(unavailable).click()
    await expect(entryCards(page)).toHaveCount(3)
    await expect(entryCards(page).nth(2).getByTestId('constraint-entry-summary')).toContainText(
      '李娜',
    )

    // 落库证据：三条禁排 + 两条阈值一起回来了。
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    const teacherUnavailable = configs.find((item: any) => item.type === 'TEACHER_UNAVAILABLE')
    expect(teacherUnavailable.entries).toHaveLength(3)
    expect(teacherUnavailable.hard).toBe(true)
    const threshold = configs.find((item: any) => item.type === 'TEACHER_MAX_PER_DAY')
    expect(threshold.entries).toHaveLength(2)
    expect(threshold.weight).toBe(60)
  })

  test('模板往返保留要求分组与逐项控制', async ({ page }) => {
    await apiCall(api, token, 'post', `/semesters/${seed.semesterId}/set-current`)
    const controlsTemplate = `控制模板-${suffix}`
    const course = seed.courses[0]
    const state = await apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId: seed.semesterId,
    })
    await apiCall(
      api,
      token,
      'put',
      `/constraints?semesterId=${seed.semesterId}&baseVersion=${state.version}`,
      {
        configs: [
          {
            type: 'COURSE_UNAVAILABLE',
            weight: 100,
            hard: true,
            enabled: true,
            entries: [
              {
                params: { courseId: course.id, dayOfWeek: 3, periodNo: 1 },
                groupKey: 'g-course',
                hard: false,
                weight: 35,
                enabled: true,
              },
              {
                params: { courseId: course.id, dayOfWeek: 5, periodNo: 6 },
                groupKey: 'g-course',
                hard: false,
                weight: 35,
                enabled: true,
              },
            ],
          },
        ],
      },
    )
    await apiCall(api, token, 'post', `/constraint-templates?semesterId=${seed.semesterId}`, {
      name: controlsTemplate,
    })
    // Wipe the configuration so the template is the only source.
    const wiped = await apiCall(api, token, 'get', '/constraints/state', undefined, {
      semesterId: seed.semesterId,
    })
    await apiCall(
      api,
      token,
      'put',
      `/constraints?semesterId=${seed.semesterId}&baseVersion=${wiped.version}`,
      { configs: [] },
    )

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await pickTemplate(page, controlsTemplate, '（1 类 · 2 条）')
    await page.getByTestId('constraint-template-apply').click()
    const confirm = messageBox(page)
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '确定' }).click()
    await expect(page.getByTestId('constraint-template-result')).toContainText('恢复 1 类配置')

    const restored = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    const courseUnavailable = restored.find((item: any) => item.type === 'COURSE_UNAVAILABLE')
    expect(courseUnavailable.entries).toHaveLength(2)
    expect(new Set(courseUnavailable.entries.map((entry: any) => entry.groupKey)).size).toBe(1)
    expect(courseUnavailable.entries[0].hard).toBe(false)
    expect(courseUnavailable.entries[0].weight).toBe(35)

    await page.locator('[data-type="COURSE_UNAVAILABLE"]').click()
    const card = page.getByTestId('constraint-entry').first()
    await expect(card.getByTestId('constraint-entry-effective')).toContainText('实际：软约束')
    await expect(card).toContainText('重要程度 35')
    await capture(page, 'constraint-template-controls')
  })

  test('跨学期套用：失效班级列出清单，重新绑定后套用成功', async ({ page }) => {
    // Switch the current semester through the API: the page always opens the current one.
    await apiCall(api, token, 'post', `/semesters/${target.semesterId}/set-current`)

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await pickTemplate(page, crossTemplateName)
    await page.getByTestId('constraint-template-apply').click()

    const issues = page.getByTestId('constraint-template-issues')
    await expect(issues).toBeVisible()
    await expect(issues).toContainText('班级一天最多 N 节')
    await expect(issues).toContainText(sourceClassName)
    await expect(issues).toContainText('行政班属于其他学期')
    await expect(page.getByTestId('constraint-template-issues-summary')).toContainText(
      '需选择当前学期的班级',
    )
    await expect(page.getByTestId('constraint-template-issues-apply')).toBeDisabled()
    await capture(page, 'constraint-template-cross-semester-issues')

    // 显式选择一个当前学期的班级，绝不按同名猜测。
    await page.getByTestId('constraint-template-rebind-0').click()
    const option = page
      .locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: target.className })
      .first()
    await expect(option).toBeVisible()
    await option.click()
    await expect(page.getByTestId('constraint-template-issues-apply')).toBeEnabled()
    await page.getByTestId('constraint-template-issues-apply').click()

    await expect(page.getByTestId('constraint-template-result')).toContainText('恢复 2 类配置')
    await expect(page.getByTestId('constraint-template-issues')).toBeHidden()

    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: target.semesterId,
    })
    const threshold = configs.find((item: any) => item.type === 'CLASS_MAX_PER_DAY')
    expect(threshold.entries.map((entry: any) => entry.params.classId)).toEqual([
      undefined,
      target.classId,
    ])
    const unavailable = configs.find((item: any) => item.type === 'TEACHER_UNAVAILABLE')
    expect(unavailable.entries[0].params.teacherId).toBe(seed.teachers[0].id)
  })
})
