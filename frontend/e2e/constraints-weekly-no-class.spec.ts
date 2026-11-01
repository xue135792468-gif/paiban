import { expect, test } from '@playwright/test'
import { API, apiCall, apiLogin, newContext, pickOption, seedSchool, solveDraft, uiLogin } from './helpers'
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
    await card.getByRole('radio', { name: '指定班级', exact: true }).check()
    await pickOption(card, 'constraint-entry-class', ownClass.name)
    await page.keyboard.press('Escape')
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

test('多个班级与全校范围：刷新不丢班级，新班继承全校规则', async ({ page }) => {
  const api = await newContext()
  try {
    const seed = await seedSchool(api)
    const token = await apiLogin(api)
    const original = (await apiCall(api, token, 'get', `/classes?semesterId=${seed.semesterId}`))[0]
    const assignment = (await apiCall(api, token, 'get', `/assignments?semesterId=${seed.semesterId}`))[0]
    const addClass = async (suffix: string) => {
      const item = await apiCall(api, token, 'post', `/classes?semesterId=${seed.semesterId}`,
        { gradeId: original.gradeId, name: `${original.name}-${suffix}`, studentCount: 40 })
      await apiCall(api, token, 'post', '/assignments', {
        classId: item.id, teacherId: seed.teacherId, courseId: assignment.courseId, weeklyLessons: 4,
      })
      return item
    }
    const second = await addClass('二')
    const third = await addClass('三')
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await page.locator('[data-type="CLASS_UNAVAILABLE"]').click()
    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) await toggle.click()
    await page.getByTestId('constraint-entry-add').click()
    const card = page.getByTestId('constraint-entry').first()
    await card.getByRole('radio', { name: '指定班级', exact: true }).check()
    await pickOption(card, 'constraint-entry-class', original.name)
    await page.keyboard.press('Escape')
    await pickOption(card, 'constraint-entry-class', second.name)
    await page.keyboard.press('Escape')
    const slot = card.getByTestId('constraint-entry-slot').first()
    await pickOption(slot, 'constraint-entry-slot-day', '周三')
    await pickOption(slot, 'constraint-entry-slot-period', '第8节')
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
    await page.reload()
    await page.locator('[data-type="CLASS_UNAVAILABLE"]').click()
    await expect(page.getByTestId('constraint-entry')).toHaveCount(1)
    await expect(page.getByTestId('constraint-entry-summary')).toContainText(original.name)
    await expect(page.getByTestId('constraint-entry-summary')).toContainText(second.name)
    const draftId = await solveDraft(api, seed.semesterId)
    const draft = await apiCall(api, token, 'get', `/timetable-drafts/${draftId}`)
    expect(draft.blockedSlots.map((item: { classId: number }) => item.classId).sort())
      .toEqual([seed.classId, second.id].sort())
    expect(draft.blockedSlots.some((item: { classId: number }) => item.classId === third.id)).toBe(false)
    await page.getByTestId('constraint-entry').first().getByRole('radio', { name: '全校', exact: true }).check()
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
    const future = await addClass('以后新增')
    const globalId = await solveDraft(api, seed.semesterId)
    const global = await apiCall(api, token, 'get', `/timetable-drafts/${globalId}`)
    expect(new Set(global.blockedSlots.map((item: { classId: number }) => item.classId)))
      .toEqual(new Set([seed.classId, second.id, third.id, future.id]))
    expect(global.lessons).toHaveLength(16)
    await capture(page, 'weekly-no-class-scope')
  } finally { await api.dispose() }
})

test('容量不足：40个位置39节课留空2个，允许保存但拒绝开始排课', async ({ page }) => {
  const api = await newContext()
  try {
    const seed = await seedSchool(api)
    const token = await apiLogin(api)
    await apiCall(api, token, 'post', `/semesters/${seed.semesterId}/periods/default`, {
      morningCount: 4, afternoonCount: 4, eveningCount: 0, days: [1, 2, 3, 4, 5],
    })
    const assignment = (await apiCall(api, token, 'get', `/assignments?semesterId=${seed.semesterId}`))[0]
    await apiCall(api, token, 'put', `/assignments/${assignment.id}`, {
      classId: seed.classId, teacherId: seed.teacherId, courseId: assignment.courseId, weeklyLessons: 20,
    })
    const extra = await apiCall(api, token, 'post', '/courses', {
      name: `容量验证-${seed.semesterId}`, subject: '语文', allowConsecutive: false,
    })
    await apiCall(api, token, 'post', '/assignments', {
      classId: seed.classId, teacherId: seed.teacherId, courseId: extra.id, weeklyLessons: 19,
    })
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/admin/constraints')
    await page.locator('[data-type="CLASS_UNAVAILABLE"]').click()
    const toggle = page.getByTestId('constraint-enabled-switch')
    if (!(await toggle.getAttribute('class'))?.includes('is-checked')) await toggle.click()
    await page.getByTestId('constraint-entry-add').click()
    const card = page.getByTestId('constraint-entry').first()
    for (let i = 0; i < 2; i++) {
      if (i) await card.getByTestId('constraint-entry-slot-add').click()
      const slot = card.getByTestId('constraint-entry-slot').nth(i)
      await pickOption(slot, 'constraint-entry-slot-day', '周三')
      await pickOption(slot, 'constraint-entry-slot-period', `第${i + 1}节`)
    }
    const warning = page.getByTestId('constraint-capacity-warning')
    await expect(warning).toContainText('39')
    await expect(warning).toContainText('38')
    await expect(warning).toContainText('1')
    await page.getByTestId('constraint-save').click()
    await expect(page.getByTestId('constraint-unsaved')).toHaveCount(0)
    await page.reload()
    await expect(warning).toContainText('39')
    const response = await api.post(`${API}/scheduling-jobs?semesterId=${seed.semesterId}`, {
      headers: { Authorization: `Bearer ${token}` }, data: { maxSeconds: 5 },
    })
    expect(response.status()).toBe(400)
    const body = JSON.stringify(await response.json())
    expect(body).toContain('39')
    expect(body).toContain('38')
    await capture(page, 'weekly-no-class-capacity')
  } finally { await api.dispose() }
})
