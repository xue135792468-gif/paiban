import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, newContext, pickOption, seedSchool, solveDraft, uiLogin } from './helpers'
import { capture } from './screenshots'

test('单班每周留空：设置与刷新、排课、拖入拒绝和发布校验', async ({ page }) => {
  const api = await newContext()
  try {
    const seed = await seedSchool(api)
    const token = await apiLogin(api)
    const classes = await apiCall(api, token, 'get', `/classes?semesterId=${seed.semesterId}`)
    const ownClass = classes.find((item: { id: number }) => item.id === seed.classId)
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await page.locator('[data-type="CLASS_UNAVAILABLE"]').click()
    await expect(page.getByTestId('constraint-hard-only')).toHaveText('必须留空')
    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) await toggle.click()
    await page.getByTestId('constraint-entry-add').click()
    const card = page.getByTestId('constraint-entry').first()
    await pickOption(card, 'constraint-entry-class', ownClass.name)
    const slot = card.getByTestId('constraint-entry-slot').first()
    await pickOption(slot, 'constraint-entry-slot-day', '周三')
    await pickOption(slot, 'constraint-entry-slot-period', '第8节')
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
    await page.reload()
    await page.locator('[data-type="CLASS_UNAVAILABLE"]').click()
    await expect(page.getByTestId('constraint-entry')).toHaveCount(1)
    await expect(page.getByTestId('constraint-entry-summary')).toContainText(`${ownClass.name} · 周三第8节`)

    const draftId = await solveDraft(api, seed.semesterId)
    const before = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
    const blocked = before.blockedSlots.find((item: { classId: number }) => item.classId === seed.classId)
    expect(blocked).toBeTruthy()
    expect(before.lessons).toHaveLength(4)
    expect(before.lessons.every((lesson: { periodId: number }) => lesson.periodId !== blocked.periodId)).toBe(true)
    await page.goto(`/admin/drafts/${draftId}`)
    const blockedCell = page.locator(`.cell[data-period-id="${blocked.periodId}"]`)
    await expect(blockedCell).toContainText('不上课')
    const source = before.lessons[0]
    await page.locator(`.lesson[data-lesson-id="${source.id}"]`).dragTo(blockedCell)
    const rejected = await apiCall(api, token, 'patch', `/timetable-drafts/${draftId}/lessons/${source.id}`, {
      periodId: blocked.periodId, roomId: source.roomId,
    })
    expect(rejected.saved).toBe(false)
    expect(rejected.conflicts.map((item: { type: string }) => item.type)).toContain('CLASS_UNAVAILABLE')
    const after = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
    expect(after.lessons.find((item: { id: number }) => item.id === source.id).periodId).toBe(source.periodId)
    const validation = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}/validation`)
    expect(validation.publishable).toBe(true)
    await capture(page, 'weekly-no-class-single')
    await page.getByRole('button', { name: '发布课表', exact: true }).click()
    await page.getByRole('button', { name: '确定', exact: true }).click()
    await expect(page.locator('.toolbar').getByText('已发布', { exact: true })).toBeVisible()
  } finally {
    await api.dispose()
  }
})
