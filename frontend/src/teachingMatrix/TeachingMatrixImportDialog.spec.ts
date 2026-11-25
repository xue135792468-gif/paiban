import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { expect, it, vi } from 'vitest'
import TeachingMatrixImportDialog from './TeachingMatrixImportDialog.vue'
import type { MatrixReport } from './importApi'

const http = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('@/api/http', () => ({ http }))

it('requires a preview of the completed hours before permitting commit', async () => {
  const counts = { created: 1, updated: 0, unchanged: 0 }
  const initial: MatrixReport = {
    summary: { classes: counts, assignments: counts, teachers: counts, courses: counts },
    courses: [{ name: '数学', action: 'CREATE', defaultHours: null, effectiveHours: null, missingHours: true }],
    teachers: [{ name: '张老师', action: 'CREATE' }],
    rows: [], errors: [], warnings: [],
  }
  const updated = { ...initial, courses: [{ ...initial.courses[0], effectiveHours: 5, missingHours: false }] }
  http.post.mockResolvedValueOnce({ status: 200, data: initial })
    .mockResolvedValueOnce({ status: 200, data: updated })
    .mockResolvedValueOnce({ status: 200, data: updated })
  const dialog = mount(TeachingMatrixImportDialog, {
    global: { plugins: [ElementPlus] },
    props: { context: { semesterId: 1, gradeId: 2 }, target: '春季 · 八年级' },
  })
  await flushPromises()
  const file = dialog.get('input[type=file]')
  Object.defineProperty(file.element, 'files', { value: [new File(['data'], '任课表.xlsx')] })
  await file.trigger('change')
  const button = (name: string) => {
    const match = dialog.findAll('button').find((item) => item.text() === name)
    if (!match) throw new Error(`找不到按钮 ${name}`)
    return match
  }
  await button('预演').trigger('click')
  await flushPromises()
  expect(dialog.text()).toContain('待填写周课时数')
  expect(button('确认导入').attributes('disabled')).toBeDefined()
  await dialog.get('input[inputmode=numeric]').setValue('5')
  expect(button('确认导入').attributes('disabled')).toBeDefined()
  await button('更新预演').trigger('click')
  await flushPromises()
  expect(button('确认导入').attributes('disabled')).toBeUndefined()
  await button('确认导入').trigger('click')
  await flushPromises()
  expect(http.post.mock.calls.map(([url]) => url)).toEqual([
    '/assignments/matrix/import/preview', '/assignments/matrix/import/preview', '/assignments/matrix/import/commit',
  ])
  expect(dialog.emitted('imported')).toHaveLength(1)
  dialog.unmount()
})
