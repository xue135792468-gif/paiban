import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DraftGrid from './DraftGrid.vue'
import type { DraftLesson, DraftPeriod } from './types'

function period(
  dayOfWeek: number,
  periodNo: number,
  overrides: Partial<DraftPeriod> = {},
): DraftPeriod {
  return {
    id: dayOfWeek * 100 + periodNo,
    dayOfWeek,
    periodNo,
    label: `第${periodNo}节`,
    section: '',
    startTime: '',
    endTime: '',
    ...overrides,
  }
}

function lesson(
  id: number,
  dayOfWeek: number,
  periodNo: number,
  overrides: Partial<DraftLesson> = {},
): DraftLesson {
  return {
    id,
    assignmentId: id,
    courseId: id,
    courseName: `课程${id}`,
    teacherId: id,
    teacherName: `教师${id}`,
    classId: 1,
    studentCount: 40,
    dayOfWeek,
    periodNo,
    roomName: '101',
    ...overrides,
  }
}

function mountGrid(props: Record<string, unknown>) {
  return mount(DraftGrid, {
    props: {
      periods: [period(1, 1)],
      lessons: [],
      courses: [],
      draggable: false,
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('DraftGrid', () => {
  it('同一格里的多节课都会渲染，用于导入预览里的硬冲突', () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1), lesson(2, 1, 1, { courseName: '数学', teacherName: '李四' })],
    })

    const lessons = wrapper.findAll('.lesson')
    expect(lessons).toHaveLength(2)
    expect(lessons.map((item) => item.text())).toEqual([
      expect.stringContaining('课程1'),
      expect.stringContaining('数学'),
    ])
  })

  it('冲突课节带 conflict 标记，普通课节没有', () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1), lesson(2, 2, 1)],
      periods: [period(1, 1), period(2, 1)],
      conflictLessonIds: [1],
    })

    const lessons = wrapper.findAll('.lesson')
    expect(lessons[0].classes()).toContain('conflict')
    expect(lessons[1].classes()).not.toContain('conflict')
  })

  it('课表页面不显示教室，导入预览仍保留教室', () => {
    const hidden = mountGrid({ lessons: [lesson(1, 1, 1)], showRoom: false })
    expect(hidden.get('.lesson').text()).not.toContain('101')

    const shown = mountGrid({ lessons: [lesson(1, 1, 1)] })
    expect(shown.get('.lesson').text()).toContain('101')
  })

  it('作息没有起止时间时不显示时间占位', () => {
    const wrapper = mountGrid({ lessons: [lesson(1, 1, 1)] })

    expect(wrapper.findAll('.row-time')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('--')
  })

  it('有起止时间时显示时间段', () => {
    const wrapper = mountGrid({
      periods: [period(1, 1, { startTime: '08:00:00', endTime: '08:45:00' })],
      lessons: [lesson(1, 1, 1)],
    })

    expect(wrapper.get('.row-time').text()).toBe('08:00-08:45')
  })
})
