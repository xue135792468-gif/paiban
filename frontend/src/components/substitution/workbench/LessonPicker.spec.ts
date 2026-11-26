import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LessonPicker from './LessonPicker.vue'
import type { Entry } from '../types'

function makeEntry(id: number, dayOfWeek: number, periodNo = 1): Entry {
  return {
    id,
    classId: 10,
    courseId: 20,
    courseName: '数学',
    className: '高一1班',
    teacherId: 1,
    teacherName: '王建国',
    periodId: 100 + dayOfWeek * 10 + periodNo,
    periodNo,
    dayOfWeek,
    substituted: false,
  }
}

function mountPicker(overrides: Record<string, unknown> = {}) {
  return mount(LessonPicker, {
    props: {
      teachers: [{ id: 1, name: '王建国' }],
      teacherId: 1,
      date: '2026-03-05', // Thursday
      minDate: '2026-03-05',
      weekStart: '2026-03-02', // Monday
      entries: [],
      loading: false,
      timetableError: '',
      selectedEntryId: null,
      dragInvalidReason: '',
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('LessonPicker F-05 空态与本周无剩余可调课提示', () => {
  it('本周有课但全部在今天之前时，提示本周剩余日期无课并提供跳到下周按钮', async () => {
    const wrapper = mountPicker({
      entries: [makeEntry(1, 1, 1), makeEntry(2, 2, 2), makeEntry(3, 3, 1)],
    })

    const callout = wrapper.get('[data-testid="week-no-adjustable-callout"]')
    expect(callout.text()).toContain('本周剩余日期没有可调整的课')
    expect(callout.text()).toContain('本周有 3 节课已结束')

    const jumpBtn = wrapper.get('[data-testid="jump-next-week"]')
    expect(jumpBtn.text()).toContain('跳到下周')
    await jumpBtn.trigger('click')

    expect(wrapper.emitted('update:date')?.at(-1)).toEqual(['2026-03-09'])
    wrapper.unmount()
  })

  it('本周有今天或之后的课时，不显示本周无剩余可调课提示', () => {
    const wrapper = mountPicker({
      entries: [makeEntry(1, 1, 1), makeEntry(2, 4, 1)],
    })

    expect(wrapper.find('[data-testid="week-no-adjustable-callout"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('semesterLessonCount 为 0 时明确提示本学期没有排课且不展示跳到下周', () => {
    const wrapper = mountPicker({
      entries: [],
      semesterLessonCount: 0,
    })

    expect(wrapper.get('[data-testid="no-semester-lessons"]').text()).toContain(
      '该教师本学期没有排课',
    )
    expect(wrapper.find('[data-testid="jump-next-week"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('本周 0 节但本学期有课时提示本周没有可调整的课并提供跳到下周', async () => {
    const wrapper = mountPicker({
      entries: [],
      semesterLessonCount: 16,
    })

    const msg = wrapper.get('[data-testid="no-week-lessons"]')
    expect(msg.text()).toContain('本周没有可调整的课')
    expect(msg.text()).toContain('16 节排课')

    await wrapper.get('[data-testid="jump-next-week"]').trigger('click')
    expect(wrapper.emitted('update:date')?.at(-1)).toEqual(['2026-03-09'])
    wrapper.unmount()
  })
})
