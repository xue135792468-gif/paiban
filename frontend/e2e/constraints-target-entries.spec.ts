import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
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
 * 票 03 前端切片：其余十二类目标型约束支持多条条目，并给「全部课程 / 全部学科 /
 * 体育类」一个显式入口。断言只落在条目卡片（data-testid 按卡片作用域）、顶部计数与
 * 接口返回上，不依赖组件内部结构。
 *
 * 四个用例串行、共用同一学期：每个用例保存后都刷新，像真实教务反复编辑的顺序。
 */
test.describe.serial('约束配置：目标型约束多条目', () => {
  const NOT_IN_EVENING = '[data-type="COURSE_NOT_IN_EVENING"]'
  const ONCE_PER_DAY = '[data-type="SAME_COURSE_ONCE_PER_DAY"]'
  const PREFERRED_SLOT = '[data-type="TEACHER_PREFERRED_SLOT"]'
  const ROOM_TYPE = '[data-type="COURSE_REQUIRED_ROOM_TYPE"]'

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

  async function openType(page: Page, selector: string, title: string) {
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    await page.locator(selector).click()
    await expect(page.getByTestId('constraint-panel-title')).toContainText(title)
  }

  async function enableType(page: Page) {
    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) {
      await toggle.click()
    }
    await expect(toggle).toHaveClass(/is-checked/)
  }

  async function pickOption(scope: Locator, field: string, text: string) {
    const trigger = scope.getByTestId(field)
    await trigger.click()
    // 下拉被 teleport 到 <body>：用触发器 input 的 aria-controls 锁定「这一个」下拉，
    // 避免多条条目并存时命中上一张卡片残留的下拉。
    const controls = await trigger.locator('input').getAttribute('aria-controls')
    const dropdown = scope.page().locator(`#${controls}`)
    await expect(dropdown).toBeVisible()
    const option = dropdown.locator('.el-select-dropdown__item').filter({ hasText: text }).first()
    await expect(option).toBeVisible()
    await option.click()
    await expect(dropdown).toBeHidden()
  }

  async function addCard(page: Page) {
    await page.getByTestId('constraint-entry-add').click()
    const card = entryCards(page).last()
    await expect(card).toBeVisible()
    return card
  }

  function navCount(page: Page, selector: string) {
    return page.locator(`${selector} [data-testid="constraint-nav-count"]`)
  }

  /**
   * 该类型的条目数（左侧导航）与顶部总数必须来自同一份数据：总数＝各类导航条目数之和。
   * 顶部是整学期口径（本 spec 的四个类型共用同一学期），所以按「和」断言而不是写死数字。
   */
  async function expectEntryCount(page: Page, selector: string, entries: number) {
    await expect(navCount(page, selector)).toHaveText(`${entries} 条`)
    await expect
      .poll(async () => {
        const texts = await page
          .locator('[data-type] [data-testid="constraint-nav-count"]')
          .allInnerTexts()
        const total = texts
          .filter((text) => text.endsWith(' 条'))
          .reduce((sum, text) => sum + Number.parseInt(text, 10), 0)
        const summary = await page.getByTestId('constraint-summary-entries').innerText()
        return summary.includes(`具体条目 ${total} 条`)
      })
      .toBe(true)
  }

  async function saveAndWait(page: Page) {
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
  }

  async function configOf(type: string) {
    const token = await apiLogin(api)
    const configs = await apiCall(api, token, 'get', '/constraints', undefined, {
      semesterId: seed.semesterId,
    })
    return configs.find((item: any) => item.type === type)
  }

  test('两门课程各加一条「不排晚自习」，保存后两条都在且重复保存不增删', async ({ page }) => {
    const [math, chinese] = seed.courses

    await uiLogin(page, 'admin', 'admin123')
    await openType(page, NOT_IN_EVENING, '课程不排晚自习')
    await enableType(page)

    const first = await addCard(page)
    await pickOption(first, 'constraint-entry-course', math.name)
    await expect(first.getByTestId('constraint-entry-summary')).toHaveText(math.name)

    const second = await addCard(page)
    await pickOption(second, 'constraint-entry-course', chinese.name)
    await expect(second.getByTestId('constraint-entry-summary')).toHaveText(chinese.name)

    await expect(entryCards(page)).toHaveCount(2)
    await expectEntryCount(page, NOT_IN_EVENING, 2)

    await saveAndWait(page)

    const saved = await configOf('COURSE_NOT_IN_EVENING')
    expect(saved.enabled).toBe(true)
    expect(saved.entries).toHaveLength(2)
    expect(saved.entries.map((entry: any) => entry.params.courseId)).toEqual([
      math.id,
      chinese.id,
    ])

    // 刷新后两条都在，且没有任何未保存改动。
    await page.reload()
    await page.locator(NOT_IN_EVENING).click()
    await expect(entryCards(page)).toHaveCount(2)
    await expect(entryCards(page).nth(0).getByTestId('constraint-entry-summary')).toHaveText(
      math.name,
    )
    await expect(entryCards(page).nth(1).getByTestId('constraint-entry-summary')).toHaveText(
      chinese.name,
    )
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)

    // 幂等：原样再保存一次，条目数量不变。
    await saveAndWait(page)
    const again = await configOf('COURSE_NOT_IN_EVENING')
    expect(again.entries).toHaveLength(2)
  })

  test('同课每日一次加一条显式「全部课程」，刷新后仍显示全部课程', async ({ page }) => {
    await uiLogin(page, 'admin', 'admin123')
    await openType(page, ONCE_PER_DAY, '同一课程一天最多一节')
    await enableType(page)

    const card = await addCard(page)
    // 「全部」是显式勾选，不是留空输入框。
    const allTarget = card.getByTestId('constraint-entry-all-target')
    await expect(allTarget).toContainText('适用于全部课程')
    await expect(allTarget.locator('input')).toBeChecked()
    await expect(card.getByTestId('constraint-entry-summary')).toHaveText('全部课程')
    await expectEntryCount(page, ONCE_PER_DAY, 1)

    await saveAndWait(page)

    const saved = await configOf('SAME_COURSE_ONCE_PER_DAY')
    expect(saved.entries).toHaveLength(1)
    expect(saved.entries[0].params).toEqual({})

    await page.reload()
    await page.locator(ONCE_PER_DAY).click()
    await expect(entryCards(page)).toHaveCount(1)
    const reloaded = entryCards(page).first()
    await expect(reloaded.getByTestId('constraint-entry-summary')).toHaveText('全部课程')
    await expect(reloaded.getByTestId('constraint-entry-all-target').locator('input')).toBeChecked()
    await expectEntryCount(page, ONCE_PER_DAY, 1)
  })

  test('同一教师偏好时段一条里加两个时段，摘要保持「或」', async ({ page }) => {
    const [teacher] = seed.teachers

    await uiLogin(page, 'admin', 'admin123')
    await openType(page, PREFERRED_SLOT, '教师偏好时段')
    await enableType(page)

    const card = await addCard(page)
    await pickOption(card, 'constraint-entry-teacher', teacher.name)

    const slots = card.getByTestId('constraint-entry-slot')
    await expect(slots).toHaveCount(1)
    await pickOption(slots.nth(0), 'constraint-entry-slot-day', '周一')
    const firstPeriod = slots.nth(0).getByTestId('constraint-entry-slot-period').locator('input')
    await firstPeriod.fill('1')
    await firstPeriod.press('Tab')

    await card.getByTestId('constraint-entry-slot-add').click()
    await expect(slots).toHaveCount(2)
    await pickOption(slots.nth(1), 'constraint-entry-slot-day', '周三')
    const secondPeriod = slots.nth(1).getByTestId('constraint-entry-slot-period').locator('input')
    await secondPeriod.fill('2')
    await secondPeriod.press('Tab')

    const expected = `${teacher.name} · 周一第1节 或 周三第2节（命中任一即满足）`
    await expect(card.getByTestId('constraint-entry-summary')).toHaveText(expected)
    // 一条卡片 = 两个后端条目（后端一条条目只放一个时段）。
    await expectEntryCount(page, PREFERRED_SLOT, 2)

    await saveAndWait(page)

    const saved = await configOf('TEACHER_PREFERRED_SLOT')
    expect(saved.enabled).toBe(true)
    expect(saved.entries).toHaveLength(2)
    expect(saved.entries.map((entry: any) => entry.params.teacherId)).toEqual([
      teacher.id,
      teacher.id,
    ])

    await page.reload()
    await page.locator(PREFERRED_SLOT).click()
    await expect(entryCards(page)).toHaveCount(1)
    await expect(entryCards(page).first().getByTestId('constraint-entry-summary')).toHaveText(
      expected,
    )
    await expect(
      entryCards(page).first().getByTestId('constraint-entry-slot'),
    ).toHaveCount(2)
  })

  test('同一课程两个教室类型：保存被拒，错误落在对应条目，不产生部分写入', async ({ page }) => {
    const [math] = seed.courses

    await uiLogin(page, 'admin', 'admin123')
    await openType(page, ROOM_TYPE, '课程指定教室类型')
    await enableType(page)

    const first = await addCard(page)
    await pickOption(first, 'constraint-entry-course', math.name)
    await pickOption(first, 'constraint-entry-room-type', '实验室')

    const second = await addCard(page)
    await pickOption(second, 'constraint-entry-course', math.name)
    await pickOption(second, 'constraint-entry-room-type', '普通教室')

    await page.getByTestId('constraint-save').click()

    // 保存前预检给出明确提示，并且没有发出请求：输入原样保留。
    const precheck = page.getByTestId('constraint-precheck-error')
    await expect(precheck).toBeVisible()
    await expect(precheck).toContainText('课程指定教室类型')
    await expect(precheck).toContainText(math.name)
    await expect(precheck).toContainText('教室类型')
    await expect(page.getByTestId('constraint-unsaved')).toBeVisible()
    await expect(entryCards(page)).toHaveCount(2)
    // 冲突也落在两张卡片上，用户知道该改哪一条。
    await expect(entryCards(page).nth(0).getByTestId('constraint-entry-conflict')).toContainText(
      `课程「${math.name}」`,
    )
    await expect(entryCards(page).nth(1).getByTestId('constraint-entry-conflict')).toContainText(
      '教室类型',
    )

    const saved = await configOf('COURSE_REQUIRED_ROOM_TYPE')
    expect(saved?.entries ?? []).toHaveLength(0)
  })

  test('十二类目标型逐一打开并截图（人工复核用，不作断言）', async ({ page }) => {
    const types: Array<[string, string]> = [
      ['TEACHER_MEETING_BLOCKED', '教研活动禁排'],
      ['COURSE_REQUIRED_ROOM_TYPE', '课程指定教室类型'],
      ['COURSE_NOT_IN_EVENING', '课程不排晚自习'],
      ['SAME_COURSE_ONCE_PER_DAY', '同一课程一天最多一节'],
      ['SPORT_NOT_FIRST_AFTERNOON', '体育不在下午第一节'],
      ['CONSECUTIVE_LESSONS', '连堂课连排'],
      ['COURSE_SPREAD', '同一课程分散到不同天'],
      ['MAIN_SUBJECT_MORNING', '主科尽量排上午'],
      ['TEACHER_PREFERRED_SLOT', '教师偏好时段'],
      ['TEACHER_NO_FIRST_PERIOD', '教师不排第一节'],
      ['TEACHER_DAY_OFF', '教师固定无课日'],
      ['PE_LAST_PERIOD_AVOID', '体育不在最后一节'],
    ]
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await expect(page.getByTestId('constraint-nav')).toBeVisible()
    for (const [code, title] of types) {
      const navItem = page.locator(`[data-type="${code}"]`)
      // 初中模式隐藏教研活动禁排（ADR-0018），那一类没有入口可截。
      if ((await navItem.count()) === 0) {
        continue
      }
      await navItem.click()
      await expect(page.getByTestId('constraint-panel-title')).toContainText(title)
      await capture(page, `constraint-entry-${code}`)
    }
  })
})
