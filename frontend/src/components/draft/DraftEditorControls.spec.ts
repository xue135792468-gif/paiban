import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DraftEditorControls from './DraftEditorControls.vue'
import type { DraftLesson } from './types'

function lesson(overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id: 1,
    assignmentId: 1,
    courseId: 1,
    courseName: '数学',
    teacherId: 10,
    teacherName: '张老师',
    classId: 1,
    className: '高一1班',
    studentCount: 40,
    periodId: 101,
    dayOfWeek: 1,
    periodNo: 1,
    locked: false,
    ...overrides,
  }
}

function mountControls(props: Record<string, unknown> = {}) {
  return mount(DraftEditorControls, {
    props: {
      viewMode: 'class',
      targetId: 1,
      targetOptions: [{ id: 1, name: '高一1班' }],
      selectedLesson: null,
      draftEditable: true,
      unassignedCount: 0,
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('DraftEditorControls', () => {
  it('多选按钮进入/退出选择模式', async () => {
    const wrapper = mountControls()

    await wrapper.get('[data-testid="toggle-selection"]').trigger('click')

    expect(wrapper.emitted('toggle-selection')).toHaveLength(1)
  })

  it('选择模式显示已选数量，批量按钮带上实际节数', async () => {
    const wrapper = mountControls({ selectionMode: true, selectedCount: 2 })

    expect(wrapper.get('[data-testid="selected-count"]').text()).toContain('已选 2 节')
    const lock = wrapper.findAll('button').find((button) => button.text() === '锁定所选（2）')
    expect(lock).toBeTruthy()
    await lock!.trigger('click')

    expect(wrapper.emitted('batch-lock')?.[0]?.[0]).toEqual({ scope: 'selected', locked: true })
  })

  it('选中单节课时按锁定状态给出锁定或解锁入口', async () => {
    const unlocked = lesson({ locked: false })
    const wrapper = mountControls({ selectedLesson: unlocked })
    await wrapper.get('[data-testid="lock-single"]').trigger('click')
    expect(wrapper.emitted('lock-single')?.[0]?.[0]).toMatchObject({ id: 1 })

    const locked = lesson({ locked: true })
    const second = mountControls({ selectedLesson: locked, selectedLessonLocked: true })
    await second.get('[data-testid="unlock-single"]').trigger('click')
    expect(second.emitted('unlock-single')?.[0]?.[0]).toMatchObject({ id: 1 })
  })

  it('教师/教室视角提示按天/全班需切到行政班', () => {
    const wrapper = mountControls({
      selectionMode: true,
      canUseClassScope: false,
      dayOptions: [],
    })

    expect(wrapper.text()).toContain('按天/全班需切到「按行政班」')
  })

  it('草稿不可编辑时禁用锁定入口', () => {
    const wrapper = mountControls({
      selectedLesson: lesson(),
      draftEditable: false,
      selectionMode: true,
      selectedCount: 2,
    })

    const lock = wrapper.findAll('button').find((button) => button.text() === '锁定所选（2）')
    expect(lock?.attributes('disabled')).toBeDefined()
  })
})
