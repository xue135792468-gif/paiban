import { expect, test } from '@playwright/test'
import { uiLogin } from './helpers'

/**
 * Ticket 03 验收：复杂字段旁的问号图标悬停后显示配置化的说明文案。
 *
 * 不 seed 数据、不读数据库：设置页是静态表单，登录后直接进入即可。
 * 共享 uiLogin 默认写入教程标记，避免教程覆盖层挡住悬停。
 */

test('悬停「学校类型」旁的问号显示字段说明', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/admin/settings')

  const icon = page
    .locator('.el-form-item')
    .filter({ hasText: '学校类型' })
    .getByTestId('info-tooltip')
    .first()

  await expect(icon).toBeVisible()
  await icon.hover()

  const tooltip = page.getByRole('tooltip')
  await expect(tooltip).toBeVisible()
  await expect(tooltip).toContainText('初中模式会隐藏权重、模板、多草稿对比等高级功能')
})
