import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool, solveAndPublish, uiLogin } from './helpers'

/**
 * Ticket 03: core-data deletion is soft and reversible for 7 days; data referenced
 * by a published timetable can only be disabled.
 */
test.describe('删除 / 停用 / 最近删除', () => {
  test('删除有授课任务的教师后可在最近删除里整批恢复', async ({ page }) => {
    const context = await newContext()
    const seed = await seedSchool(context)
    const token = await apiLogin(context)
    const teachers = await apiCall(context, token, 'get', '/teachers')
    const teacher = teachers.find((item: { id: number }) => item.id === seed.teacherId)
    const teacherName = teacher.name as string

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/data/teachers')

    const row = page.getByRole('row').filter({ hasText: teacherName })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '删除', exact: true }).click()

    const dialog = page.locator('.el-dialog').filter({ hasText: '删除教师' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('授课任务')

    await dialog.getByTestId('delete-impact-cascade').click()
    await expect(page.getByRole('row').filter({ hasText: teacherName })).toHaveCount(0)

    await page.goto('/settings/recycle-bin')
    const batch = page.getByRole('row').filter({ hasText: teacherName })
    await expect(batch).toBeVisible()
    await expect(batch).toContainText('共 2 项')
    await batch.getByTestId('restore-batch').click()
    await expect(page.getByRole('row').filter({ hasText: teacherName })).toHaveCount(0)

    await page.goto('/data/teachers')
    await expect(page.getByRole('row').filter({ hasText: teacherName })).toBeVisible()
    await context.dispose()
  })

  test('被已发布课表引用的教师只能停用', async ({ page }) => {
    const context = await newContext()
    const seed = await seedSchool(context)
    await solveAndPublish(context, seed)
    const token = await apiLogin(context)
    const teachers = await apiCall(context, token, 'get', '/teachers')
    const teacher = teachers.find((item: { id: number }) => item.id === seed.teacherId)
    const teacherName = teacher.name as string

    const impact = await apiCall(context, token, 'get', `/teachers/${seed.teacherId}/delete-impact`)
    expect(impact.canDelete).toBe(false)
    expect(impact.publishedRefs).toBeGreaterThan(0)

    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/data/teachers')

    const row = page.getByRole('row').filter({ hasText: teacherName })
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '删除', exact: true }).click()

    const dialog = page.locator('.el-dialog').filter({ hasText: '删除教师' })
    await expect(dialog).toContainText('只能停用')
    await expect(dialog.getByTestId('delete-impact-cascade')).toHaveCount(0)
    await expect(dialog.getByTestId('delete-impact-delete')).toHaveCount(0)

    await dialog.getByTestId('delete-impact-disable').click()
    await expect(page.getByRole('row').filter({ hasText: teacherName })).toContainText('已停用')

    // Ticket 03 acceptance: a disabled teacher must not be offered in a picker.
    await page.goto('/data/assignments')
    await page.getByRole('button', { name: '新建授课任务' }).click()
    const assignmentDialog = page.locator('.el-dialog').filter({ hasText: '新建授课任务' })
    await assignmentDialog.getByRole('combobox', { name: '* 教师' }).click()
    await expect(
      page.locator('.el-select-dropdown:visible').getByText(teacherName, { exact: true }),
    ).toHaveCount(0)
    await context.dispose()
  })
})
