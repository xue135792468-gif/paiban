import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ActionChooser from './ActionChooser.vue'
import type { WorkbenchCandidate, WorkbenchCandidateList } from './types'

function candidate(overrides: Partial<WorkbenchCandidate>): WorkbenchCandidate {
  return {
    teacherId: 1,
    name: '老师',
    subject: '语文',
    sameSubject: true,
    busyAtPeriod: false,
    busyReason: null,
    crossSubject: false,
    ...overrides,
  }
}

function mountChooser(candidates: WorkbenchCandidate[]) {
  const candidateList: WorkbenchCandidateList = {
    entryId: 100,
    lessonDate: '2026-03-05',
    classId: 1,
    className: '高一1班',
    courseId: 2,
    courseName: '语文',
    courseSubject: '语文',
    currentTeacherId: 99,
    currentTeacherName: '原老师',
    periodId: 10,
    periodNo: 1,
    periodLabel: '周四第1节',
    legacyLocked: false,
    activeRequestId: null,
    candidates,
  }

  return mount(ActionChooser, {
    props: {
      action: 'cover',
      hint: '3月5日 周四第1节',
      lessonDate: '2026-03-05',
      candidatesLoading: false,
      candidatesError: '',
      candidateList,
      candidate: null,
      coverReason: '',
      swapReason: '',
      chips: [],
      targetDate: '',
      targetSlots: [],
      targetPeriodId: null,
      targetsLoading: false,
      targetsError: '',
      restorable: false,
    },
  })
}

describe('ActionChooser U-08 候选折叠与排序配合', () => {
  it('空闲教师排在前面，忙碌教师默认折叠进展开区', async () => {
    const wrapper = mountChooser([
      candidate({ teacherId: 3, name: '忙碌张', busyAtPeriod: true, busyReason: '高一2班有课' }),
      candidate({ teacherId: 1, name: '空闲同科王', sameSubject: true, busyAtPeriod: false }),
      candidate({
        teacherId: 2,
        name: '空闲跨科李',
        subject: '数学',
        sameSubject: false,
        crossSubject: true,
        busyAtPeriod: false,
      }),
      candidate({ teacherId: 4, name: '忙碌赵', busyAtPeriod: true, busyReason: '高二1班有课' }),
    ])

    const available = wrapper.get('[data-testid="available-candidates"]')
    expect(available.find('[data-testid="substitute-option-1"]').exists()).toBe(true)
    expect(available.find('[data-testid="substitute-option-2"]').exists()).toBe(true)
    expect(available.find('[data-testid="substitute-option-3"]').exists()).toBe(false)

    const toggle = wrapper.get('[data-testid="toggle-busy-candidates"]')
    expect(toggle.text()).toBe('以下 2 位该时段有课（展开查看）')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-testid="busy-candidates"]').exists()).toBe(false)

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.text()).toBe('收起以下 2 位该时段有课')
    const busyList = wrapper.get('[data-testid="busy-candidates"]')
    expect(busyList.find('[data-testid="substitute-option-3"]').exists()).toBe(true)
    expect(busyList.find('[data-testid="substitute-option-4"]').exists()).toBe(true)
    expect(busyList.find('[data-testid="substitute-option-3"] input').attributes('disabled')).toBeDefined()

    await toggle.trigger('click')
    expect(wrapper.find('[data-testid="busy-candidates"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('全部候选都忙碌时展示无空闲提示，同时可展开查看忙碌原因', async () => {
    const wrapper = mountChooser([
      candidate({ teacherId: 3, name: '忙碌张', busyAtPeriod: true, busyReason: '高一2班有课' }),
    ])

    expect(wrapper.get('[data-testid="no-available-candidates"]').text()).toContain(
      '当前时段暂无空闲的代课老师',
    )
    expect(wrapper.get('[data-testid="toggle-busy-candidates"]').text()).toBe(
      '以下 1 位该时段有课（展开查看）',
    )
    await wrapper.get('[data-testid="toggle-busy-candidates"]').trigger('click')
    expect(wrapper.get('[data-testid="busy-candidates"]').text()).toContain('高一2班有课')
    wrapper.unmount()
  })
})
