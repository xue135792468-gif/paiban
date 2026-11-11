import { expect, test } from '@playwright/test'
import type { Locator } from '@playwright/test'
import { API, apiCall, apiLogin, createAccount, newContext, pickOption, uiLogin } from './helpers'
import { capture } from './screenshots'

/**
 * 票 02 账号审计页：真实页面 + 公开账号接口造数。
 * 造数走 `/users` create/update/disable（`createAccount` 是唯一的建号 helper），
 * 不 mock 页面接口、不读组件内部状态；筛选控件只认约定的可访问名称。
 */
const SUPER_ADMIN = { username: 'admin', password: 'admin123' }
const ACCOUNT_PASSWORD = 'teacher123'
const AUDIT_PATH = '/account-audits'

/** 每个用例开始前挂 console/pageerror 监听；用例结束断言为空。 */
let browserErrors: string[] = []

test.beforeEach(({ page }) => {
  browserErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text())
  })
  page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error}`))
})

/** 「变更前 → 变更后」是本表最后一列；只读这一列，动作名里的「停用」不会混进来。 */
function changeCell(row: Locator): Locator {
  return row.locator('td').last()
}

test('超级管理员：按目标账号看到建号、改身份、停用三条真实记录，再按动作筛出停用', async ({
  page,
}) => {
  const api = await newContext()
  const suffix = Math.random().toString(36).slice(2, 8)
  const username = `audit_${suffix}`
  const displayName = `审计对象${suffix}`
  const distractor = `audit_other_${suffix}`
  try {
    const token = await apiLogin(api)
    const created = await createAccount(api, {
      username,
      displayName,
      password: ACCOUNT_PASSWORD,
      roles: ['teacher'],
      token,
    })
    await apiCall(api, token, 'put', `/users/${created.id}`, {
      displayName,
      roleCodes: ['admin'],
    })
    await apiCall(api, token, 'post', `/users/${created.id}/disable`)
    // 干扰账号在三条目标事件之后建号，默认列表（时间倒序）里它最新。
    await createAccount(api, {
      username: distractor,
      displayName: `干扰对象${suffix}`,
      password: ACCOUNT_PASSWORD,
      roles: ['teacher'],
      token,
    })

    // 服务端事实：只有这三条，且按时间倒序（同一条筛选口径后面通过页面复现）。
    const recorded = await apiCall(api, token, 'get', AUDIT_PATH, undefined, {
      targetUserId: created.id,
      page: 1,
      size: 20,
    })
    expect(recorded.total).toBe(3)
    expect(recorded.items.map((item: any) => item.action).sort()).toEqual([
      'ACCOUNT_CREATE',
      'ACCOUNT_DISABLE',
      'ACCOUNT_ROLE_CHANGE',
    ])
    const stamps = recorded.items.map((item: any) => String(item.createdAt))
    for (let index = 1; index < stamps.length; index += 1) {
      expect(stamps[index - 1] >= stamps[index]).toBeTruthy()
    }

    await uiLogin(page, SUPER_ADMIN.username, SUPER_ADMIN.password)
    await page.goto('/settings/account-audit')
    await expect(page.getByRole('link', { name: '账号审计' })).toBeVisible()
    for (const header of ['时间', '操作者', '目标账号', '动作']) {
      await expect(page.getByRole('columnheader', { name: header })).toBeVisible()
    }
    await expect(page.getByRole('columnheader', { name: /变更/ })).toBeVisible()

    const distractorRows = page.getByRole('row').filter({ hasText: distractor })
    await expect(distractorRows).toHaveCount(1)

    // 目标账号下拉只接受完整可见标签（含停用标记），由公共 helper 精确选中。
    const [filtered] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          response.url().includes(`/api${AUDIT_PATH}`) &&
          response.url().includes(`targetUserId=${created.id}`),
      ),
      pickOption(
        page.locator('body'),
        'account-audit-target-filter',
        `${username}（${displayName}） · 已停用`,
      ),
    ])
    expect(filtered.status()).toBe(200)

    const rows = page.getByRole('row').filter({ hasText: username })
    await expect(rows).toHaveCount(3)
    await expect(distractorRows).toHaveCount(0)
    await expect(rows.nth(0)).toContainText('停用账号')
    await expect(rows.nth(1)).toContainText('改身份')
    await expect(rows.nth(2)).toContainText('手工创建账号')
    await expect(rows.nth(0)).toContainText(displayName)

    // 改身份：变更前列老师、变更后列教务管理员（顺序即前后）。
    const identityChange = await changeCell(rows.nth(1)).innerText()
    expect(identityChange).toContain('老师')
    expect(identityChange).toContain('教务管理员')
    expect(identityChange.indexOf('老师')).toBeLessThan(identityChange.indexOf('教务管理员'))

    // 停用：变更前启用 → 变更后停用。
    const disableChange = await changeCell(rows.nth(0)).innerText()
    expect(disableChange).toContain('启用')
    expect(disableChange).toContain('停用')
    expect(disableChange).toContain('→')
    expect(disableChange.indexOf('启用')).toBeLessThan(disableChange.indexOf('停用'))
    await capture(page, 'dingtalk-t02-account-audit-target')

    const [reloaded] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          response.url().includes(`/api${AUDIT_PATH}`) &&
          response.url().includes('action=ACCOUNT_DISABLE'),
      ),
      pickOption(page.locator('body'), 'account-audit-action-filter', '停用账号'),
    ])
    expect(reloaded.status()).toBe(200)

    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText(username)
    await expect(rows.first()).toContainText(displayName)
    await expect(rows.first()).toContainText('停用账号')
    const onlyChange = await changeCell(rows.first()).innerText()
    expect(onlyChange.indexOf('启用')).toBeLessThan(onlyChange.indexOf('停用'))
    await capture(page, 'dingtalk-t02-account-audit-action')

    expect(browserErrors).toEqual([])
  } finally {
    await api.dispose()
  }
})

const RESTRICTED: Array<{
  label: string
  prefix: string
  roles: string[]
  bindTeacher: boolean
}> = [
  { label: '教务管理员', prefix: 'audit_jw', roles: ['admin'], bindTeacher: false },
  { label: '老师', prefix: 'audit_teacher', roles: ['teacher'], bindTeacher: true },
]

for (const actor of RESTRICTED) {
  test(`${actor.label}：设置里没有「账号审计」页签，直接打开该页与接口都是 403`, async ({
    page,
  }) => {
    const api = await newContext()
    const suffix = Math.random().toString(36).slice(2, 8)
    const username = `${actor.prefix}_${suffix}`
    try {
      const token = await apiLogin(api)
      let teacherId: number | undefined
      if (actor.bindTeacher) {
        const teacher = await apiCall(api, token, 'post', '/teachers', {
          name: `审计权限${suffix}`,
          employeeNo: `AU-${suffix}`,
        })
        teacherId = teacher.id
      }
      await createAccount(api, {
        username,
        displayName: `审计${actor.label}${suffix}`,
        password: ACCOUNT_PASSWORD,
        roles: actor.roles,
        teacherId,
        token,
      })

      await uiLogin(page, username, ACCOUNT_PASSWORD)

      await page.goto('/settings/general')
      if (actor.bindTeacher) {
        // 老师连设置入口都没有：设置页直接落到 403。
        await expect(page).toHaveURL(/\/403$/)
      } else {
        await expect(page.getByRole('link', { name: '设置', exact: true }).first()).toBeVisible()
      }
      await expect(page.getByRole('link', { name: '账号审计' })).toHaveCount(0)

      await page.goto('/settings/account-audit')
      await expect(page).toHaveURL(/\/403$/)

      // 页面守卫之外的第二道边界：同一身份直接打接口也必须是 403。
      const actorToken = await apiLogin(api, username, ACCOUNT_PASSWORD)
      const denied = await api.get(`${API}${AUDIT_PATH}?page=1&size=20`, {
        headers: { Authorization: `Bearer ${actorToken}` },
      })
      expect(denied.status()).toBe(403)

      await capture(page, `dingtalk-t02-account-audit-denied-${actor.prefix}`)
      expect(browserErrors).toEqual([])
    } finally {
      await api.dispose()
    }
  })
}
