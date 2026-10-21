import { expect, test, type Page } from '@playwright/test'

// Local login helper: ticket 01 owns the shared `uiLogin` in helpers.ts, and this
// spec must stay runnable on its own branch. The tour flags keep the onboarding
// tour from covering the help page once that ticket lands.
async function uiLogin(page: Page, username = 'admin', password = 'admin123') {
  await page.addInitScript(() => {
    localStorage.setItem('paiban.tour.seen', '1')
    localStorage.setItem('paiban.tour.advanced', '1')
  })
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'))
}

test('帮助中心首页展示目录树与分组', async ({ page }) => {
  await uiLogin(page)
  await page.goto('/help')

  await expect(page.getByRole('heading', { name: '帮助中心', level: 1 })).toBeVisible()

  const sidebar = page.getByTestId('help-sidebar')
  await expect(sidebar.getByText('功能模块')).toBeVisible()
  await expect(sidebar.getByText('场景流程')).toBeVisible()
  await expect(sidebar.getByRole('link', { name: '基础数据' })).toBeVisible()
  await expect(sidebar.getByRole('link', { name: '新学期开学流程' })).toBeVisible()

  await expect(page.getByTestId('help-index').getByText('排课', { exact: true })).toBeVisible()
})

test('打开文档后渲染 Markdown 正文', async ({ page }) => {
  await uiLogin(page)
  await page.goto('/help')

  await page.getByTestId('help-sidebar').getByRole('link', { name: '基础数据' }).click()
  await expect(page).toHaveURL(/\/help\/foundation$/)
  await expect(page.getByTestId('help-doc-title')).toHaveText('基础数据')

  const content = page.getByTestId('help-content')
  await expect(content.getByRole('heading', { name: '行政班' })).toBeVisible()
  await expect(content.getByText('行政班是排课的基本单位')).toBeVisible()
  await expect(content.getByRole('table')).toBeVisible()
})

test('搜索关键词返回结果并可跳转', async ({ page }) => {
  await uiLogin(page)
  await page.goto('/help')

  await page.getByPlaceholder('搜索帮助文档，例如：每周节数').fill('每周节数')
  const results = page.getByTestId('help-search-results')
  await expect(results).toBeVisible()
  await expect(results.getByRole('link', { name: '排课' })).toBeVisible()

  await results.getByRole('link', { name: '排课' }).click()
  await expect(page).toHaveURL(/\/help\/scheduling$/)
  await expect(page.getByTestId('help-content').getByText('每周节数').first()).toBeVisible()
})

test('文档内站内链接跳到真实路由', async ({ page }) => {
  await uiLogin(page)
  await page.goto('/help/foundation')

  await page.getByTestId('help-content').getByRole('link', { name: '创建班级' }).click()
  await expect(page).toHaveURL(/\/admin\/classes$/)
  await expect(page.getByRole('heading', { name: '行政班管理' })).toBeVisible()
})
