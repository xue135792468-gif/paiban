import { expect, test } from '@playwright/test'
import { uiLogin } from './helpers'

/**
 * Ticket 03 验收：复杂字段旁的问号图标悬停后显示配置化的说明文案。
 *
 * 不 seed 数据、不读数据库：设置页是静态表单，登录后直接进入即可。
 * 共享 uiLogin 默认写入教程标记，避免教程覆盖层挡住悬停。
 *
 * 票 15 删除了初高中模式（`settings.schoolType` 的 tooltip 已退役），这里改测设置页
 * 仍在的「AI 供应商」说明：同样是一个配置型复杂区块，悬停其问号应给出大白话解释。
 */

test('悬停「AI 供应商」旁的问号显示字段说明', async ({ page }) => {
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/settings/general')

  const icon = page
    .locator('.card-title')
    .filter({ hasText: 'AI 供应商' })
    .getByTestId('info-tooltip')
    .first()

  await expect(icon).toBeVisible()
  await icon.hover()

  const tooltip = page.getByRole('tooltip')
  await expect(tooltip).toBeVisible()
  await expect(tooltip).toContainText(
    '配置 AI 供应商、模型与任务分配；未配置时 AI 功能保持隐藏，不影响排课。',
  )
})
