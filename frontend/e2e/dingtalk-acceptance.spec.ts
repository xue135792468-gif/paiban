import { expect, test } from '@playwright/test'
import {
  DINGTALK_UA,
  apiCall,
  apiLogin,
  classEntries,
  createAccount,
  dateFor,
  fillDateInput,
  installFakeWindowDd,
  newContext,
  pickFirstVisibleOption,
  pickOption,
  seedSchool,
  solveAndPublish,
  trackBrowserErrors,
  uiLogin,
} from './helpers'
import { capture } from './screenshots'

test('手机首次免登提交 → 独立教务电脑改人批准 → 手机我的调代已生效', async ({ browser }) => {
  const api = await newContext()
  const superAdmin = await apiLogin(api)
  const suffix = Math.random().toString(36).slice(2, 10)
  const caseId = `browser_${suffix}`
  const reason = `手机整链请假-${suffix}`
  const finalName = `接课终验-${suffix}`
  let ownedAccountId: number | undefined
  const phone = await browser.newContext({
    userAgent: DINGTALK_UA,
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  })
  const desktop = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  try {
    // Keep the original teacher genuinely unbound: first-login provisioning is part of this test.
    const seed = await seedSchool(api, { teacherAccount: false })
    await apiCall(api, superAdmin, 'put', `/teachers/${seed.teacherId}`, {
      name: `验收申请人-${caseId}`,
      employeeNo: `ACC-${caseId}`,
    })
    const finalTeacher = await apiCall(api, superAdmin, 'post', '/teachers', {
      name: finalName,
      employeeNo: `FINAL-${suffix}`,
      dingtalkUserId: `dd-final-${suffix}`,
    })
    const academic = { username: `acceptance_jw_${suffix}`, password: 'AcceptanceOnly8!' }
    await createAccount(api, { ...academic, displayName: `验收教务-${suffix}`, roles: ['admin'] })
    await solveAndPublish(api, seed)
    const entries = await classEntries(api, seed.classId)
    const entry = entries.find((item) => item.teacherId === seed.teacherId)!
    expect(entry).toBeTruthy()
    const date = dateFor(entry.dayOfWeek)
    const options = await apiCall(api, superAdmin, 'get', '/teachers/options')
    const expectedName = options.find((item: { id: number }) => item.id === seed.substituteTeacherId).name
    expect(new Set([seed.teacherId, seed.substituteTeacherId, finalTeacher.id]).size).toBe(3)
    await apiCall(api, superAdmin, 'put', '/integrations/dingtalk/config', {
      enabled: true,
      appKey: 'acceptance-key',
      appSecret: 'acceptance-secret',
      agentId: '1000',
      corpId: 'acceptance-corp',
      syncStrategy: 'local',
    })
    await installFakeWindowDd(phone, `fake:acceptance:${caseId}`)
    const teacherPage = await phone.newPage()
    const teacherErrors = trackBrowserErrors(teacherPage)
    const authResponsePromise = teacherPage.waitForResponse(
      (response) => response.request().method() === 'POST' && response.url().endsWith('/api/auth/dingtalk'),
    )
    await teacherPage.goto('/login')
    const authResponse = await authResponsePromise
    expect(authResponse.status()).toBe(200)
    const auth = await authResponse.json()
    ownedAccountId = auth.user.id
    expect(auth.user).toMatchObject({ teacherId: seed.teacherId, roles: ['teacher'], loginType: 'DINGTALK' })
    await teacherPage.waitForURL((url) => !url.pathname.startsWith('/login'))
    // 手机默认落到本人课表后，走真实可见导航进入「我的调代」，不再 page.goto 绕过入口。
    await teacherPage.getByRole('button', { name: '打开导航', exact: true }).click()
    const navDrawer = teacherPage.getByRole('dialog', { name: '导航', exact: true })
    await navDrawer.getByRole('menuitem', { name: '调代课', exact: true }).click()
    await expect(teacherPage).toHaveURL(/\/substitution\/requests(?:\?.*)?$/)
    await expect(navDrawer).toBeHidden()
    await expect(teacherPage.getByRole('tab', { name: '我的调代' })).toBeVisible()
    await expect(teacherPage.getByRole('tab', { name: '待审批' })).toHaveCount(0)
    await fillDateInput(teacherPage.getByRole('combobox', { name: '请假日期' }), date)
    await pickFirstVisibleOption(
      teacherPage.locator('.el-form-item', { hasText: '我的课节' }).locator('.el-select'),
    )
    await pickOption(
      teacherPage.locator('.el-form-item', { hasText: '期望代课' }),
      'expected-substitute',
      expectedName,
    )
    await teacherPage.getByPlaceholder('如：家中有事，需要请假半天').fill(reason)
    await capture(teacherPage, 't08-phone-submit')
    const submittedPromise = teacherPage.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && response.url().endsWith('/api/substitution-requests'),
    )
    await teacherPage.getByRole('button', { name: '提交申请' }).click()
    const submittedResponse = await submittedPromise
    expect(submittedResponse.status()).toBe(200)
    const submitted = await submittedResponse.json()
    expect(submitted).toMatchObject({
      status: 'SUBMITTED',
      expectedSubstituteTeacherId: seed.substituteTeacherId,
    })
    const mine = teacherPage.locator('.el-table__row').filter({ hasText: reason })
    await expect(mine).toHaveCount(1)
    await expect(mine.getByText('待审批', { exact: true })).toBeVisible()

    const academicPage = await desktop.newPage()
    const academicErrors = trackBrowserErrors(academicPage)
    await uiLogin(academicPage, academic.username, academic.password)
    await academicPage.goto('/substitution/requests')
    await academicPage.getByRole('tab', { name: '待审批' }).click()
    const pending = academicPage.locator('.el-table__row').filter({ hasText: reason })
    await expect(pending).toHaveCount(1)
    await pending.getByRole('button', { name: '批准', exact: true }).click()
    const dialog = academicPage.getByRole('dialog', { name: '批准代课申请' })
    await pickOption(dialog, 'approval-teacher', finalName)
    await dialog.getByTestId('approve-comment').fill('改由最终接课老师执行')
    await capture(academicPage, 't08-desktop-change-teacher')
    const approvedPromise = academicPage.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith(`/api/substitution-requests/${submitted.id}/approve`),
    )
    await dialog.getByTestId('approve-confirm').click()
    const approvedResponse = await approvedPromise
    expect(approvedResponse.status()).toBe(200)
    expect(await approvedResponse.json()).toMatchObject({
      status: 'EFFECTIVE',
      expectedSubstituteTeacherId: seed.substituteTeacherId,
      substituteTeacherId: finalTeacher.id,
      operatorUserId: ownedAccountId,
    })
    await expect(pending).toHaveCount(0)

    await teacherPage.reload()
    const effective = teacherPage.locator('.el-table__row').filter({ hasText: reason })
    await expect(effective).toHaveCount(1)
    await expect(effective.getByText('已生效', { exact: true })).toBeVisible()
    await expect(effective.getByText(`期望：${expectedName}`, { exact: true })).toBeVisible()
    await expect(effective.getByText(`实际：${finalName}`, { exact: true })).toBeVisible()
    await expect(effective.getByText('改由最终接课老师执行', { exact: true })).toBeVisible()
    // The app scrolls inside el-main, so fullPage alone still captures the blank form above.
    const actualTeacher = effective.getByText(`实际：${finalName}`, { exact: true })
    await actualTeacher.scrollIntoViewIfNeeded()
    await expect(actualTeacher).toBeInViewport()
    await capture(teacherPage, 't08-phone-effective-details')
    const effectiveStatus = effective.getByText('已生效', { exact: true })
    await effectiveStatus.scrollIntoViewIfNeeded()
    await expect(effectiveStatus).toBeInViewport()
    await capture(teacherPage, 't08-phone-effective')
    const actual = await apiCall(api, auth.token, 'get', `/timetable/class/${seed.classId}`, undefined, {
      date,
    })
    expect(actual.find((item: { id: number }) => item.id === submitted.entryId)).toMatchObject({
      teacherId: finalTeacher.id,
      substituted: true,
    })
    expect(teacherErrors).toEqual([])
    expect(academicErrors).toEqual([])
  } finally {
    await phone.close()
    await desktop.close()
    // This synthetic identity is intentionally absent from directory sync. Disable only our account.
    try {
      if (ownedAccountId != null) await apiCall(api, superAdmin, 'post', `/users/${ownedAccountId}/disable`)
    } finally {
      try {
        await apiCall(api, superAdmin, 'put', '/integrations/dingtalk/config', { enabled: false })
      } finally {
        await api.dispose()
      }
    }
  }
})
