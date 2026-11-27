import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, newContext, pickOption, SUPER_ADMIN, trackBrowserErrors, uiLogin } from './helpers'
import { buildXlsx } from './xlsx'

test('教务管理员从任课方阵导入空学期、撤销删除、使用班主任组并完成排课', async ({ page }, testInfo) => {
  const api = await newContext()
  const errors = trackBrowserErrors(page)
  const suffix = Math.random().toString(36).slice(2, 8)
  const gradeName = `八年级${suffix}`
  const className = `${gradeName}（1）班`
  const teacherName = `任课班主任${suffix}`
  const courseName = `道德与法治${suffix}`
  const record = async (step: string) => {
    await testInfo.attach(`${step}.png`, { body: await page.screenshot({ animations: 'disabled', fullPage: true }), contentType: 'image/png' })
    await testInfo.attach(`${step}.aria.txt`, { body: await page.locator('body').ariaSnapshot(), contentType: 'text/plain' })
  }

  try {
    const token = await apiLogin(api)
    const semester = await apiCall(api, token, 'post', '/semesters', {
      name: `任课表验收${suffix}`, startDate: '2030-09-02', endDate: '2031-01-20',
    })
    const semesterId: number = semester.id
    await apiCall(api, token, 'post', `/semesters/${semesterId}/set-current`)
    await apiCall(api, token, 'post', `/semesters/${semesterId}/periods/default`, {
      morningCount: 4, afternoonCount: 4, eveningCount: 0,
    })
    const grade = await apiCall(api, token, 'post', '/grades', { name: gradeName }, { semesterId })

    await uiLogin(page, SUPER_ADMIN.username, SUPER_ADMIN.password)
    await page.getByRole('menuitem', { name: '数据管理', exact: true }).click()
    await page.getByRole('navigation', { name: '排课输入' }).getByRole('link', { name: '任课表' }).click()
    await expect(page.getByText('这个年级还没有行政班')).toBeVisible()
    await page.getByRole('button', { name: '导入', exact: true }).click()
    const upload = page.getByRole('dialog', { name: '导入任课表 Excel' })
    await upload.getByLabel('选择 Excel 文件').setInputFiles({
      name: '学校任课表.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: buildXlsx([
        ['学校八年级任课表'],
        ['班 级', '人数', '班主任', `道德与法
治${suffix}`],
        ['1', '42', teacherName, teacherName],
      ], { sheetName: '任课表', merges: ['A1:D1'] }),
    })
    await upload.getByRole('button', { name: '预演', exact: true }).click()
    await expect(upload.getByText('待填写周课时数')).toBeVisible()
    await expect(upload.getByRole('button', { name: '确认导入' })).toBeDisabled()
    const before = await apiCall(api, token, 'get', '/classes', undefined, { semesterId })
    expect(before).toEqual([])
    await upload.getByRole('textbox', { name: `${courseName}本次周课时数` }).fill('2')
    await upload.getByText('同步保存为课程默认周课时', { exact: true }).click()
    await upload.getByRole('button', { name: '更新预演' }).click()
    await expect(upload.getByRole('button', { name: '确认导入' })).toBeEnabled()
    await expect(upload.getByRole('row', { name: new RegExp(className) })).toContainText(teacherName)
    await record('01-preview')
    await upload.getByRole('button', { name: '确认导入' }).click()
    await expect(upload).toBeHidden()

    const matrix = page.getByRole('region', { name: '任课表', exact: true })
    const classRow = matrix.getByRole('row').filter({ has: page.getByRole('cell', { name: className, exact: true }) })
    await expect(classRow.getByRole('cell', { name: '42', exact: true })).toBeVisible()
    await expect(classRow.getByRole('cell', { name: teacherName, exact: true }).first()).toBeVisible()
    const task = matrix.getByRole('button', { name: `${className} · ${courseName} · ${teacherName}` })
    await expect(task).toBeVisible()
    await record('02-imported')
    await task.click()
    await page.getByRole('dialog', { name: `${className} · ${courseName}` }).getByRole('button', { name: '删除' }).click()
    await expect(page.getByTestId('delete-impact-delete')).toBeVisible()
    await page.getByTestId('delete-impact-delete').click()
    await expect(matrix.getByRole('button', { name: `${className} · ${courseName} · 添加任课教师` })).toBeVisible()
    await record('03-delete-undo')
    await matrix.getByRole('button', { name: '撤销' }).click()
    await expect(task).toBeVisible()

    const [download] = await Promise.all([
      page.waitForEvent('download'), matrix.getByRole('button', { name: '导出', exact: true }).click(),
    ])
    expect(download.suggestedFilename()).toBe('任课表.xlsx')
    await matrix.getByRole('button', { name: '导入', exact: true }).click()
    await upload.getByLabel('选择 Excel 文件').setInputFiles(await download.path())
    await upload.getByRole('button', { name: '预演', exact: true }).click()
    await expect(upload.getByRole('row', { name: new RegExp(className) })).toContainText(`${courseName} · ${teacherName} · 2 节 · 不变`)
    await upload.getByRole('button', { name: '取消' }).click()

    await page.getByRole('menuitem', { name: '排课规则', exact: true }).click()
    await page.getByTestId('rule-groups-toggle').click()
    const groups = await apiCall(api, token, 'get', '/rule-groups', undefined, { type: 'TEACHER' })
    const schoolGroup = groups.find((group: { name: string }) => group.name === '班主任')
    const gradeGroup = groups.find((group: { name: string }) => group.name === `${gradeName}班主任`)
    expect(schoolGroup.members.map((member: { name: string }) => member.name)).toEqual([teacherName])
    expect(gradeGroup.autoFilled).toBe(true)
    await expect(page.getByTestId(`rule-group-row-${schoolGroup.id}`)).toContainText(teacherName)
    await expect(page.getByTestId(`rule-group-row-${gradeGroup.id}`)).toContainText(teacherName)
    await record('04-homeroom-groups')
    await page.getByTestId('rule-new').click()
    const ruleForm = page.getByTestId('rule-form')
    await pickOption(ruleForm, 'rule-subject', `教师组 · ${gradeName}班主任`)
    await ruleForm.getByTestId('rule-days').click()
    const dayOptions = page.locator('.el-select-dropdown:visible').last()
    await dayOptions.getByText('周一', { exact: true }).click()
    await ruleForm.getByTestId('rule-preview').click()
    await expect(dayOptions).toBeHidden()
    await ruleForm.getByTestId('rule-period-1').click()
    await pickOption(ruleForm, 'rule-type', '不上课')
    await ruleForm.getByTestId('rule-submit').click()
    const ruleSaved = page.waitForResponse((response) =>
      response.url().includes('/api/constraints') && response.request().method() === 'PUT' && response.ok())
    await page.getByTestId('rule-save').click()
    await ruleSaved
    const ruleState = await apiCall(api, token, 'get', '/constraints/state', undefined, { semesterId })
    const teacherRule = ruleState.configs.find((config: { type: string }) => config.type === 'TEACHER_UNAVAILABLE')
    expect(teacherRule.entries).toHaveLength(1)
    expect(teacherRule.entries[0].params.teacherGroupId).toBe(gradeGroup.id)

    await page.getByRole('menuitem', { name: '课表工作台', exact: true }).click()
    const duration = page.getByRole('spinbutton')
    await duration.fill('5')
    await duration.press('Tab')
    const jobCreated = page.waitForResponse((response) =>
      new URL(response.url()).pathname === '/api/scheduling-jobs' && response.request().method() === 'POST' && response.ok())
    await page.getByRole('button', { name: '开始排课' }).click()
    const job = await (await jobCreated).json()
    const getJob = () => apiCall(api, token, 'get', `/scheduling-jobs/${job.id}`)
    await expect.poll(async () => (await getJob()).status, { timeout: 60_000 }).toBe('COMPLETED')
    const finished = await getJob()
    const draft = await apiCall(api, token, 'get', `/timetable-drafts/${finished.draftId}`)
    const periods: { id: number; dayOfWeek: number; periodNo: number }[] =
      await apiCall(api, token, 'get', `/semesters/${semesterId}/periods`)
    const unavailable = periods.find((item) => item.dayOfWeek === 1 && item.periodNo === 1)?.id
    expect(unavailable).toBeDefined()
    expect(draft.lessons).toHaveLength(2)
    expect(draft.lessons.every((lesson: { periodId: number | null }) =>
      lesson.periodId !== null && lesson.periodId !== unavailable)).toBe(true)
    await expect(page.getByRole('button', { name: '打开草稿' }).first()).toBeVisible()
    await record('05-solved')
    await testInfo.attach('proof.json', { contentType: 'application/json', body: JSON.stringify({
      semesterId, gradeId: grade.id, className, teacherName, courseName, jobId: job.id,
      draftId: finished.draftId, lessons: draft.lessons, unavailable, rule: teacherRule.entries[0].params, errors,
    }, null, 2) })
    expect(errors).toEqual([])
  } finally {
    await api.dispose()
  }
})
