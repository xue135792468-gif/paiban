import { expect, test, type Locator, type Page } from '@playwright/test'
import {
  apiCall,
  apiLogin,
  classEntries,
  dateFor,
  newContext,
  pickOption,
  seedSchool,
  solveAndPublish,
  uiLogin,
} from './helpers'

/**
 * 票 05：老师自助只申请代课，提交后停在「待审批」；教务在待审批里批准（可改人）
 * 或驳回（必填理由），老师在自己的列表里看到结果。
 */

async function pickDate(page: Page, date: string) {
  const dateInput = page.getByRole('combobox', { name: '请假日期' })
  await dateInput.fill(date)
  await dateInput.press('Tab')
}

/** 课节选项由当天的课表生成，测试只取第一条；等旧面板收起再点。 */
async function pickFirstOption(select: Locator) {
  await select.click()
  const dropdown = select.page().locator('.el-select-dropdown:visible').last()
  const option = dropdown.locator('.el-select-dropdown__item').first()
  await expect(option).toBeVisible()
  await option.evaluate((element) => (element as HTMLElement).click())
  await expect(dropdown).toBeHidden()
}

async function submitCover(page: Page, date: string, reason: string, substituteName: string) {
  await pickDate(page, date)
  await pickFirstOption(page.locator('.el-form-item', { hasText: '我的课节' }).locator('.el-select'))
  await pickOption(page, 'expected-substitute', substituteName)
  await page.getByPlaceholder('如：家中有事，需要请假半天').fill(reason)
  await page.getByRole('button', { name: '提交申请' }).click()
  await expect(page.getByText('申请已提交，等待教务审批')).toBeVisible()
}

test.describe('代课申请与审批', () => {
  test('老师提交 → 撤回 → 重新提交 → 教务改人批准 → 老师看到已生效', async ({ page }) => {
    const api = await newContext()
    const seed = await seedSchool(api)
    await solveAndPublish(api, seed)
    const adminToken = await apiLogin(api)

    // 改人批准需要第三位老师：新建一位无课教师，不出现在原表单的默认人选里。
    const suffix = Math.random().toString(36).slice(2, 8)
    const otherTeacher = await apiCall(api, adminToken, 'post', '/teachers', {
      name: `王老师${suffix}`,
      employeeNo: `T3-${suffix}`,
    })
    const teacherOptions = await apiCall(api, adminToken, 'get', '/teachers/options')
    const expectedTeacherName = teacherOptions.find(
      (item: { id: number }) => item.id === seed.substituteTeacherId,
    ).name

    const entries = await classEntries(api, seed.classId)
    const entry = entries.find((item) => item.dayOfWeek != null && item.id != null)
    expect(entry).toBeTruthy()
    const effectiveDate = dateFor(entry!.dayOfWeek)

    await uiLogin(page, seed.username, seed.password)
    await page.goto('/substitution')

    // 老师表单只有代课，原因框提示想调课写在这里。
    await expect(page.getByText('想调课请写明原因，由教务处理')).toBeVisible()
    await expect(page.locator('.el-radio-button', { hasText: '调课' })).toHaveCount(0)

    await submitCover(page, effectiveDate, '家中有事，先撤回再提交', expectedTeacherName)
    await expect(page.locator('.el-table').getByText('待审批').first()).toBeVisible()
    await page.getByRole('button', { name: '撤回' }).click()
    await expect(page.getByText('已撤回申请')).toBeVisible()
    await expect(page.locator('.el-table').getByText('已撤回').first()).toBeVisible()

    // 撤回后可以重新提交同一节，仍然停在待审批。
    await submitCover(page, effectiveDate, '家中有事，第二次提交', expectedTeacherName)
    await expect(page.locator('.el-table').getByText('待审批').first()).toBeVisible()

    // 教务在待审批里改人批准。
    await page.evaluate(() => localStorage.clear())
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/substitution')
    await page.getByRole('tab', { name: '待审批' }).click()
    await page.getByRole('button', { name: '批准' }).first().click()
    const dialog = page.locator('.el-dialog', { hasText: '批准代课申请' })
    await expect(dialog).toBeVisible()
    await pickOption(dialog, 'approval-teacher', otherTeacher.name)
    await dialog.locator('[data-testid="approve-comment"]').fill('同意，改由王老师代课')
    await page.locator('[data-testid="approve-confirm"]').click()
    await expect(page.getByText('已批准，代课已生效')).toBeVisible()
    await expect(page.getByText('没有待审批的申请')).toBeVisible()

    // 老师看到已生效，并且分得清期望与实际代课人。
    await page.evaluate(() => localStorage.clear())
    await uiLogin(page, seed.username, seed.password)
    await page.goto('/substitution')
    await expect(page.locator('.el-table').getByText('已生效').first()).toBeVisible()
    await expect(page.locator('.el-table').getByText(`期望：${expectedTeacherName}`)).toBeVisible()
    await expect(page.locator('.el-table').getByText(`实际：${otherTeacher.name}`)).toBeVisible()
    await expect(page.locator('.el-table').getByText('同意，改由王老师代课')).toBeVisible()
  })

  test('教务驳回必须写理由，老师在自己的列表里看到理由', async ({ page }) => {
    const api = await newContext()
    const seed = await seedSchool(api)
    await solveAndPublish(api, seed)

    const entries = await classEntries(api, seed.classId)
    const entry = entries.find((item) => item.dayOfWeek != null && item.id != null)
    expect(entry).toBeTruthy()
    const effectiveDate = dateFor(entry!.dayOfWeek)
    const options = await apiCall(api, await apiLogin(api), 'get', '/teachers/options')
    const substituteName = options.find(
      (item: { id: number }) => item.id === seed.substituteTeacherId,
    ).name

    await uiLogin(page, seed.username, seed.password)
    await page.goto('/substitution')
    await submitCover(page, effectiveDate, '家中有事，等教务处理', substituteName)

    await page.evaluate(() => localStorage.clear())
    await uiLogin(page, 'admin', 'admin123')
    await page.goto('/substitution')
    await page.getByRole('tab', { name: '待审批' }).click()
    await page.getByRole('button', { name: '驳回' }).first().click()
    const dialog = page.locator('.el-dialog', { hasText: '驳回代课申请' })
    await expect(dialog).toBeVisible()
    await page.locator('[data-testid="reject-confirm"]').click()
    await expect(page.getByText('请填写驳回理由')).toBeVisible()
    await dialog.locator('[data-testid="reject-reason"]').fill('该时段已安排教研')
    await page.locator('[data-testid="reject-confirm"]').click()
    await expect(page.getByText('已驳回').first()).toBeVisible()

    await page.evaluate(() => localStorage.clear())
    await uiLogin(page, seed.username, seed.password)
    await page.goto('/substitution')
    await expect(page.locator('.el-table').getByText('已驳回').first()).toBeVisible()
    await expect(page.locator('.el-table').getByText('该时段已安排教研')).toBeVisible()
  })
})
