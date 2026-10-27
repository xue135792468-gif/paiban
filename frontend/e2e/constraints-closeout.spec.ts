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
 * 票 06 收尾：主从布局下的页面状态验收与截图证据。
 *
 * 与前三张前端票据的区别只在覆盖面：这里不重复「某类怎么编辑」，而是把用户确认的
 * 变体 B 布局（左侧类型导航 + 右侧面板 + 顶部吸顶汇总带）的每一种页面状态各走一遍，
 * 并把截图留给人工复核。断言仍然只落在稳定可观察状态上：顶部三个计数、左侧每类
 * 条目数、条目卡片、阈值面板标签、保存错误定位。
 *
 * 截图目录由 PAIBAN_E2E_SCREENSHOTS 决定（默认不在仓库内），本票使用
 * E:/paiban-wt/screenshots/ticket06。
 */
test.describe.serial('约束配置：收尾页面状态与证据', () => {
  const TEACHER_UNAVAILABLE = '[data-type="TEACHER_UNAVAILABLE"]'
  const TEACHER_MAX = '[data-type="TEACHER_MAX_PER_DAY"]'
  const templateName = `收尾模板-${Math.random().toString(36).slice(2, 8)}`

  let api: APIRequestContext
  let token: string
  let seed: ConstraintSeed

  /** 不同用例之间的页面数据基线：三条禁排 +（部分用例）阈值。 */
  async function saveEntries(extra: unknown[] = []) {
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
        ...extra,
      ],
    })
  }

  test.beforeAll(async () => {
    api = await newContext()
    token = await apiLogin(api)
    // 模板区与权重是中学段视图（ADR-0018）；收尾用例都按启用它的视图执行。
    await apiCall(api, token, 'put', '/settings', { 'school.type': 'senior' })
    seed = await seedConstraintScenario(api)
    await saveEntries()
  })

  test.afterAll(async () => {
    await apiCall(api, token, 'put', '/settings', { 'school.type': 'junior' }).catch(() => undefined)
    await api.dispose()
  })

  function entryCards(page: Page) {
    return page.getByTestId('constraint-entry')
  }

  function summaryEntries(page: Page) {
    return page.getByTestId('constraint-summary-entries')
  }

  function navCount(page: Page, type = TEACHER_UNAVAILABLE) {
    return page.locator(`${type} [data-testid="constraint-nav-count"]`)
  }

  /** 左侧每类「N 条」相加，用作顶部「具体条目」的同源校验。 */
  async function navEntryTotal(page: Page): Promise<number> {
    const texts = await page
      .locator('[data-type] [data-testid="constraint-nav-count"]')
      .allInnerTexts()
    return texts
      .filter((text) => text.endsWith(' 条'))
      .reduce((total, text) => total + Number.parseInt(text, 10), 0)
  }

  /** The dialog of the moment; closed message boxes linger in the DOM. */
  function messageBox(page: Page) {
    return page.locator('.el-message-box:visible')
  }

  test('默认页：左侧四组导航 + 顶部三个计数 + 右侧面板，计数同源', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()

    // 分组体现在左侧导航的分区标题上（变体 B），不再有折叠卡片。
    await expect(page.getByTestId('constraint-nav-group')).toHaveCount(4)
    await expect(page.getByTestId('constraint-nav-group').first()).toContainText('冲突类')

    await expect(page.getByTestId('constraint-summary-enabled')).toContainText(/已启用 \d+ \/ 25 类/)
    await expect(summaryEntries(page)).toContainText('具体条目 3 条')
    // 顶部条目数＝左侧每类条目数之和，两者出自同一份草稿状态。
    expect(await navEntryTotal(page)).toBe(3)
    await expect(navCount(page)).toHaveText('3 条')

    // 开关型显示「开关」而不是条目数。
    await expect(navCount(page, '[data-type="TEACHER_CONFLICT"]')).toHaveText('开关')
    await expect(page.getByTestId('constraint-panel-title')).toContainText('教师禁排时段')

    await capture(page, 'default')
  })

  test('长列表：一类 21 条仍逐条渲染，计数同步', async ({ page }) => {
    const [first, second] = seed.teachers
    const entries = []
    for (let index = 0; index < 21; index += 1) {
      entries.push({
        params: {
          teacherId: index % 2 === 0 ? first.id : second.id,
          dayOfWeek: (index % 5) + 1,
          periodNo: Math.floor(index / 5) + 1,
        },
      })
    }
    await apiCall(api, token, 'put', `/constraints?semesterId=${seed.semesterId}`, {
      configs: [
        { type: 'TEACHER_UNAVAILABLE', weight: 100, hard: true, enabled: true, entries },
      ],
    })

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await page.locator(TEACHER_UNAVAILABLE).click()

    await expect(entryCards(page)).toHaveCount(21)
    await expect(summaryEntries(page)).toContainText('具体条目 21 条')
    await expect(navCount(page)).toHaveText('21 条')
    expect(await navEntryTotal(page)).toBe(21)

    await capture(page, 'long-list')
  })

  test('阈值：全局默认一行 + 对象例外，例外标注被覆盖值', async ({ page }) => {
    const [teacher] = seed.teachers
    await saveEntries([
      {
        type: 'TEACHER_MAX_PER_DAY',
        weight: 60,
        hard: false,
        enabled: true,
        entries: [
          { params: { maxPerDay: 6 } },
          { params: { teacherId: teacher.id, maxPerDay: 3 } },
        ],
      },
    ])

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await page.locator(TEACHER_MAX).click()

    await expect(
      page.getByTestId('constraint-threshold-global-value').locator('input'),
    ).toHaveValue('6')
    const exception = page.getByTestId('constraint-threshold-exception').first()
    await expect(exception.getByTestId('constraint-threshold-override')).toHaveText('覆盖全局 6')
    await expect(exception.getByTestId('constraint-threshold-value').locator('input')).toHaveValue('3')
    // 全局默认与例外各算一条，顶部与左侧一致。
    await expect(navCount(page, TEACHER_MAX)).toHaveText('2 条')
    await expect(summaryEntries(page)).toContainText('具体条目 5 条')

    await capture(page, 'threshold-global-exception')
  })

  test('模板往返：删条目保存后套用模板，条目与计数完整恢复', async ({ page }) => {
    await saveEntries()

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(summaryEntries(page)).toContainText('具体条目 3 条')

    await page.getByTestId('constraint-template-save').click()
    const prompt = messageBox(page)
    await expect(prompt).toBeVisible()
    await prompt.getByPlaceholder('例如：标准学期模板').fill(templateName)
    await prompt.getByRole('button', { name: '确定' }).click()

    // 删一条并保存：计数降到 2。
    await page.locator(TEACHER_UNAVAILABLE).click()
    await expect(entryCards(page)).toHaveCount(3)
    await entryCards(page).first().getByTestId('constraint-entry-remove').click()
    const confirm = messageBox(page)
    await expect(confirm).toBeVisible()
    await confirm.getByRole('button', { name: '删除' }).click()
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
    await expect(summaryEntries(page)).toContainText('具体条目 2 条')

    // 套用模板：重新拉取后条目与计数一起回来。
    await page.getByTestId('constraint-template-select').click()
    const option = page
      .locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: templateName })
      .first()
    await expect(option).toBeVisible()
    await option.click()
    await page.getByTestId('constraint-template-apply').click()
    const applyConfirm = messageBox(page)
    await expect(applyConfirm).toBeVisible()
    await applyConfirm.getByRole('button', { name: '确定' }).click()

    await expect(summaryEntries(page)).toContainText('具体条目 3 条')
    await expect(entryCards(page)).toHaveCount(3)
    expect(await navEntryTotal(page)).toBe(3)
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    await capture(page, 'template-roundtrip')
  })

  test('保存失败定位：重复条目落在第 2 条，导航红点提示', async ({ page }) => {
    const [first] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await page.locator(TEACHER_UNAVAILABLE).click()
    await expect(entryCards(page)).toHaveCount(3)

    // 复制第一条：页面在保存前就认出重复，类型在左侧亮红点。
    await page.getByTestId('constraint-entry-add').click()
    const card = entryCards(page).nth(3)
    await expect(card).toBeVisible()
    const teacherTrigger = card.getByTestId('constraint-entry-teacher')
    await teacherTrigger.click()
    const dropdown = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
    await dropdown.filter({ hasText: first.name }).first().click()
    const slot = card.getByTestId('constraint-entry-slot').first()
    await slot.getByTestId('constraint-entry-slot-day').click()
    await page
      .locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: '周一' })
      .first()
      .click()
    const periodInput = slot.getByTestId('constraint-entry-slot-period').locator('input')
    await periodInput.fill('1')
    await periodInput.press('Tab')

    await expect(card.getByTestId('constraint-entry-duplicate')).toContainText('与第 1 条完全相同')
    // 新增：冲突在保存前就让左侧该类型亮起红点。
    await expect(
      page.locator(`${TEACHER_UNAVAILABLE} [data-testid="constraint-nav-dot"]`),
    ).toHaveAttribute('data-state', 'error')

    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-save-error')).toBeVisible()
    await expect(card.getByTestId('constraint-entry-error')).toContainText('重复条目已拒绝')
    await expect(page.getByTestId('constraint-unsaved')).toBeVisible()

    await capture(page, 'save-failure')
  })
})
