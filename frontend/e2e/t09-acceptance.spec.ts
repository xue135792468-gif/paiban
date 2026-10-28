import { expect, test } from '@playwright/test'
import { capture } from './screenshots'
import {
  apiCall,
  apiLogin,
  newContext,
  pickOption,
  seedSchool,
  seedWorkbenchScenario,
  solveAndPublish,
  uiLogin,
} from './helpers'

/**
 * 票 09 收尾验收：帮助文档可见性、班主任数据范围的 UI 断言与关键页面截图。
 *
 * 前票的 Playwright（admin-substitute / admin-swap / admin-swap-drag /
 * admin-reschedule / substitution / substitution-conversion / global-scroll）
 * 继续覆盖各自流程；本 spec 只补此前只有 HTTP 断言的班主任范围，并留档截图。
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
  await expect(content.getByRole('heading', { name: '提交审批还是亲自确认' })).toBeVisible()
  await expect(content.getByRole('heading', { name: '左右对照与变化清单' })).toBeVisible()
  // 关键句（一次性日期语义与审批/确认差别）。
  await expect(
    content.getByText('下周同一节次自动恢复常规课表', { exact: false }).first(),
  ).toBeVisible()
  await expect(content.getByText('开课后不能再改', { exact: false }).first()).toBeVisible()

  // 锚点跳转：#compare 小节正文进入视口（票 08 的帮助页页内定位行为不被破坏）。
  await page.goto('/help/substitution-workbench#compare')
  await expect(
    content.getByText('左侧面板是本次调整前的最新安排', { exact: false }).first(),
  ).toBeInViewport()
  const windowY = await page.evaluate(
    () => document.scrollingElement?.scrollTop ?? document.documentElement.scrollTop,
  )
  expect(windowY).toBeGreaterThan(0)
})

test('班主任只能看到自己授权班级的调代记录', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  await solveAndPublish(api, seed)
  const token = await apiLogin(api)

  // 同年级再造一个班（未授权给班主任），并把李老师设为 seed 班级的班主任。
  const classes = await apiCall(api, token, 'get', '/classes', undefined, {
    semesterId: seed.semesterId,
  })
  const own = classes.find((row: any) => row.id === seed.classId)
  const otherName = `高一2班-${Math.random().toString(36).slice(2, 8)}`
  await apiCall(api, token, 'post', `/classes?semesterId=${seed.semesterId}`, {
    gradeId: own.gradeId,
    name: otherName,
    studentCount: 45,
  })
  await apiCall(api, token, 'put', `/classes/${seed.classId}`, {
    gradeId: own.gradeId,
    name: own.name,
    headTeacherId: seed.substituteTeacherId,
    studentCount: own.studentCount,
  })
  const username = `head_${Math.random().toString(36).slice(2, 8)}`
  await apiCall(api, token, 'post', '/users', {
    username,
    password: 'teacher123',
    displayName: '班主任',
    roleCodes: ['head_teacher'],
    teacherId: seed.substituteTeacherId,
  })

  await uiLogin(page, username, 'teacher123')
  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '调代记录' }).click()

  // 班级筛选默认锁在自己的班；下拉里看不到未授权班级。
  // （el-select 的占位符是 span 不是 input 属性，改用可见页签里第一个下拉定位。）
  const classSelect = page.locator('.el-tab-pane:visible .el-select').first()
  await expect(classSelect).toBeVisible()
  await classSelect.click()
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  await expect(dropdown.getByText(own.name, { exact: true })).toBeVisible()
  await expect(dropdown.getByText(otherName)).toHaveCount(0)
})

test('关键页面留档截图：工作台、预览对照、历史转换面板、帮助文档', async ({ page }) => {
  const api = await newContext()
  const seed = await seedWorkbenchScenario(api)
  await uiLogin(page, 'admin', 'admin123')

  // 1) 管理员调代工作台：教师搜索 + 实际日期课表。
  await page.goto('/substitutions/workbench')
  await expect(page.getByTestId('teacher-select')).toBeVisible()
  await pickOption(page.locator('.picker-card'), 'teacher-select', seed.teacherName)
  await expect(page.getByTestId('workbench-timetable')).toBeVisible({ timeout: 20_000 })
  await capture(page, 't09-01-workbench')

  // 2) 指定代课 → 左右对照 + 变化清单。
  const dateInput = page.getByTestId('lesson-date').locator('input')
  await dateInput.fill(seed.lessonDate)
  await dateInput.press('Tab')
  await expect(page.getByTestId('pick-lesson').first()).toBeVisible()
  await page.getByTestId('pick-lesson').first().click()
  await page.getByTestId('choose-cover').click()
  await page.getByTestId(`choose-${seed.substituteTeacherId}`).click()
  await page.getByTestId('preview-reason').fill('任课教师临时有事，管理员代发')
  await page.getByTestId('generate-preview').click()
  await expect(page.getByTestId('compare-panels')).toBeVisible()
  await expect(page.getByTestId('change-list')).toBeVisible()
  await capture(page, 't09-02-preview-compare')

  // 3) 历史转换面板（票 02）。
  await page.goto('/substitutions')
  await page.getByRole('tab', { name: '历史转换' }).click()
  await expect(page.getByRole('button', { name: '生成预检报告' })).toBeVisible()
  await capture(page, 't09-03-conversion-panel')

  // 4) 帮助文档。
  await page.goto('/help/substitution-workbench')
  await expect(page.getByTestId('help-doc-title')).toHaveText('调代工作台')
  await capture(page, 't09-04-help-doc')
})
