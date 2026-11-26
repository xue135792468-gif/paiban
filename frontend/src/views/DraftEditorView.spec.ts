import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import DraftEditorView from './DraftEditorView.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const periods = [
  { id: 101, dayOfWeek: 1, periodNo: 1, label: '第1节', startTime: '08:00:00', endTime: '08:45:00' },
  { id: 102, dayOfWeek: 1, periodNo: 2, label: '第2节', startTime: '08:55:00', endTime: '09:40:00' },
  { id: 103, dayOfWeek: 1, periodNo: 3, label: '第3节', startTime: '10:00:00', endTime: '10:45:00' },
  { id: 104, dayOfWeek: 1, periodNo: 4, label: '第4节', startTime: '10:55:00', endTime: '11:40:00' },
]

const lessons = [
  {
    id: 11,
    assignmentId: 1,
    courseId: 1,
    courseName: '语文',
    teacherId: 101,
    teacherName: '陈老师',
    classId: 1,
    className: '高一(1)班',
    studentCount: 40,
    periodId: 101,
    dayOfWeek: 1,
    periodNo: 1,
    periodLabel: '周一第1节',
    locked: false,
  },
  {
    id: 21,
    assignmentId: 2,
    courseId: 2,
    courseName: '数学',
    teacherId: 102,
    teacherName: '王老师',
    classId: 2,
    className: '高一(2)班',
    studentCount: 40,
    periodId: 101,
    dayOfWeek: 1,
    periodNo: 1,
    periodLabel: '周一第1节',
    locked: false,
  },
  {
    id: 22,
    assignmentId: 3,
    courseId: 3,
    courseName: '英语',
    teacherId: 103,
    teacherName: '李老师',
    classId: 2,
    className: '高一(2)班',
    studentCount: 40,
    periodId: 102,
    dayOfWeek: 1,
    periodNo: 2,
    periodLabel: '周一第2节',
    locked: false,
  },
  {
    id: 31,
    assignmentId: 4,
    courseId: 2,
    courseName: '数学',
    teacherId: 102,
    teacherName: '王老师',
    classId: 3,
    className: '高一(3)班',
    studentCount: 40,
    periodId: 103,
    dayOfWeek: 1,
    periodNo: 3,
    periodLabel: '周一第3节',
    locked: false,
  },
]

const validation = {
  valid: false,
  hardConflicts: 2,
  conflicts: [
    { type: 'TEACHER_CONFLICT', message: '王老师 在高一(2)班 周一第1节 冲突', lessonId: 21 },
    { type: 'TEACHER_CONFLICT', message: '王老师 在高一(3)班 周一第3节 冲突', lessonId: 31 },
  ],
  publishable: false,
  blockers: [
    { code: 'HARD_CONFLICTS', message: '课表存在 2 处排课冲突', count: 2, action: 'NONE' },
  ],
  rulesFingerprint: 'fp-1',
}

async function mountDraftView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    enabled: true,
    roles: [],
    permissions: ['timetable:edit', 'timetable:publish', 'scheduling:run'],
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/workbench/drafts/:id', component: { template: '<div />' } }],
  })
  await router.push('/workbench/drafts/5')
  await router.isReady()

  const wrapper = mount(DraftEditorView, {
    attachTo: document.body,
    global: { plugins: [pinia, router, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('DraftEditorView F-03 逐条定位与 F-06 选择位置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/timetable-drafts/5') {
        return Promise.resolve({
          data: {
            draft: {
              id: 5,
              semesterId: 1,
              name: '测试草稿',
              status: 'DRAFT',
              scoreHard: -2,
              scoreSoft: 0,
              createdAt: '2026-01-01T00:00:00Z',
              lessonCount: lessons.length,
              version: 1,
            },
            lessons,
            periods,
            blockedSlots: [{ classId: 2, periodId: 104, reason: '全校周五教研' }],
            rulesFingerprint: 'fp-1',
          },
        })
      }
      if (url === '/timetable-drafts/5/validation') {
        return Promise.resolve({ data: validation })
      }
      if (url === '/courses') {
        return Promise.resolve({ data: [{ id: 1 }, { id: 2 }, { id: 3 }] })
      }
      if (url.includes('/move-targets')) {
        return Promise.resolve({
          data: {
            targets: [
              { periodId: 101, available: true, conflicts: [] },
              { periodId: 102, available: false, conflicts: [{ type: 'CLASS_CONFLICT', message: '本班有课', lessonId: 22 }] },
              { periodId: 103, available: false, conflicts: [{ type: 'TEACHER_CONFLICT', message: '王老师此时在高一(3)班有数学课', lessonId: 31 }] },
              { periodId: 104, available: false, conflicts: [{ type: 'CLASS_UNAVAILABLE', message: '全校周五教研', lessonId: 21 }] },
            ],
          },
        })
      }
      return Promise.resolve({ data: [] })
    })
  })

  it('点击「逐条定位」切换班级、滚动并循环高亮冲突课节', async () => {
    const scrollSpy = vi.fn()
    HTMLElement.prototype.scrollIntoView = scrollSpy

    const wrapper = await mountDraftView()
    // 初始落在高一(1)班，该班没有冲突
    expect(wrapper.find('[data-lesson-id="11"]').exists()).toBe(true)
    expect(wrapper.find('[data-lesson-id="21"]').exists()).toBe(false)

    const locateBtn = wrapper.get('[data-testid="blocker-action-HARD_CONFLICTS"]')
    expect(locateBtn.text()).toContain('逐条定位')

    await locateBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-lesson-id="21"][data-highlighted="true"]').exists()).toBe(true)
    expect(locateBtn.text()).toContain('逐条定位（1/2）')
    expect(scrollSpy).toHaveBeenCalled()

    await locateBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-lesson-id="31"][data-highlighted="true"]').exists()).toBe(true)
    expect(locateBtn.text()).toContain('逐条定位（2/2）')

    // 再次点击回到第一条冲突
    await locateBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-lesson-id="21"][data-highlighted="true"]').exists()).toBe(true)
    expect(locateBtn.text()).toContain('逐条定位（1/2）')
    wrapper.unmount()
  })

  it('点击冲突课节打开「冲突原因」弹窗并支持「知道了」与「换到别的位置」', async () => {
    const wrapper = await mountDraftView()

    // 定位到高一(2)班冲突课节 21，然后点击该课节打开冲突原因弹窗
    await wrapper.get('[data-testid="blocker-action-HARD_CONFLICTS"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-lesson-id="21"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('冲突原因')
    expect(wrapper.text()).toContain('王老师 在高一(2)班 周一第1节 冲突')
    await wrapper.get('[data-testid="conflict-ack"]').trigger('click')
    await flushPromises()
    const conflictDialog = wrapper.findComponent({ name: 'DraftConflictDialog' })
    expect(conflictDialog.props('modelValue')).toBe(false)

    // 重新点开后点「换到别的位置」进入选择位置弹窗，且不把别班占用判成不可用
    await wrapper.get('[data-lesson-id="21"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="conflict-relocate"]').trigger('click')
    await flushPromises()

    const chip102 = wrapper.get('.period-chip[data-period-id="102"]')
    const chip103 = wrapper.get('.period-chip[data-period-id="103"]')
    const chip104 = wrapper.get('.period-chip[data-period-id="104"]')
    expect((chip102.element as HTMLButtonElement).disabled).toBe(false)
    expect(chip102.text()).toContain('本班已占用')
    expect((chip103.element as HTMLButtonElement).disabled).toBe(true)
    expect(chip103.text()).toContain('教师有课')
    expect((chip104.element as HTMLButtonElement).disabled).toBe(true)
    expect(chip104.text()).toContain('禁排时段')
    wrapper.unmount()
  })
})
