import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import {
  SUPER_ADMIN,
  apiCall,
  apiLogin,
  createAccount,
  newContext,
  seedSchool,
  uiLogin,
} from './helpers'
import type { AccountSeed } from './helpers'
import { capture } from './screenshots'

/**
 * 票 01 权限矩阵（真实页面）：超级管理员 / 教务管理员 / 老师三种身份，
 * 从界面验证账号页、集成配置、草稿与规则入口以及全校课表只读。
 *
 * 后端三角色迁移、integration:manage 字段过滤与 user:manage 守卫都已整合进
 * `codex/dingtalk-multiuser`，这条 spec 在集成分支上跑（见 t01-ui-delivery.md）。
 */

async function menuText(page: Page): Promise<string> {
  return (await page.locator('[data-testid="layout-menu"]').innerText()).replace(/\s+/g, ' ')
}

/** 用管理员 token 建一个密码账号，用完即销毁 API 上下文（建号动作在 helpers.ts）。 */
async function createAccountAsAdmin(
  account: AccountSeed,
): Promise<{ id: number; roles: string[] }> {
  const api = await newContext()
  try {
    return await createAccount(api, account)
  } finally {
    await api.dispose()
  }
}

test('超级管理员：能看到钉钉配置与账号页，改身份只给三个选项', async ({ page }) => {
  await uiLogin(page, SUPER_ADMIN.username, SUPER_ADMIN.password)

  await page.goto('/settings/general')
  await expect(page.locator('#dingtalk-integration')).toBeVisible()
  await expect(page.locator('.page-tab[href="/settings/users"]')).toBeVisible()

  await page.goto('/settings/users')
  await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible()

  await page.getByRole('button', { name: '编辑' }).first().click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '编辑用户' })
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-select').first().click()
  // Element Plus 的下拉会 teleport 到 body：只认当前打开的那一个 dropdown，
  // 否则同页其他下拉的 option 也会被算进来。
  const options = page.locator('.el-select-dropdown:visible').last().getByRole('option')
  await expect(options).toHaveCount(3)
  await expect(options).toHaveText(['超级管理员', '教务管理员', '老师'])
  await capture(page, 'dingtalk-t01-super-admin-roles')
})

test('教务管理员：看不到钉钉配置与账号页，业务设置照常保存', async ({ page }) => {
  const suffix = Math.random().toString(36).slice(2, 8)
  const username = `jw_${suffix}`
  const password = 'teacher123'
  await createAccountAsAdmin({ username, displayName: `教务${suffix}`, password, roles: ['admin'] })

  await uiLogin(page, username, password)
  expect(await menuText(page)).toContain('设置')

  await page.goto('/settings/general')
  await expect(page.locator('#dingtalk-integration')).toHaveCount(0)
  await expect(page.locator('.page-tab[href="/settings/users"]')).toHaveCount(0)

  const saved = page.waitForResponse(
    (response) => response.url().includes('/api/settings') && response.request().method() === 'PUT',
  )
  await page.getByRole('button', { name: '保存设置' }).click()
  expect((await saved).status()).toBe(200)
  await capture(page, 'dingtalk-t01-academic-settings')

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
  await expect(page.locator('.toolbar').getByRole('radio', { name: '教师' })).toBeChecked()
  await expect(page.locator('.toolbar').getByText('全校', { exact: true })).toBeVisible()
  await capture(page, 'dingtalk-t01-teacher-timetable')

  await page.goto('/settings/general')
  await expect(page).toHaveURL(/\/403$/)
})

test('超级管理员：多角色账号只改姓名不会丢身份', async ({ page }) => {
  const suffix = Math.random().toString(36).slice(2, 8)
  const username = `multi_${suffix}`
  const renamed = `改名${suffix}`
  const created = await createAccountAsAdmin({
    username,
    displayName: `多角色${suffix}`,
    password: 'teacher123',
    roles: ['admin', 'super_admin', 'teacher'],
  })

  await uiLogin(page, SUPER_ADMIN.username, SUPER_ADMIN.password)
  await page.goto('/settings/users')

  const row = page.getByRole('row').filter({ hasText: username })
  await expect(row).toHaveCount(1)
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '编辑用户' })
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-form-item').filter({ hasText: '姓名' }).locator('input').fill(renamed)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).toBeHidden()

  // 真实后端回读：只改姓名不能把三个身份压成弹窗里那一个（修复前会变成 ['admin']）。
  const api = await newContext()
  try {
    const token = await apiLogin(api)
    const accounts: Array<{ id: number; displayName: string; roles: string[] }> = await apiCall(
      api,
      token,
      'get',
      '/users',
    )
    const after = accounts.find((account) => account.id === created.id)!
    expect(after.displayName).toBe(renamed)
    expect(after.roles).toEqual(['admin', 'super_admin', 'teacher'])
  } finally {
    await api.dispose()
  }
  await capture(page, 'dingtalk-t01-multi-role-name-edit')
})
