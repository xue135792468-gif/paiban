import { expect, test, type Locator, type Page } from '@playwright/test'
import { newContext, pickOption, seedWorkbenchScenario, uiLogin, type WorkbenchSeed } from './helpers'
import { capture } from './screenshots'

/**
 * 票 03：工作台无障碍与 320px。
 * 1) 仅键盘（focus + Enter/Space，全程不点鼠标）走完一次跨科代课确认到成功态；
 * 2) aria：步骤条 aria-live 文本随步骤更新、课卡/动作卡/日期 chip 的 aria-pressed、
 *    跨科勾选有可读 label；
 * 3) 320×700：导航隐藏、步骤 1 与确认页变化可视化均切按天列表、页面无横向滚动。
 */

/** 按本地时区平移日期（种子日期都在未来一周内）。 */
function shiftDate(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00`)
  value.setDate(value.getDate() + days)
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const dayNo = String(value.getDate()).padStart(2, '0')
  return `${value.getFullYear()}-${month}-${dayNo}`
}

/** 键盘流公共步：focus + Enter 触发按钮（本 spec 禁用 mouse.click）。 */
async function pressButton(page: Page, testId: string): Promise<void> {
  const button = page.getByTestId(testId)
  await button.focus()
  await page.keyboard.press('Enter')
}

/** 键盘流公共步：focus + Space 勾选 radio/checkbox（原生键盘语义）。 */
async function pressCheck(page: Page, input: Locator): Promise<void> {
  await input.focus()
  await page.keyboard.press('Space')
}

/** 键盘选教师：focus 下拉输入 → 输入唯一名 → 键盘选中过滤行（全程无鼠标点击）。 */
async function keyboardPickTeacher(page: Page, seed: WorkbenchSeed): Promise<void> {
  const input = page.getByTestId('teacher-select').locator('input')
  await input.focus()
  await input.fill(seed.teacherName)
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(dropdown.getByText(seed.teacherName, { exact: true })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
}

/** document 与工作台内容区（.wb-scroll）都不得出现横向滚动。 */
async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => {
    const doc = document.documentElement
    const inner = document.querySelector<HTMLElement>('.wb-scroll')
    return {
      documentDelta: doc.scrollWidth - doc.clientWidth,
      innerDelta: inner ? inner.scrollWidth - inner.clientWidth : 0,
    }
  })
  expect(overflow.documentDelta, `document 横向溢出 ${overflow.documentDelta}px`).toBeLessThanOrEqual(1)
  expect(overflow.innerDelta, `工作台内容区横向溢出 ${overflow.innerDelta}px`).toBeLessThanOrEqual(1)
}

test('仅键盘完成一次代课确认：选课 → 动作卡 → 候选 → 跨科勾选 → 成功态', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()

  // 步骤 1：键盘选教师 + 填日期 + Enter 点课卡。
  await keyboardPickTeacher(page, seed)
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible({ timeout: 20_000 })

  const sourceCard = page.getByTestId(`workbench-cell-${seed.lessonDate}-1`).getByTestId('pick-lesson')
  await sourceCard.focus()
  await page.keyboard.press('Enter')
  await expect(sourceCard).toHaveAttribute('aria-pressed', 'true')

  // aria-live 容器：文本随步骤更新（步骤 1 → 2 → 3）。
  await expect(page.getByTestId('wizard-stepper')).toBeVisible()
  await expect(page.getByTestId('wizard-live')).toHaveText('第 1 步，共 3 步：选哪节课')

  await pressButton(page, 'wizard-next-1')
  await expect(page.getByTestId('wizard-panel-2')).toBeVisible()
  await expect(page.getByTestId('wizard-live')).toHaveText('第 2 步，共 3 步：怎么调')

  // 步骤 2：先用键盘试调课方向，验证动作卡与日期 chip 的 aria-pressed（选中互斥）。
  await expect(page.getByTestId('choose-cover')).toHaveAttribute('aria-pressed', 'false')
  await pressButton(page, 'choose-swap')
  await expect(page.getByTestId('choose-swap')).toHaveAttribute('aria-pressed', 'true')
  const thursdayChip = page.getByTestId(`swap-date-chip-${shiftDate(seed.lessonDate, 1)}`)
  await thursdayChip.focus()
  await page.keyboard.press('Space')
  await expect(thursdayChip).toHaveAttribute('aria-pressed', 'true')
  const wednesdayChip = page.getByTestId(`swap-date-chip-${seed.lessonDate}`)
  await wednesdayChip.focus()
  await page.keyboard.press('Space')
  await expect(wednesdayChip).toHaveAttribute('aria-pressed', 'true')
  await expect(thursdayChip).toHaveAttribute('aria-pressed', 'false')

  // 虚线切换链接同样键盘可达：切回代课方向继续。
  await pressButton(page, 'switch-cover')
  await expect(page.getByTestId('choose-cover')).toHaveAttribute('aria-pressed', 'true')

  // 候选 radio：focus + Space 勾选；原因填写；键盘进入第③步。
  const option = page.getByTestId(`substitute-option-${seed.substituteTeacherId}`).locator('input')
  await pressCheck(page, option)
  await expect(option).toBeChecked()
  await page.getByTestId('preview-reason').fill('键盘流程：任课教师临时有事')
  await pressButton(page, 'wizard-next-2')
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()
  await expect(page.getByTestId('wizard-live')).toHaveText('第 3 步，共 3 步：核对并提交')

  // 跨科门：checkbox 有可读 label（getByLabel 能定位），Space 勾选后提交解锁。
  const ack = page.getByLabel('我已知晓，可以跨科')
  await expect(ack).not.toBeChecked()
  await pressCheck(page, ack)
  await expect(ack).toBeChecked()
  await expect(page.getByTestId('personal-confirm')).toBeEnabled()

  // 亲自确认 → 成功态。
  await pressButton(page, 'personal-confirm')
  const result = page.getByTestId('workbench-result')
  await expect(result).toContainText('已生效')
  await expect(result).toContainText('课表已更新')
  await capture(page, 't03-01-keyboard-success')
})

test('320×700：导航隐藏、步骤 1 按天列表、无横向滚动', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 700 })
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()

  // 导航隐藏（≤900px 隐藏左侧导航）。
  await expect(page.getByTestId('layout-aside')).toBeHidden()

  await pickOption(page.locator('.picker-card'), 'teacher-select', seed.teacherName)
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible({ timeout: 20_000 })

  // 步骤 1 是按天列表（网格与列表二选一渲染，不是 CSS 隐藏）。
  await expect(page.locator('.wgrid')).toHaveCount(0)
  await expect(page.getByTestId('workbench-timetable')).toBeVisible()
  const items = page.getByTestId('pick-lesson')
  // 按天列表项按「周X M/D · 第N节 · 课程 · 教师」组织；种子周三第 1 节必有课卡。
  const wednesdayItem = items.filter({ hasText: '周三' }).first()
  await expect(wednesdayItem).toContainText('· 第1节 ·')

  // 页面无横向滚动（document 与工作台内容区两级都查）。
  await expectNoHorizontalOverflow(page)
  await page.getByTestId('wizard-panel-1').scrollIntoViewIfNeeded()
  await capture(page, 't03-02-320-step1')

  // 走到确认页（320px 下全程可操作）。
  await items.filter({ hasText: '周三' }).first().click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  await page.getByTestId(`substitute-option-${seed.substituteTeacherId}`).locator('input').check()
  await page.getByTestId('preview-reason').fill('320px 流程检查')
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()

  // 变化可视化切按天列表；同样无横向滚动。
  await expect(page.getByTestId('change-visual').locator('.wtab')).toHaveCount(0)
  const changeList = page.getByTestId('change-list')
  await expect(changeList).toBeVisible()
  await expect(changeList.locator('.wi')).toHaveCount(1)
  await expect(changeList).toContainText('→')
  await expectNoHorizontalOverflow(page)
  await page.getByTestId('change-visual').scrollIntoViewIfNeeded()
  await capture(page, 't03-03-320-change-list')
})
