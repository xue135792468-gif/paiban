import { expect, test } from '@playwright/test'
import { apiCall, apiLogin, newContext, seedSchool, uiLogin } from './helpers'

/**
 * Ticket 12 at the API boundary plus the page entry point. The solver run is kept
 * deliberately small (one class, four lessons, five seconds): the shared-teacher
 * total maths and the 409 are covered deterministically by the backend integration
 * test, while this spec locks the request/response contract and the visible picker.
 */
test('按年级发起排课任务并返回年级范围', async ({ page }) => {
  const context = await newContext()
  const seed = await seedSchool(context)
  const token = await apiLogin(context)

  const classes = await apiCall(context, token, 'get', `/classes?semesterId=${seed.semesterId}`)
  const seededClass = classes.find((row: { id: number }) => row.id === seed.classId)
  const gradeId = seededClass.gradeId as number
  // A second grade with no assignments: selecting only the first one must still work
  // and the job DTO must echo the narrowed scope.
  await apiCall(context, token, 'post', `/grades?semesterId=${seed.semesterId}`, {
    name: `高二-${seed.semesterId}`,
    sortOrder: 2,
  })

  const job = await apiCall(
    context,
    token,
    'post',
    `/scheduling-jobs?semesterId=${seed.semesterId}`,
    { maxSeconds: 5, gradeIds: [gradeId] },
  )
  expect(job.gradeIds).toEqual([gradeId])

  await expect
    .poll(async () => (await apiCall(context, token, 'get', `/scheduling-jobs/${job.id}`)).status, {
      timeout: 60_000,
    })
    .toBe('COMPLETED')

  const finished = await apiCall(context, token, 'get', `/scheduling-jobs/${job.id}`)
  expect(finished.gradeIds).toEqual([gradeId])
  const draft = await apiCall(context, token, 'get', `/timetable-drafts/${finished.draftId}`)
  expect(draft.lessons.length).toBeGreaterThan(0)
  expect(
    draft.lessons.every((lesson: { classId: number }) => lesson.classId === seed.classId),
  ).toBe(true)

  // The workbench exposes the same scope choice; both grades are listed and the
  // default remains the whole school.
  await uiLogin(page, 'admin', 'admin123')
  await page.goto('/workbench')
  await expect(page.getByTestId('grade-scope-picker')).toBeVisible()
  await expect(page.getByTestId('grade-scope-hint')).toContainText('本次排全部 2 个年级')
})
