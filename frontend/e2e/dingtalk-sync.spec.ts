import { expect, test } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { apiCall, apiLogin, newContext, uiLogin } from './helpers'

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
    await page.goto('/admin/settings')

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
  } finally {
    await apiCall(api, token, 'put', '/integrations/dingtalk/config', { enabled: false })
    await api.dispose()
  }
})
