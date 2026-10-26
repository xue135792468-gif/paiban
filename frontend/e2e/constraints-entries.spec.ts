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
 * 票 02 前端切片：教师禁排时段支持多条条目。
 * 断言只落在稳定可观察状态上 —— 条目卡片（data-testid）、顶部计数、左侧条目数。
 * 三个用例串行：后一条依赖前一条保存后的数据，像真实教务的操作顺序。
 */
test.describe.serial('约束配置：教师禁排多条条目', () => {
  const TEACHER_UNAVAILABLE = '[data-type="TEACHER_UNAVAILABLE"]'

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

  async function openTeacherUnavailable(page: Page) {
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await page.locator(TEACHER_UNAVAILABLE).click()
    await expect(page.getByTestId('constraint-panel-title')).toContainText('教师禁排时段')
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
    // The dropdown is teleported to <body>, so only the trigger is scoped to the card.
    const option = scope
      .page()
      .locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: text })
      .first()
    await expect(option).toBeVisible()
    await option.click()
  }

  async function addEntry(page: Page, teacherName: string, dayLabel: string, period: string) {
    await page.getByTestId('constraint-entry-add').click()
    const card = entryCards(page).last()
    await expect(card).toBeVisible()
    // Every field is scoped to the card just added: with two cards on screen the
    // bare testid would match both and Playwright's strict mode would refuse.
    await pickOption(card, 'constraint-entry-teacher', teacherName)
    await pickOption(card, 'constraint-entry-day', dayLabel)
    const periodInput = card.getByTestId('constraint-entry-period').locator('input')
    await periodInput.fill(period)
    await periodInput.press('Tab')
  }

  function summaryEntries(page: Page) {
    return page.getByTestId('constraint-summary-entries')
  }

  function navEntryCount(page: Page) {
    return page.locator(`${TEACHER_UNAVAILABLE} [data-testid="constraint-nav-count"]`)
  }

  async function expectCounts(page: Page, entries: number) {
    await expect(summaryEntries(page)).toContainText(`具体条目 ${entries} 条`)
    // 顶部计数与左侧条目数必须来自同一份数据。
    await expect(navEntryCount(page)).toHaveText(`${entries} 条`)
  }

  test('添加两位教师各一条禁排，保存后刷新仍在', async ({ page }) => {
    const [first, second] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await openTeacherUnavailable(page)
    await enableType(page)

    await addEntry(page, first.name, '周三', '2')
    await expect(entryCards(page)).toHaveCount(1)
    await expect(entryCards(page).first().getByTestId('constraint-entry-summary')).toContainText(
      `${first.name} · 周三 第2节`,
    )

    await addEntry(page, second.name, '周四', '5')
    await expect(entryCards(page)).toHaveCount(2)
    await expect(entryCards(page).nth(1).getByTestId('constraint-entry-summary')).toContainText(
      `${second.name} · 周四 第5节`,
    )
    await expectCounts(page, 2)

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    // 落库证据：接口里两条条目带着稳定 id。
    const token = await apiLogin(api)
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    const config = configs.find((item: any) => item.type === 'TEACHER_UNAVAILABLE')
    expect(config.enabled).toBe(true)
    expect(config.entries).toHaveLength(2)
    expect(config.entries.map((entry: any) => entry.params.teacherId)).toEqual([
      first.id,
      second.id,
    ])

    await page.reload()
    await page.locator(TEACHER_UNAVAILABLE).click()
    await expect(entryCards(page)).toHaveCount(2)
    await expect(entryCards(page).first().getByTestId('constraint-entry-summary')).toContainText(
      first.name,
    )
    await expect(entryCards(page).nth(1).getByTestId('constraint-entry-summary')).toContainText(
      second.name,
    )
    await expectCounts(page, 2)
  })

  test('删除其中一条后保存，刷新只剩一条', async ({ page }) => {
    const [first, second] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await openTeacherUnavailable(page)
    await expect(entryCards(page)).toHaveCount(2)

    const firstCard = entryCards(page).filter({ hasText: first.name })
    await firstCard.getByTestId('constraint-entry-remove').click()
    const confirm = page.locator('.el-message-box')
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '删除' }).click()
    await expect(entryCards(page)).toHaveCount(1)
    await expectCounts(page, 1)

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    await page.reload()
    await page.locator(TEACHER_UNAVAILABLE).click()
    await expect(entryCards(page)).toHaveCount(1)
    await expect(entryCards(page).first().getByTestId('constraint-entry-summary')).toContainText(
      second.name,
    )
    await expectCounts(page, 1)
  })

  test('顶部「具体条目」与左侧「条目数」始终一致，开关型不计数', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()

    // 已启用的开关型（教师/班级/教室不冲突）算类型、不算条目。首帧可能还没 hydrate
    // 完，所以轮询到两个数都稳定为非 0 再判定一致。
    const navCounts = () =>
      page.locator('[data-type] [data-testid="constraint-nav-count"]').allInnerTexts()
    const navEntriesOf = (texts: string[]) =>
      texts
        .filter((text) => text.endsWith(' 条'))
        .reduce((total, text) => total + Number.parseInt(text, 10), 0)

    await expect
      .poll(async () => {
        const entries = navEntriesOf(await navCounts())
        const summary = await summaryEntries(page).innerText()
        return summary.includes(`具体条目 ${entries} 条`) && entries > 0
      })
      .toBe(true)

    expect(navEntriesOf(await navCounts())).toBe(1)

    const enabledText = await page.getByTestId('constraint-summary-enabled').innerText()
    expect(enabledText).toMatch(/已启用 \d+ \/ 24 类/)

    // 开关型在左侧标注「开关」而不是 0 条。
    await expect(
      page.locator('[data-type="TEACHER_CONFLICT"] [data-testid="constraint-nav-count"]'),
    ).toHaveText('开关')
  })

  test('重复条目保存被拒：错误落在第 2 条，输入原样保留', async ({ page }) => {
    const [, second] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await openTeacherUnavailable(page)
    await expect(entryCards(page)).toHaveCount(1)

    // 第一条是测试 2 留下的「李娜 · 周四 第5节」，再录一条一模一样的。
    await addEntry(page, second.name, '周四', '5')
    await expect(entryCards(page)).toHaveCount(2)
    await expect(entryCards(page).nth(1).getByTestId('constraint-entry-duplicate')).toContainText(
      '与第 1 条完全相同',
    )

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-save-error')).toBeVisible()
    // 服务端按「第N 条」定位，页面把这条错误放回对应卡片。
    await expect(entryCards(page).nth(1).getByTestId('constraint-entry-error')).toContainText(
      '重复条目已拒绝',
    )
    await expect(entryCards(page)).toHaveCount(2)
    await expect(page.getByTestId('constraint-unsaved')).toBeVisible()
    await expectCounts(page, 2)
  })
})
