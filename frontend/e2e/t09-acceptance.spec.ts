import { expect, test } from '@playwright/test'
import { capture } from './screenshots'
import { newContext, pickOption, seedWorkbenchScenario, uiLogin } from './helpers'

/**
 * 票 09 收尾验收：帮助文档可见性、调代工作台交互与关键页面截图。
 *
 * 前票的 Playwright（admin-substitute / admin-swap / admin-swap-drag /
 * admin-reschedule / substitution / global-scroll）继续覆盖各自流程；
 * 本 spec 只补帮助文档与工作台视觉，并留档截图。
 *
 * 票 01（钉钉多用户）下线了班主任身份：「本班课表 / 本班调代」两个权限点还留在
 * 权限目录里，但不再分配给任何预置角色，也就不存在能登录的班主任账号，
 * 原先那条「班主任只能看到自己授权班级」的 UI 用例随之退役。
 */

test('帮助文档：管理员调代工作台标题、四主题小节与锚点跳转', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')

  await page.goto('/help/substitution-workbench')
  await expect(page.getByTestId('help-doc-title')).toHaveText('调代工作台')
  // 目录树注册了新文档（与既有文档同一导航方式）。
  await expect(
    page.getByTestId('help-sidebar').getByRole('link', { name: '调代工作台' }),
  ).toBeVisible()

  const content = page.getByTestId('help-content')
  // 票据 1-4 条各对应一个小节。
  await expect(content.getByRole('heading', { name: '只改所选日期那一节' })).toBeVisible()
  await expect(content.getByRole('heading', { name: '五种操作怎么选' })).toBeVisible()
  await expect(content.getByRole('heading', { name: '亲自确认生效' })).toBeVisible()
  await expect(content.getByRole('heading', { name: '左右对照与变化清单' })).toBeVisible()
  // 关键句（一次性日期语义与开课截止）。
  await expect(
    content.getByText('下周同一节次自动恢复常规课表', { exact: false }).first(),
  ).toBeVisible()
  await expect(content.getByText('开课后不能再改', { exact: false }).first()).toBeVisible()

  // 锚点跳转：#compare 小节正文进入视口（票 08 的帮助页页内定位行为不被破坏）。
  await page.goto('/help/substitution-workbench#compare')
  await expect(
    content.getByText('旧安排画删除线', { exact: false }).first(),
  ).toBeInViewport()
  const windowY = await page.evaluate(
    () => document.scrollingElement?.scrollTop ?? document.documentElement.scrollTop,
  )
  expect(windowY).toBeGreaterThan(0)
})

test('关键页面留档截图：工作台、预览对照、帮助文档', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)
  await uiLogin(page, 'admin', 'admin123')

  // 1) 管理员调代工作台：三步向导步骤 1（教师搜索 + 实际日期课表）。
  await page.goto('/substitution')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await expect(page.getByTestId('wizard-stepper')).toBeVisible()
  await pickOption(page.locator('.picker-card'), 'teacher-select', seed.teacherName)
  await expect(page.getByTestId('workbench-timetable')).toBeVisible({ timeout: 20_000 })
  await capture(page, 't09-01-workbench')

  // 2) 指定代课 → 第③步「课表怎么变」变化格 + 折叠明细。
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()
  await page.getByTestId('pick-lesson').first().click()
  await page.getByTestId('wizard-next-1').click()
  await page.getByTestId('choose-cover').click()
  await page.getByTestId(`substitute-option-${seed.substituteTeacherId}`).locator('input').check()
  await page.getByTestId('preview-reason').fill('任课教师临时有事，管理员代发')
  await page.getByTestId('wizard-next-2').click()
  await expect(page.getByTestId('confirm-conclusion')).toBeVisible()
  await expect(page.getByTestId('change-visual')).toBeVisible()
  await capture(page, 't09-02-preview-compare')

  // 3) 帮助文档。
  await page.goto('/help/substitution-workbench')
  await expect(page.getByTestId('help-doc-title')).toHaveText('调代工作台')
  await capture(page, 't09-03-help-doc')
})
