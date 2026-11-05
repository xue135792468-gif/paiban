import { expect, test } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  newContext,
  seedConstraintScenario,
  seedSchool,
  solveDraft,
  uiLogin,
} from './helpers'

/**
 * Ticket 04: rule cards and drafts join the same 7-day recycle bin as core data.
 * Rule cards and drafts are deleted from their own page and restored in 最近删除.
 */
test.describe('删除 / 撤销：规则与草稿', () => {
  test('删除规则卡后可在最近删除恢复', async ({ page }) => {
    const context = await newContext()
    const seed = await seedConstraintScenario(context)
    const token = await apiLogin(context)
    const teacher = seed.teachers[0]

    await apiCall(context, token, 'put', `/constraints?semesterId=${seed.semesterId}`, {
      configs: [
        {
          type: 'TEACHER_UNAVAILABLE',
          weight: 100,
          hard: true,
          enabled: true,
          entries: [
            {
              params: { teacherId: teacher.id, dayOfWeek: 1, periodNo: 1 },
              enabled: true,
              groupKey: 'e2e-card:0',
            },
          ],
        },
      ],
    })

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/rules')

    const card = page.getByTestId('rule-card').filter({ hasText: teacher.name })
    await expect(card).toBeVisible()
    await card.getByTestId('rule-delete').click()

    const dialog = page.locator('.el-message-box').filter({ hasText: '删除规则' })
    await expect(dialog).toContainText('影响')
    await dialog.getByRole('button', { name: '删除', exact: true }).click()
    await expect(page.getByTestId('rule-card').filter({ hasText: teacher.name })).toHaveCount(0)

    await page.goto('/settings/recycle-bin')
    const batch = page.getByRole('row').filter({ hasText: teacher.name })
    await expect(batch).toBeVisible()
    await expect(batch).toContainText('排课规则')
    await batch.getByTestId('restore-batch').click()
    await expect(page.getByRole('row').filter({ hasText: teacher.name })).toHaveCount(0)

    await page.goto('/rules')
    await expect(page.getByTestId('rule-card').filter({ hasText: teacher.name })).toBeVisible()
    await context.dispose()
  })

  test('删除草稿后可在最近删除恢复', async ({ page }) => {
    const context = await newContext()
    const seed = await seedSchool(context)
    const token = await apiLogin(context)
    const draftId = await solveDraft(context, seed.semesterId)
    const detail = await apiCall(context, token, 'get', `/timetable-drafts/${draftId}`)
    const draftName = detail.draft.name as string

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/workbench')

    const draftsTable = page.locator('.el-table').filter({ hasText: '课节数' }).first()
    const row = draftsTable.getByRole('row').filter({ hasText: draftName })
    await expect(row).toBeVisible()
    await row.getByTestId('draft-delete').click()

    const dialog = page.locator('.el-message-box').filter({ hasText: '删除草稿' })
    await expect(dialog).toContainText('最近删除')
    await dialog.getByRole('button', { name: '确定删除', exact: true }).click()
    await expect(draftsTable.getByRole('row').filter({ hasText: draftName })).toHaveCount(0)

    await page.goto('/settings/recycle-bin')
    const batch = page.getByRole('row').filter({ hasText: draftName })
    await expect(batch).toBeVisible()
    await expect(batch).toContainText('课表草稿')
    await batch.getByTestId('restore-batch').click()
    await expect(page.getByRole('row').filter({ hasText: draftName })).toHaveCount(0)

    await page.goto('/workbench')
    const restoredTable = page.locator('.el-table').filter({ hasText: '课节数' }).first()
    await expect(restoredTable.getByRole('row').filter({ hasText: draftName })).toBeVisible()
    await context.dispose()
  })
})
