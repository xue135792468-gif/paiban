import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { expect, it, vi } from 'vitest'
import TeachingMatrixCellDialog from './TeachingMatrixCellDialog.vue'

const http = vi.hoisted(() => ({ post: vi.fn(), put: vi.fn() }))
vi.mock('@/api/http', () => ({ http }))

it('submits the selected semester and the editable course default when creating a task', async () => {
  http.post.mockResolvedValue({})
  const dialog = mount(TeachingMatrixCellDialog, {
    global: { plugins: [ElementPlus] },
    props: {
      selection: {
        semesterId: 12,
        adminClass: { id: 3, gradeId: 1, name: '八（1）班', studentCount: 42, headTeacherName: null },
        course: { id: 4, name: '语文', weeklyHours: 5, active: true },
      },
      teachers: [{ id: 8, name: '张老师' }],
    },
  })
  await flushPromises()
  expect(dialog.get('input[inputmode="numeric"]').element).toHaveProperty('value', '5')
  dialog.getComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 8)
  await dialog.get('input[inputmode="numeric"]').setValue('6')
  await dialog.findAll('button').find((button) => button.text() === '保存')?.trigger('click')
  await flushPromises()
  expect(http.post).toHaveBeenCalledWith('/assignments', {
    classId: 3, courseId: 4, teacherId: 8, weeklyLessons: 6,
  }, { params: { semesterId: 12 } })
  expect(dialog.emitted('saved')).toHaveLength(1)
  dialog.unmount()
})
