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
  it('主动留空有文字原因，不能作为拖入目标', async () => {
    const wrapper = mountGrid({
      draggable: true,
      draggedLessonId: 9,
      blockedPeriods: { 101: '指定班级规则（本班）' },
      targets: { 101: { available: true, conflicts: [] } },
    })
    expect(wrapper.get('.cell').text()).toContain('不上课')
    expect(wrapper.get('.cell').attributes('title')).toBe('指定班级规则（本班）')
    expect(wrapper.get('.cell').classes()).toContain('invalid-target')
    await wrapper.get('.cell').trigger('drop')
    expect(wrapper.emitted('drop-cell')).toBeUndefined()
  })

  it('禁排位置已有课程时保留课程并标冲突，仍可把课程拖走', async () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1)],
      draggable: true,
      blockedPeriods: { 101: '本班每周不上课时段' },
    })
    expect(wrapper.get('.lesson').text()).toContain('课程1')
    expect(wrapper.get('.lesson').classes()).toContain('conflict')
    expect(wrapper.get('.cell').text()).toContain('占用了不上课时段')
    await wrapper.get('.lesson').trigger('dragstart')
    expect(wrapper.emitted('drag-start')).toHaveLength(1)
  })

  it('留空灰格的 title 原样显示后端给出的来源，重叠时两种都显示', () => {
    const wrapper = mountGrid({ blockedPeriods: { 101: '全校规则、指定班级规则（本班）' } })
    expect(wrapper.get('.cell').attributes('title')).toBe('全校规则、指定班级规则（本班）')
    expect(wrapper.get('.cell').text()).toContain('不上课')
    expect(wrapper.get('.cell').classes()).toContain('blocked-slot')
  })

  it('未传班级禁排映射的其他视角不显示主动留空', () => {
    const wrapper = mountGrid({})
    expect(wrapper.text()).not.toContain('不上课')
    expect(wrapper.get('.cell').text()).toBe('—')
  })

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
