import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedConstraintScenario, uiLogin, type ConstraintSeed } from './helpers'
import { capture } from './screenshots'

/**
 * 票 04 前端切片：五类阈值约束支持「全局默认 + 具体对象例外」。
 *
 * 断言只落在阈值面板（全局行、例外行、覆盖标签）、左侧条目计数与接口返回上。
 * 用例串行、共用同一学期，按真实教务「先配默认、再给个别教师收紧、最后撤销」的顺序走。
 */
test.describe.serial('约束配置：阈值全局默认与对象例外', () => {
  const TEACHER_MAX = '[data-type="TEACHER_MAX_PER_DAY"]'

  let api: APIRequestContext
  let seed: ConstraintSeed

  test.beforeAll(async () => {
    api = await newContext()
    seed = await seedConstraintScenario(api)
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  async function openType(page: Page) {
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await page.locator(TEACHER_MAX).click()
    await expect(page.getByTestId('constraint-panel-title')).toContainText('教师一天最多 N 节')
  }

  async function enableType(page: Page) {
    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) {
      await toggle.click()
    }
    await expect(toggle).toHaveClass(/is-checked/)
  }

  /** 数字输入是 el-input-number：填值后失焦才会提交到 v-model。 */
  async function fillNumber(scope: Locator, testId: string, value: string) {
    const input = scope.getByTestId(testId).locator('input')
    await input.fill(value)
    await input.press('Tab')
  }

  async function pickTeacher(row: Locator, name: string) {
    const trigger = row.getByTestId('constraint-threshold-object')
    await trigger.click()
    // 下拉被 teleport 到 <body>：用触发器 input 的 aria-controls 锁定「这一个」下拉。
    const controls = await trigger.locator('input').getAttribute('aria-controls')
    const dropdown = row.page().locator(`#${controls}`)
    await expect(dropdown).toBeVisible()
    const option = dropdown.locator('.el-select-dropdown__item').filter({ hasText: name }).first()
    await expect(option).toBeVisible()
    await option.click()
    await expect(dropdown).toBeHidden()
  }

  function navCount(page: Page) {
    return page.locator(`${TEACHER_MAX} [data-testid="constraint-nav-count"]`)
  }

  async function saveAndWait(page: Page) {
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
  }

  async function configOf() {
    const token = await apiLogin(api)
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    return configs.find((item: any) => item.type === 'TEACHER_MAX_PER_DAY')
  }

  test('设全局 6 + 给某位教师设 3：保存刷新后两行都在，例外显示覆盖 6', async ({ page }) => {
    const [teacher] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await openType(page)
    await enableType(page)

    // 还没有全局默认时，面板明确说未列出的对象不设上限。
    await expect(page.getByTestId('constraint-threshold-no-global-hint')).toContainText('不设上限')
    await page.getByTestId('constraint-threshold-add-global').click()
    await fillNumber(page, 'constraint-threshold-global-value', '6')
    await expect(navCount(page)).toHaveText('1 条')

    await page.getByTestId('constraint-threshold-add-exception').click()
    const exception = page.getByTestId('constraint-threshold-exception').first()
    await expect(exception).toBeVisible()
    await pickTeacher(exception, teacher.name)
    await fillNumber(exception, 'constraint-threshold-value', '3')

    // 例外替代的对象写在标签里：一眼看出 3 盖住了全局 6。
    await expect(exception.getByTestId('constraint-threshold-override')).toHaveText('覆盖全局 6')
    await expect(navCount(page)).toHaveText('2 条')

    await capture(page, 'constraint-threshold-scope')
    await saveAndWait(page)

    const saved = await configOf()
    expect(saved.enabled).toBe(true)
    expect(saved.entries).toHaveLength(2)
    expect(saved.entries[0].params).toEqual({ maxPerDay: 6 })
    expect(saved.entries[1].params).toEqual({ teacherId: teacher.id, maxPerDay: 3 })

    // 刷新后：全局 6、一条例外、仍显示覆盖 6，且没有未保存改动。
    await page.reload()
    await page.locator(TEACHER_MAX).click()
    await expect(
      page.getByTestId('constraint-threshold-global-value').locator('input'),
    ).toHaveValue('6')
    const reloaded = page.getByTestId('constraint-threshold-exception')
    await expect(reloaded).toHaveCount(1)
    await expect(reloaded.first().getByTestId('constraint-threshold-override')).toHaveText('覆盖全局 6')
    await expect(
      reloaded.first().getByTestId('constraint-threshold-value').locator('input'),
    ).toHaveValue('3')
    await expect(navCount(page)).toHaveText('2 条')
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
  })

  test('删除例外后回到全局 6；再删全局则回到空状态并提示不设上限', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openType(page)

    await page.getByTestId('constraint-threshold-exception').first().getByTestId(
      'constraint-threshold-remove',
    ).click()
    await expect(page.getByTestId('constraint-threshold-exception')).toHaveCount(0)
    await expect(navCount(page)).toHaveText('1 条')
    await saveAndWait(page)
    let saved = await configOf()
    expect(saved.entries).toHaveLength(1)
    expect(saved.entries[0].params).toEqual({ maxPerDay: 6 })

    // 全局也删掉：该类型回到「未配置」，未列出的对象当然不设上限。
    await page.getByTestId('constraint-threshold-global-remove').click()
    await expect(page.getByTestId('constraint-threshold-empty')).toBeVisible()
    await expect(page.getByTestId('constraint-threshold-no-global-hint')).toContainText('不设上限')
    await expect(navCount(page)).toHaveText('0 条')
    await saveAndWait(page)
    saved = await configOf()
    expect(saved.entries).toHaveLength(0)

    await page.reload()
    await page.locator(TEACHER_MAX).click()
    await expect(page.getByTestId('constraint-threshold-empty')).toBeVisible()
    await expect(page.getByTestId('constraint-threshold-global-empty')).toBeVisible()
  })

  test('同一教师两条例外：保存前被拦下，不发请求也不产生部分写入', async ({ page }) => {
    const [teacher] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await openType(page)
    await page.getByTestId('constraint-threshold-add-global').click()
    await fillNumber(page, 'constraint-threshold-global-value', '6')

    await page.getByTestId('constraint-threshold-add-exception').click()
    await page.getByTestId('constraint-threshold-add-exception').click()
    const rows = page.getByTestId('constraint-threshold-exception')
    await expect(rows).toHaveCount(2)
    for (const index of [0, 1]) {
      await pickTeacher(rows.nth(index), teacher.name)
    }
    await fillNumber(rows.nth(0), 'constraint-threshold-value', '3')
    await fillNumber(rows.nth(1), 'constraint-threshold-value', '4')

    await page.getByTestId('constraint-save').click()

    const precheck = page.getByTestId('constraint-precheck-error')
    await expect(precheck).toBeVisible()
    await expect(precheck).toContainText('同一作用域最多一条阈值')
    await expect(page.getByTestId('constraint-unsaved')).toBeVisible()
    // 没有发出请求：服务端仍是上一条用例留下的空配置。
    const saved = await configOf()
    expect(saved.entries).toHaveLength(0)
  })

  test('五类阈值各有自己的对象选择器（人工复核用，不作断言）', async ({ page }) => {
    const types: Array<[string, string]> = [
      ['TEACHER_MAX_PER_DAY', '教师一天最多 N 节'],
      ['CLASS_MAX_PER_DAY', '班级一天最多 N 节'],
      ['COURSE_MAX_PER_DAY', '课程一天最多 N 节'],
      ['EVENING_MAX', '晚自习最多 N 节'],
      ['TEACHER_MAX_CONSECUTIVE', '教师连续上课上限'],
    ]
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    for (const [code, title] of types) {
      await page.locator(`[data-type="${code}"]`).click()
      await expect(page.getByTestId('constraint-panel-title')).toContainText(title)
      await expect(page.getByTestId('constraint-threshold-panel')).toBeVisible()
      await capture(page, `constraint-threshold-${code}`)
    }
  })
})
