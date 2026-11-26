import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { expect, it, vi } from 'vitest'
import TeachingMatrixView from './TeachingMatrixView.vue'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http }))

it('confirms the assignment impact and offers the exact deletion batch for immediate undo', async () => {
  let present = true
  const assignment = {
    id: 30, classId: 3, courseId: 4, teacherId: 5, teacherName: '张老师', weeklyLessons: 4, active: true,
  }
  const responses: Record<string, unknown> = {
    '/semesters': [{ id: 1, name: '春季', active: true, current: true }],
    '/grades': [{ id: 2, name: '八年级', active: true }],
    '/classes': [{ id: 3, name: '八（1）班', gradeId: 2, studentCount: 42, headTeacherName: '李老师' }],
    '/courses': [{ id: 4, name: '语文', weeklyHours: 5, active: true }],
    '/teachers/options': [{ id: 5, name: '张老师' }],
    '/assignments/30/delete-impact': {
      label: '八（1）班 · 语文', canDelete: true, canDisable: false, draftRefs: 2,
      cascadeAssignments: 0, cascadeClasses: 0,
    },
  }
  http.get.mockImplementation(async (url: string) => ({ data: url === '/assignments' ? (present ? [assignment] : []) : responses[url] }))
  http.delete.mockImplementation(async () => {
    present = false
    return { data: { batchId: 'delete-30', deletedCount: 1 } }
  })
  http.post.mockImplementation(async () => {
    present = true
    return { data: { batchId: 'delete-30', restoredCount: 1 } }
  })
  const wrapper = mount(TeachingMatrixView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  const existing = 'button[aria-label="八（1）班 · 语文 · 张老师"]'
  expect(wrapper.get(existing).text()).toContain('4 节/周')
  await wrapper.get(existing).trigger('click')
  await flushPromises()
  await wrapper.findAll('button').find((button) => button.text() === '删除')?.trigger('click')
  await flushPromises()

  expect(http.get).toHaveBeenCalledWith('/assignments/30/delete-impact')
  expect(http.delete).not.toHaveBeenCalled()
  await wrapper.get('[data-testid="delete-impact-delete"]').trigger('click')
  await flushPromises()
  expect(wrapper.find(existing).exists()).toBe(false)
  const undo = wrapper.get('[role="status"]').findAll('button').find((button) => button.text() === '撤销')
  await undo?.trigger('click')
  await flushPromises()
  expect(http.post).toHaveBeenCalledWith('/recent-deletions/delete-30/restore')
  expect(wrapper.get(existing).text()).toContain('张老师')
  wrapper.unmount()
})
