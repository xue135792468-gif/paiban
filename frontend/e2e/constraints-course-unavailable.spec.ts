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
 * Ticket 14/01: 课程禁排时段 — one requirement owns a course plus several weekday/
 * period rows, has its own pause/must-prefer controls, and survives save + reload as
 * one group.
 */
test.describe.serial('约束配置：课程禁排时段与逐项控制', () => {
  const COURSE_UNAVAILABLE = '[data-type="COURSE_UNAVAILABLE"]'

  let api: APIRequestContext
  let seed: ConstraintSeed

  test.beforeAll(async () => {
    api = await newContext()
    seed = await seedConstraintScenario(api)
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  function entryCards(page: Page) {
    return page.getByTestId('constraint-entry')
  }

  async function openCourseUnavailable(page: Page) {
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await page.locator(COURSE_UNAVAILABLE).click()
    await expect(page.getByTestId('constraint-panel-title')).toContainText('课程禁排时段')
  }

  async function enableType(page: Page) {
    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) {
      await toggle.click()
    }
    await expect(toggle).toHaveClass(/is-checked/)
  }

  async function pickOption(scope: Locator, field: string, text: string) {
    await scope.getByTestId(field).click()
    const options = scope
      .page()
      .locator('.el-select-dropdown:visible')
      .last()
      .locator('.el-select-dropdown__item')
    // No contains-fallback: the retry inside toBeVisible waits for the dropdown to
    // render instead of silently clicking "继承类型（尽量）" for "尽量".
    const option = options.filter({ hasText: new RegExp(`^${text}$`) }).first()
    await expect(option).toBeVisible()
    await option.click()
  }

  async function addSlot(card: Locator, dayLabel: string, period: string) {
    await card.getByTestId('constraint-entry-slot-add').click()
    const slot = card.getByTestId('constraint-entry-slot').last()
    await pickOption(slot, 'constraint-entry-slot-day', dayLabel)
    await pickOption(slot, 'constraint-entry-slot-period', `第${period}节`)
  }

  async function courseConfig(token: string) {
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    return configs.find((item: any) => item.type === 'COURSE_UNAVAILABLE')
  }

  test('多选星期与不连续课节保存后仍是一条要求', async ({ page }) => {
    const course = seed.courses[0]

    await uiLogin(page, 'admin', 'admin123')
    await openCourseUnavailable(page)
    await enableType(page)

    await page.getByTestId('constraint-entry-add').click()
    const card = entryCards(page).first()
    await expect(card).toBeVisible()
    await pickOption(card, 'constraint-entry-course', course.name)

    const firstSlot = card.getByTestId('constraint-entry-slot').first()
    await pickOption(firstSlot, 'constraint-entry-slot-day', '周三')
    await pickOption(firstSlot, 'constraint-entry-slot-period', '第1节')

    await addSlot(card, '周五', '6')

    await expect(card.getByTestId('constraint-entry-summary')).toContainText(course.name)
    await expect(card.getByTestId('constraint-entry-summary')).toContainText('周三第1节')
    await expect(card.getByTestId('constraint-entry-summary')).toContainText('周五第6节')
    await expect(page.getByTestId('constraint-summary-entries')).toContainText('具体条目 2 条')

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    const token = await apiLogin(api)
    const config = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    }).then((configs: any[]) => configs.find((item) => item.type === 'COURSE_UNAVAILABLE'))
    expect(config.entries).toHaveLength(2)
    expect(config.entries[0].groupKey).toBeTruthy()
    expect(config.entries[0].groupKey).toBe(config.entries[1].groupKey)
    expect(config.entries.map((entry: any) => entry.params.courseId)).toEqual([
      course.id,
      course.id,
    ])

    await page.reload()
    await page.locator(COURSE_UNAVAILABLE).click()
    await expect(entryCards(page)).toHaveCount(1)
    await expect(entryCards(page).first().getByTestId('constraint-entry-summary')).toContainText(
      course.name,
    )
  })

  test('这条要求可单独设为尽量并暂停，保存后状态保留', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openCourseUnavailable(page)
    const card = entryCards(page).first()
    await expect(card).toBeVisible()

    // Per-requirement control: this one prefers instead of forbids.
    await pickOption(card, 'constraint-entry-hard', '尽量')
    await expect(card.getByTestId('constraint-entry-effective')).toContainText('尽量')

    // The slider only appears for an effective soft requirement.
    await expect(card.getByTestId('constraint-entry-weight-override')).toBeVisible()
    await card.getByTestId('constraint-entry-weight-override').click()
    await expect(card.getByTestId('constraint-entry-weight')).toBeVisible()

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    let config = await courseConfig(await apiLogin(api))
    expect(config.entries.every((entry: any) => entry.hard === false)).toBe(true)
    expect(config.entries.every((entry: any) => typeof entry.weight === 'number')).toBe(true)

    // Pause the whole requirement: the content stays, evaluation stops.
    await card.getByTestId('constraint-entry-enabled').click()
    await expect(entryCards(page).first()).toHaveClass(/is-paused/)
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    config = await courseConfig(await apiLogin(api))
    expect(config.entries.every((entry: any) => entry.enabled === false)).toBe(true)
    expect(config.entries).toHaveLength(2)

    await page.reload()
    await page.locator(COURSE_UNAVAILABLE).click()
    await expect(entryCards(page).first()).toHaveClass(/is-paused/)
  })

  test('把这一条改回继承类型默认，控制值清空', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openCourseUnavailable(page)
    const card = entryCards(page).first()
    await card.getByTestId('constraint-entry-enabled').click()
    await pickOption(card, 'constraint-entry-hard', '继承类型（必须）')

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    const config = await courseConfig(await apiLogin(api))
    // The API omits null fields (non_null inclusion), so "inherit" reads as null/undefined.
    expect(config.entries.every((entry: any) => entry.hard == null)).toBe(true)
    expect(config.entries.every((entry: any) => entry.enabled === true)).toBe(true)
  })
})
