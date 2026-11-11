import { expect, test } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { apiCall, apiLogin, createAccount, newContext, runSql, uiLogin } from './helpers'
import { capture } from './screenshots'

let browserErrors: string[] = []

test.beforeEach(({ page }) => {
  browserErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(message.text())
  })
  page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error}`))
})

/**
 * 十期验收：同步前先看到预演（按部门分组、可整部门排除），确认后才写库。
 *
 * e2e profile 使用确定性假钉钉客户端：根部门下固定有张三、李四两人。
 * 其他用例可能已按工号绑定过他们，因此这里只断言「两人都出现在预演里」，
 * 不断言他们属于新增还是补齐。
 */
test('钉钉通讯录预演、整部门排除与确认导入', async ({ page }) => {
  const api: APIRequestContext = await newContext()
  const token = await apiLogin(api)
  await apiCall(api, token, 'put', '/integrations/dingtalk/config', {
    enabled: true,
    appKey: 'e2e-app-key',
    appSecret: 'e2e-app-secret',
    agentId: '1000',
    syncStrategy: 'local',
  })

  try {
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/settings/general')

    const card = page.locator('#dingtalk-integration')
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '预演同步' }).click()

    const preview = card.locator('.org-sync-preview')
    await expect(preview).toBeVisible()
    await expect(preview.getByText('根部门')).toBeVisible()
    // Scope to rows: names can also appear inside conflict details from earlier specs.
    const rows = preview.locator('.el-table__body tr')
    await expect(rows.filter({ hasText: '张三' })).toHaveCount(1)
    await expect(rows.filter({ hasText: '李四' })).toHaveCount(1)

    const confirmButton = preview.getByRole('button', { name: /确认导入 \d+ 人/ })
    await expect(confirmButton).toBeEnabled()

    // 整部门勾选排除后没有人会被写入，重新勾选后恢复。
    const departmentCheckbox = preview.locator('.group-head .el-checkbox').first()
    await departmentCheckbox.click()
    await expect(preview.getByRole('button', { name: '确认导入 0 人' })).toBeDisabled()
    await departmentCheckbox.click()
    await expect(confirmButton).toBeEnabled()

    await confirmButton.click()

    // 完成后预演表关闭，展示扫描量与写入统计。
    await expect(preview).toHaveCount(0)
    await expect(card.locator('.sync-result')).toContainText('扫描 1 个部门 / 2 人')
    expect(browserErrors).toEqual([])
  } finally {
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', { enabled: false })
    await api.dispose()
  }
})

/**
 * 票 04 验收：已绑定钉钉且有启用账号、但不在本次钉钉可见范围的老师列为「将停用」；
 * 默认勾选，支持逐个排除，确认时二次提示「不删档案与课表、可在账号页重新启用」，
 * 确认后仅停用选中账号并写 ACCOUNT_SYNC_DISABLE 审计，不删教师档案。
 */
test('钉钉同步预演「将停用」候选：逐个排除、二次确认停用、保留档案并写同步停用审计', async ({
  page,
}) => {
  const api: APIRequestContext = await newContext()
  const token = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 8)
  let teacherAId: number | null = null
  let teacherBId: number | null = null

  await apiCall(api, token, 'put', '/integrations/dingtalk/config', {
    enabled: true,
    appKey: 'e2e-app-key',
    appSecret: 'e2e-app-secret',
    agentId: '1000',
    syncStrategy: 'local',
  })

  try {
    const teacherA = await apiCall(api, token, 'post', '/teachers', {
      name: `离职排除${suffix}`,
      employeeNo: `DA-${suffix}`,
    })
    const teacherB = await apiCall(api, token, 'post', '/teachers', {
      name: `离职停用${suffix}`,
      employeeNo: `DB-${suffix}`,
    })
    teacherAId = teacherA.id
    teacherBId = teacherB.id

    await runSql(
      api,
      `UPDATE teacher SET dingtalk_user_id = 'dd-gone-a-${suffix}' WHERE id = ${teacherA.id};` +
        `UPDATE teacher SET dingtalk_user_id = 'dd-gone-b-${suffix}' WHERE id = ${teacherB.id};`,
    )

    const accountA = await createAccount(api, {
      username: `depart_a_${suffix}`,
      displayName: teacherA.name,
      password: 'teacher123',
      roles: ['teacher'],
      teacherId: teacherA.id,
      token,
    })
    const accountB = await createAccount(api, {
      username: `depart_b_${suffix}`,
      displayName: teacherB.name,
      password: 'teacher123',
      roles: ['teacher'],
      teacherId: teacherB.id,
      token,
    })

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/settings/general')

    const card = page.locator('#dingtalk-integration')
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '预演同步' }).click()

    const preview = card.locator('.org-sync-preview')
    await expect(preview).toBeVisible()

    const deactivationSection = preview.getByTestId('dingtalk-deactivation-section')
    await expect(deactivationSection).toBeVisible()
    await expect(deactivationSection.getByRole('row').filter({ hasText: `depart_a_${suffix}` })).toHaveCount(1)
    await expect(deactivationSection.getByRole('row').filter({ hasText: `depart_b_${suffix}` })).toHaveCount(1)

    // 排除 accountA，只保留 accountB 待停用
    await preview.getByTestId(`dingtalk-deactivate-checkbox-${accountA.id}`).click()
    await expect(preview.getByTestId('dingtalk-deactivation-tag')).toContainText('将停用 1')
    await capture(page, 'dingtalk-t04-sync-deactivation-preview')

    const confirmButton = preview.getByTestId('dingtalk-confirm')
    await expect(confirmButton).toContainText('停用 1 个账号')
    await confirmButton.click()

    const dialog = page.locator('.el-message-box')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('档案')
    await expect(dialog).toContainText('课表')
    await expect(dialog).toContainText('重新启用')

    // 先点取消：不提交，预演面板保留
    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(preview).toBeVisible()

    // 再次点击确认并确认停用
    await confirmButton.click()
    await expect(dialog).toBeVisible()
    const [confirmResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().includes('/api/integrations/dingtalk/sync/confirm'),
      ),
      dialog.getByRole('button', { name: '确认停用' }).click(),
    ])
    expect(confirmResponse.status()).toBe(200)

    await expect(preview).toHaveCount(0)
    await expect(card.locator('.sync-result')).toBeVisible()
    await capture(page, 'dingtalk-t04-sync-deactivation-confirmed')

    const users = await apiCall(api, token, 'get', '/users')
    const afterA = users.find((user: any) => user.id === accountA.id)
    const afterB = users.find((user: any) => user.id === accountB.id)
    expect(afterA?.enabled).toBe(true)
    expect(afterB?.enabled).toBe(false)

    const teachers = await apiCall(api, token, 'get', '/teachers')
    expect(teachers.some((teacher: any) => teacher.id === teacherA.id)).toBe(true)
    expect(teachers.some((teacher: any) => teacher.id === teacherB.id)).toBe(true)

    const auditsB = await apiCall(api, token, 'get', '/account-audits', undefined, {
      targetUserId: accountB.id,
      page: 1,
      size: 20,
    })
    expect(auditsB.items.some((item: any) => item.action === 'ACCOUNT_SYNC_DISABLE')).toBe(true)

    const auditsA = await apiCall(api, token, 'get', '/account-audits', undefined, {
      targetUserId: accountA.id,
      page: 1,
      size: 20,
    })
    expect(auditsA.items.some((item: any) => item.action === 'ACCOUNT_SYNC_DISABLE')).toBe(false)

    expect(browserErrors).toEqual([])
  } finally {
    if (teacherAId !== null && teacherBId !== null) {
      await runSql(
        api,
        `UPDATE teacher SET dingtalk_user_id = NULL WHERE id IN (${teacherAId}, ${teacherBId});`,
      ).catch(() => undefined)
    }
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', { enabled: false }).catch(
      () => undefined,
    )
    await api.dispose()
  }
})
