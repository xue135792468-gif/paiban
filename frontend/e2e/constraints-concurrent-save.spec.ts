import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  seedConstraintScenario,
  uiLogin,
  type ConstraintSeed,
  pickOption,
} from './helpers'

/**
 * Ticket 14/07: two browsers based on the same configuration version. The second save
 * must not silently overwrite the first; the page keeps the user's work, shows the
 * conflict, and only a reviewed choice writes again.
 */
test.describe.serial('约束配置：两人同时保存', () => {
  let api: APIRequestContext
  let seed: ConstraintSeed

  test.beforeAll(async () => {
    api = await newContext()
    seed = await seedConstraintScenario(api)
  })

  test.afterAll(async () => {
    await api.dispose()
  })

  async function openPage(browser: Browser): Promise<Page> {
    const context = await browser.newContext()
    const page = await context.newPage()
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    return page
  }


  async function enableType(page: Page) {
    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) {
      await toggle.click()
    }
    await expect(toggle).toHaveClass(/is-checked/)
  }

  async function configs() {
    const token = await apiLogin(api)
    return apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
  }

  test('后保存者先看到冲突；合并后两人的类型都在', async ({ browser }) => {
    const [first, second] = seed.teachers
    const pageA = await openPage(browser)
    const pageB = await openPage(browser)

    // A: teacher 1 has Wednesday off.
    await pageA.locator('[data-type="TEACHER_DAY_OFF"]').click()
    await enableType(pageA)
    await pageA.getByTestId('constraint-entry-add').click()
    const cardA = pageA.getByTestId('constraint-entry').first()
    await pickOption(cardA, 'constraint-entry-teacher', first.name)
    await pickOption(cardA, 'constraint-entry-day', '周三')
    await pageA.getByTestId('constraint-save').click()
    await expect(pageA.getByTestId('constraint-unsaved')).toHaveCount(0)
    await pageA.context().close()

    // B was loaded before A saved: its save is based on the old version.
    await pageB.locator('[data-type="TEACHER_NO_FIRST_PERIOD"]').click()
    await enableType(pageB)
    await pageB.getByTestId('constraint-entry-add').click()
    const cardB = pageB.getByTestId('constraint-entry').first()
    await pickOption(cardB, 'constraint-entry-teacher', second.name)
    await pageB.getByTestId('constraint-save').click()

    await expect(pageB.getByTestId('constraint-conflict')).toBeVisible()
    await expect(pageB.getByTestId('constraint-conflict')).toContainText('已被他人修改')
    // B's own work is still on the page and still unsaved.
    await expect(cardB.getByTestId('constraint-entry-summary')).toContainText(second.name)
    await expect(pageB.getByTestId('constraint-unsaved')).toBeVisible()

    await pageB.getByTestId('constraint-conflict-keep-mine').click()
    await expect(pageB.getByTestId('constraint-unsaved')).toHaveCount(0)

    const saved = await configs()
    const types = saved.map((config: any) => config.type)
    expect(types).toContain('TEACHER_DAY_OFF')
    expect(types).toContain('TEACHER_NO_FIRST_PERIOD')
    const dayOff = saved.find((config: any) => config.type === 'TEACHER_DAY_OFF')
    expect(dayOff.entries[0].params.teacherId).toBe(first.id)
    const noFirst = saved.find((config: any) => config.type === 'TEACHER_NO_FIRST_PERIOD')
    expect(noFirst.entries[0].params.teacherId).toBe(second.id)

    await pageB.context().close()
  })

  test('选择放弃本地修改时加载最新配置，不写回旧内容', async ({ browser }) => {
    const [first, second] = seed.teachers
    const pageA = await openPage(browser)
    const pageB = await openPage(browser)

    // B edits first but does not save yet.
    await pageB.locator('[data-type="TEACHER_PREFERRED_SLOT"]').click()
    await enableType(pageB)
    await pageB.getByTestId('constraint-entry-add').click()
    const cardB = pageB.getByTestId('constraint-entry').first()
    await pickOption(cardB, 'constraint-entry-teacher', second.name)
    await pickOption(cardB, 'constraint-entry-slot-day', '周四')
    await pickOption(cardB, 'constraint-entry-slot-period', '第2节')

    // A saves another type meanwhile.
    await pageA.locator('[data-type="TEACHER_DAY_OFF"]').click()
    await enableType(pageA)
    await pageA.getByTestId('constraint-entry-add').click()
    const cardA = pageA.getByTestId('constraint-entry').first()
    await pickOption(cardA, 'constraint-entry-teacher', first.name)
    await pickOption(cardA, 'constraint-entry-day', '周五')
    await pageA.getByTestId('constraint-save').click()
    await expect(pageA.getByTestId('constraint-unsaved')).toHaveCount(0)
    await pageA.context().close()

    await pageB.getByTestId('constraint-save').click()
    await expect(pageB.getByTestId('constraint-conflict')).toBeVisible()
    await pageB.getByTestId('constraint-conflict-take-theirs').click()
    await expect(pageB.getByTestId('constraint-conflict')).toHaveCount(0)
    await expect(pageB.getByTestId('constraint-unsaved')).toHaveCount(0)

    // The abandoned preference is gone; A's saved requirement is what the page shows.
    await pageB.locator('[data-type="TEACHER_PREFERRED_SLOT"]').click()
    await expect(pageB.getByTestId('constraint-entry')).toHaveCount(0)
    const saved = await configs()
    expect(saved.find((config: any) => config.type === 'TEACHER_PREFERRED_SLOT')).toBeUndefined()
    expect(saved.find((config: any) => config.type === 'TEACHER_DAY_OFF')).toBeTruthy()

    await pageB.context().close()
  })
})
