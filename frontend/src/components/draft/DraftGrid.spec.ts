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
    locked: false,
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

  it('卡片只显示课程与教师，不渲染教室', () => {
    const wrapper = mountGrid({ lessons: [lesson(1, 1, 1)] })

    const text = wrapper.get('.lesson').text()
    expect(text).toContain('课程1')
    expect(text).toContain('教师1')
    expect(text).not.toContain('101')
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

  it('锁定课节带 locked 标记、data-locked、锁字与 aria 说明', () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1, { locked: true })],
      lockedLessonIds: [1],
    })

    const card = wrapper.get('.lesson')
    expect(card.classes()).toContain('locked')
    expect(card.attributes('data-locked')).toBe('true')
    expect(card.attributes('tabindex')).toBe('0')
    expect(card.text()).toContain('锁')
    expect(card.attributes('aria-label')).toContain('课程1')
    expect(card.attributes('aria-label')).toContain('教师1')
    expect(card.attributes('aria-label')).toContain('已锁定')
  })

  it('未锁定的课节没有锁定标记但仍可聚焦', () => {
    const wrapper = mountGrid({ lessons: [lesson(1, 1, 1)], lockedLessonIds: [2] })

    const card = wrapper.get('.lesson')
    expect(card.classes()).not.toContain('locked')
    expect(card.attributes('data-locked')).toBeUndefined()
    expect(card.text()).not.toContain('锁')
    expect(card.attributes('aria-label')).not.toContain('已锁定')
  })

  it('Enter 与 Space 都触发 lesson-activate，用于等价键盘入口', async () => {
    const wrapper = mountGrid({ lessons: [lesson(1, 1, 1)] })
    const card = wrapper.get('.lesson')

    await card.trigger('keydown', { key: 'Enter' })
    await card.trigger('keydown', { key: ' ' })

    const events = wrapper.emitted('lesson-activate') ?? []
    expect(events).toHaveLength(2)
    expect(events[0][0]).toMatchObject({ id: 1 })
  })

  it('普通模式点击课节仍然冒泡到单元格，保持原有选中方式', async () => {
    const wrapper = mountGrid({ lessons: [lesson(1, 1, 1)] })

    await wrapper.get('.lesson').trigger('click')

    expect(wrapper.emitted('toggle-select')).toBeUndefined()
    expect(wrapper.emitted('cell-click')?.[0]?.[0]).toEqual({ day: 1, periodNo: 1 })
  })

  it('选择模式点击课节只发 toggle-select，不冒泡也不可拖拽', async () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1)],
      selectionMode: true,
      draggable: true,
    })
    const card = wrapper.get('.lesson')

    await card.trigger('click')

    expect(wrapper.emitted('toggle-select')?.[0]?.[0]).toMatchObject({ id: 1 })
    expect(wrapper.emitted('cell-click')).toBeUndefined()
    expect(wrapper.emitted('drag-start')).toBeUndefined()
    expect(card.attributes('draggable')).toBe('false')

    await card.trigger('dragstart')
    expect(wrapper.emitted('drag-start')).toBeUndefined()
  })

  it('选择模式下的选中课节有 selected 样式、data-selected 与 aria-pressed', () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1), lesson(2, 1, 2)],
      periods: [period(1, 1), period(1, 2)],
      selectionMode: true,
      selectedLessonIds: [1],
    })

    const cards = wrapper.findAll('.lesson')
    expect(cards[0].classes()).toContain('selected')
    expect(cards[0].attributes('data-selected')).toBe('true')
    expect(cards[0].attributes('aria-pressed')).toBe('true')
    expect(cards[1].classes()).not.toContain('selected')
    expect(cards[1].attributes('data-selected')).toBeUndefined()
    expect(cards[1].attributes('aria-pressed')).toBe('false')
  })

  it('锁定与选中样式可以同时存在，锁定虚线不被选中覆盖', () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1, { locked: true })],
      lockedLessonIds: [1],
      selectionMode: true,
      selectedLessonIds: [1],
    })

    const card = wrapper.get('.lesson')
    expect(card.classes()).toContain('locked')
    expect(card.classes()).toContain('selected')
    expect(card.attributes('data-locked')).toBe('true')
    expect(card.text()).toContain('锁')
  })

  it('选择模式按下 Enter/Space 发 toggle-select，普通模式仍发 lesson-activate', async () => {
    const selecting = mountGrid({ lessons: [lesson(1, 1, 1)], selectionMode: true })
    await selecting.get('.lesson').trigger('keydown', { key: 'Enter' })

    expect(selecting.emitted('toggle-select')?.[0]?.[0]).toMatchObject({ id: 1 })
    expect(selecting.emitted('lesson-activate')).toBeUndefined()

    const normal = mountGrid({ lessons: [lesson(1, 1, 1)] })
    await normal.get('.lesson').trigger('keydown', { key: ' ' })

    expect(normal.emitted('lesson-activate')?.[0]?.[0]).toMatchObject({ id: 1 })
    expect(normal.emitted('toggle-select')).toBeUndefined()
  })

  it('教师视图的课卡显示班级、课程与教师', () => {
    const wrapper = mountGrid({
      lessons: [lesson(1, 1, 1, { className: '高一1班' })],
      showClassName: true,
    })

    const text = wrapper.get('.lesson').text()
    expect(text).toContain('高一1班')
    expect(text).toContain('课程1')
    expect(text).toContain('教师1')
  })

  it('换课模式：绿格标出对方班级/课程/教师，灰格与空格带具体原因', () => {
    const wrapper = mountGrid({
      periods: [period(1, 1), period(2, 1), period(1, 2), period(2, 2)],
      lessons: [
        lesson(1, 1, 1, { classId: 1, className: '高一1班' }),
        lesson(2, 1, 1, { classId: 2, className: '高一2班' }),
        lesson(3, 2, 1, { classId: 1, className: '高一1班' }),
      ],
      showClassName: true,
      swapTargets: {
        1: { status: 'swappable', reason: '和高一1班的语文互换，李老师' },
        2: { status: 'foreign', reason: '不同班，只能同班换课' },
        3: { status: 'blocked', reason: '李老师 此时在 高一2班 有 英语课' },
      },
    })

    const cells = wrapper.findAll('.cell')
    expect(cells[0].classes()).toContain('swap-swappable')
    expect(cells[0].attributes('data-swap-state')).toBe('swappable')
    expect(cells[0].attributes('title')).toBe('和高一1班的语文互换，李老师')
    expect(cells[0].text()).toContain('高一1班')
    expect(cells[0].text()).toContain('教师1')

    expect(cells[1].classes()).toContain('swap-blocked')
    expect(cells[1].attributes('title')).toBe('李老师 此时在 高一2班 有 英语课')

    expect(cells[2].classes()).toContain('swap-blocked')
    expect(cells[2].attributes('title')).toBe('没有可换的课')
    expect(cells[3].attributes('title')).toBe('没有可换的课')

    const foreignCard = wrapper.findAll('.lesson')[1]
    expect(foreignCard.classes()).toContain('swap-foreign')
    expect(foreignCard.attributes('title')).toBe('不同班，只能同班换课')
  })
})
