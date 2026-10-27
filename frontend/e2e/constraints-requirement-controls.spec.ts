import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
} from './helpers'

/**
 * Ticket 14/05: a threshold exception carries its own strength. A looser exception
 * (larger number or must → prefer) must be shown and confirmed before saving, and the
 * stored controls must decide what runs.
 */
test.describe.serial('约束配置：阈值例外的逐项控制与放宽确认', () => {
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

  async function pickOption(scope: Locator, field: string, text: string) {
    await scope.getByTestId(field).click()
    // The dropdown is teleported to <body>; the one just opened is appended last.
    const options = scope
      .page()
      .locator('.el-select-dropdown:visible')
      .last()
      .locator('.el-select-dropdown__item')
    // Exact label first: "尽量" must not select "继承类型（尽量）". A caller that
    // names a longer label ("继承类型（必须）") still matches exactly.
    // No contains-fallback: the retry inside toBeVisible waits for the dropdown to
    // render instead of silently clicking "继承类型（尽量）" for "尽量".
    const option = options.filter({ hasText: new RegExp(`^${text}$`) }).first()
    await expect(option).toBeVisible()
    await option.click()
  }

  async function setNumber(scope: Locator, field: string, value: string) {
    const input = scope.getByTestId(field).locator('input')
    await input.fill(value)
    await input.press('Tab')
  }

  async function openThreshold(page: Page) {
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await page.locator(TEACHER_MAX).click()
    await expect(page.getByTestId('constraint-threshold-panel')).toBeVisible()
  }

  async function config(token: string) {
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    return configs.find((item: any) => item.type === 'TEACHER_MAX_PER_DAY')
  }

  test('放宽的例外先提示再保存，保存后数值与强度都在', async ({ page }) => {
    const teacher = seed.teachers[0]
    await uiLogin(page, 'admin', 'admin123')
    await openThreshold(page)

    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) {
      await toggle.click()
    }

    // Global: at most 4, must.
    await page.getByTestId('constraint-threshold-add-global').click()
    const globalRow = page.getByTestId('constraint-threshold-global')
    await setNumber(globalRow, 'constraint-threshold-global-value', '4')
    await pickOption(globalRow, 'constraint-threshold-global-hard', '必须')
    // The trigger text is the proof the click landed: the inherit option also
    // contains the words "必须"/"尽量".
    await expect(globalRow.getByTestId('constraint-threshold-global-hard')).toHaveText('必须')
    await expect(
      globalRow.getByTestId('constraint-threshold-global-effective'),
    ).toContainText('实际：必须')

    // Exception: teacher A may go up to 6, prefer. That loosens the global.
    await page.getByTestId('constraint-threshold-add-exception').click()
    const exception = page.getByTestId('constraint-threshold-exception').first()
    await pickOption(exception, 'constraint-threshold-object', teacher.name)
    await setNumber(exception, 'constraint-threshold-value', '6')
    await pickOption(exception, 'constraint-threshold-exception-hard', '尽量')
    await expect(exception.getByTestId('constraint-threshold-exception-hard')).toHaveText('尽量')
    await expect(exception.getByTestId('constraint-threshold-relaxed')).toContainText('比全局放宽')

    await page.getByTestId('constraint-save').click()
    const confirm = page.locator('.el-message-box')
    await expect(confirm).toBeVisible()
    await expect(confirm).toContainText('确认放宽')
    await expect(confirm).toContainText(teacher.name)
    // Nothing is written until the user confirms.
    await confirm.getByRole('button', { name: '返回修改' }).click()
    expect(await config(await apiLogin(api))).toBeUndefined()

    await page.getByTestId('constraint-save').click()
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '确认放宽并保存' }).click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    const saved = await config(await apiLogin(api))
    expect(saved.entries).toHaveLength(2)
    expect(saved.entries[0].params.maxPerDay).toBe(4)
    expect(saved.entries[0].hard).toBe(true)
    expect(saved.entries[1].params.maxPerDay).toBe(6)
    expect(saved.entries[1].hard).toBe(false)
  })

  test('刷新后例外仍显示实际强度与放宽标记', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openThreshold(page)
    const globalRow = page.getByTestId('constraint-threshold-global')
    await expect(globalRow.getByTestId('constraint-threshold-global-effective')).toContainText(
      '实际：必须',
    )
    const exception = page.getByTestId('constraint-threshold-exception').first()
    await expect(exception.getByTestId('constraint-threshold-relaxed')).toBeVisible()
    await expect(exception).toContainText('尽量')
  })
})
