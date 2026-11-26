import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { WORKBENCH_MANAGER_PERMISSIONS } from '@/constants/permissions'
import SubstitutionWorkbenchView from './SubstitutionWorkbenchView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const PAST_LESSON = {
  id: 1,
  classId: 10,
  courseId: 20,
  courseName: '数学',
  className: '高一1班',
  teacherId: 7,
  teacherName: '王建国',
  periodId: 11,
  periodNo: 1,
  dayOfWeek: 1, // Monday (before Thursday 2026-03-05)
  substituted: false,
}

async function mountWorkbench(): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    enabled: true,
    roles: [],
    permissions: WORKBENCH_MANAGER_PERMISSIONS,
  }
  const wrapper = mount(SubstitutionWorkbenchView, {
    global: { plugins: [pinia, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('SubstitutionWorkbenchView F-05 跳到下周与空态区分', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-03-05T10:00:00'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('本周只有过去日期的课时，点击跳到下周会推进到下周一并重新请求课表', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/teachers/options') return Promise.resolve({ data: [{ id: 7, name: '王建国' }] })
      if (url === '/timetable/teacher/7') {
        return Promise.resolve({ data: [PAST_LESSON] })
      }
      return Promise.resolve({ data: [] })
    })

    const wrapper = await mountWorkbench()
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 7)
    await flushPromises()

    expect(wrapper.get('[data-testid="week-no-adjustable-callout"]').text()).toContain(
      '本周剩余日期没有可调整的课',
    )

    await wrapper.get('[data-testid="jump-next-week"]').trigger('click')
    await flushPromises()

    const teacherCalls = httpMock.get.mock.calls.filter((call) => call[0] === '/timetable/teacher/7')
    expect(teacherCalls.at(-1)?.[1]).toEqual({
      params: { view: 'latest', date: '2026-03-09' },
    })
    // 下周一开始后周一是 2026-03-09 (>= 2026-03-05)，不再显示过去课提示
    expect(wrapper.find('[data-testid="week-no-adjustable-callout"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('本周返回空课表时通过 baseline 区分本学期无排课与本周无课', async () => {
    httpMock.get.mockImplementation((url: string, config?: { params?: Record<string, string> }) => {
      if (url === '/teachers/options') return Promise.resolve({ data: [{ id: 7, name: '王建国' }] })
      if (url === '/timetable/teacher/7') {
        if (config?.params?.view === 'baseline') return Promise.resolve({ data: [] })
        return Promise.resolve({ data: [] })
      }
      return Promise.resolve({ data: [] })
    })

    const wrapper = await mountWorkbench()
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 7)
    await flushPromises()

    expect(wrapper.get('[data-testid="no-semester-lessons"]').text()).toContain(
      '该教师本学期没有排课',
    )
    wrapper.unmount()
  })
})
