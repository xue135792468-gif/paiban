import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SubstitutionRequestForm from './SubstitutionRequestForm.vue'
import { today, weekdayOf } from './types'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

const TEACHERS = [
  { id: 1, name: '张老师' },
  { id: 2, name: '李老师' },
]

const ENTRY = {
  id: 5,
  classId: 3,
  courseId: 7,
  courseName: '数学',
  className: '高一1班',
  teacherId: 1,
  teacherName: '张老师',
  periodId: 2,
  periodNo: 3,
  dayOfWeek: weekdayOf(today()),
  substituted: false,
}

async function mountForm(): Promise<VueWrapper> {
  const wrapper = mount(SubstitutionRequestForm, {
    attachTo: document.body,
    props: { myTeacherId: 1, teachers: TEACHERS },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('教师自助申请表单', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/timetable/teacher/1') {
        return Promise.resolve({ data: [ENTRY] })
      }
      return Promise.resolve({ data: [] })
    })
  })

  it('只有代课一种类型，并提示想调课写在原因里由教务处理', async () => {
    const wrapper = await mountForm()

    const radioLabels = wrapper.findAll('.el-radio-button').map((item) => item.text())
    expect(radioLabels).toEqual([])
    expect(wrapper.text()).toContain('想调课请写明原因，由教务处理')
    expect(wrapper.text()).toContain('代课')
  })

  it('没有选代课教师时不发请求，并提示请选择代课教师', async () => {
    const wrapper = await mountForm()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    selects[0].vm.$emit('update:modelValue', ENTRY.id)
    await flushPromises()
    await wrapper.find('textarea').setValue('家中有事')
    const submit = wrapper.findAll('button').find((item) => item.text() === '提交申请')
    await submit!.trigger('click')
    await flushPromises()

    expect(httpMock.post).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请选择代课教师')
  })

  it('提交后按代课申请落库，提示等待教务审批并通知列表刷新', async () => {
    httpMock.post.mockResolvedValue({ data: { id: 9, status: 'SUBMITTED' } })
    const wrapper = await mountForm()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    selects[0].vm.$emit('update:modelValue', ENTRY.id)
    await flushPromises()
    selects[1].vm.$emit('update:modelValue', 2)
    await wrapper.find('textarea').setValue('家中有事')
    const submit = wrapper.findAll('button').find((item) => item.text() === '提交申请')
    await submit!.trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/substitution-requests', {
      entryId: ENTRY.id,
      effectiveDate: today(),
      reason: '家中有事',
      type: 'COVER',
      expectedSubstituteTeacherId: 2,
    })
    expect(document.body.textContent).toContain('申请已提交，等待教务审批')
    expect(document.body.textContent).not.toContain('已直接生效')
    expect(wrapper.emitted('submitted')).toHaveLength(1)
  })
})
