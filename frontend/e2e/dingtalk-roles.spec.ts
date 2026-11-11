import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool, uiLogin } from './helpers'

/**
 * 票 01 权限矩阵（真实页面）：超级管理员 / 教务管理员 / 老师三种身份，
 * 从界面验证账号页、集成配置、草稿与规则入口以及全校课表只读。
 *
 * 依赖新建账号后端切片（三角色 + integration:manage 过滤 + user:manage 守卫）；
 * 后端尚未整合前这条 spec 不运行，只随前端一起提交。
 */
const SUPER_ADMIN = { username: 'admin', password: 'admin123' }

async function createAccount(
  username: string,
  displayName: string,
  roleCode: 'admin' | 'teacher',
  options: { password: string; teacherId?: number },
) {
  const api = await newContext()
  const token = await apiLogin(api)
  try {
    await apiCall(api, token, 'post', '/users', {
      username,
      displayName,
      password: options.password,
      roleCodes: [roleCode],
      teacherId: options.teacherId,
    })
  } finally {
    await api.dispose()
  }
}

async function menuText(page: Page): Promise<string> {
  return (await page.locator('[data-testid="layout-menu"]').innerText()).replace(/\s+/g, ' ')
}

test('超级管理员：能看到钉钉配置与账号页，改身份只给三个选项', async ({ page }) => {
  await uiLogin(page, SUPER_ADMIN.username, SUPER_ADMIN.password)

  await page.goto('/settings/general')
  await expect(page.locator('#dingtalk-integration')).toBeVisible()
  await expect(page.locator('.page-tab').filter({ hasText: '账号' })).toBeVisible()

  await page.goto('/settings/users')
  await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible()

  await page.getByRole('button', { name: '编辑' }).first().click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '编辑用户' })
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-select').first().click()
  const options = page.getByRole('option')
  await expect(options).toHaveCount(3)
  await expect(options).toHaveText(['超级管理员', '教务管理员', '老师'])
})

test('教务管理员：看不到钉钉配置与账号页，业务设置照常保存', async ({ page }) => {
  const suffix = Math.random().toString(36).slice(2, 8)
  const username = `jw_${suffix}`
  const password = 'teacher123'
  await createAccount(username, `教务${suffix}`, 'admin', { password })

  await uiLogin(page, username, password)
  expect(await menuText(page)).toContain('设置')

  await page.goto('/settings/general')
  await expect(page.locator('#dingtalk-integration')).toHaveCount(0)
  await expect(page.locator('.page-tab').filter({ hasText: '账号' })).toHaveCount(0)

  const saved = page.waitForResponse(
    (response) => response.url().includes('/api/settings') && response.request().method() === 'PUT',
  )
  await page.getByRole('button', { name: '保存设置' }).click()
  expect((await saved).status()).toBe(200)

  await page.goto('/settings/users')
  await expect(page).toHaveURL(/\/403$/)
})

test('老师：默认自己的课表、能看全校课表，但没有草稿、规则与设置入口', async ({ page }) => {
  const api = await newContext()
  const seed = await seedSchool(api)
  const token = await apiLogin(api)
  const teachers: Array<{ id: number; name: string }> = await apiCall(
    api,
    token,
    'get',
    '/teachers/options',
  )
  const teacherName = teachers.find((teacher) => teacher.id === seed.teacherId)!.name
  await api.dispose()

  await uiLogin(page, seed.username, seed.password)
  const menu = await menuText(page)
  expect(menu).not.toContain('排课规则')
  expect(menu).not.toContain('设置')

  await page.goto('/workbench/timetable')
  // 只读：工作台只剩课表页签，没有草稿与排课。
  await expect(page.locator('.page-tab')).toHaveCount(1)
  await expect(page.locator('.page-tab')).toHaveText('课表')
  // 默认落到自己的教师课表，同时保留全校视图的只读入口。
  await expect(page.locator('.toolbar .el-select')).toContainText(teacherName)
  await expect(page.getByText('全校', { exact: true })).toBeVisible()

  await page.goto('/settings/general')
  await expect(page).toHaveURL(/\/403$/)
})
