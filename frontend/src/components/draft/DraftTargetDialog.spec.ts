import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DraftTargetDialog from './DraftTargetDialog.vue'
import type { TargetPeriodState } from './targetAvailability'
import type { DraftPeriod } from './types'

function period(dayOfWeek: number, periodNo: number): DraftPeriod {
  return {
    id: dayOfWeek * 100 + periodNo,
    dayOfWeek,
    periodNo,
    label: `第${periodNo}节`,
    startTime: '',
    endTime: '',
  }
}

describe('DraftTargetDialog', () => {
  it('区分各状态标签与 tooltip，可放置和本班已占用可点击，其余禁用', async () => {
    const periodStates: Record<number, TargetPeriodState> = {
      101: { status: 'current', disabled: true, label: '当前位置', tooltip: '当前所在位置' },
      102: { status: 'swappable', disabled: false, label: '本班已占用', tooltip: '本班已有「语文 · 李老师」，点击尝试对调' },
      103: { status: 'teacher-busy', disabled: true, label: '教师有课', tooltip: '王老师在2班有课' },
      104: { status: 'blocked', disabled: true, label: '禁排时段', tooltip: '全校不上课' },
      105: { status: 'locked', disabled: true, label: '已锁定', tooltip: '该课节已锁定' },
      106: { status: 'available', disabled: false, label: '第6节', tooltip: '可移动到第6节' },
    }
    const wrapper = mount(DraftTargetDialog, {
      props: {
        modelValue: true,
        periods: [1, 2, 3, 4, 5, 6].map((p) => period(1, p)),
        currentPeriodId: 101,
        periodStates,
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const chip = (id: number) => wrapper.get(`.period-chip[data-period-id="${id}"]`)
    expect((chip(101).element as HTMLButtonElement).disabled).toBe(true)
    expect(chip(101).text()).toContain('当前位置')
    expect((chip(102).element as HTMLButtonElement).disabled).toBe(false)
    expect(chip(102).text()).toContain('本班已占用')
    expect(chip(102).attributes('title')).toContain('语文')
    expect((chip(103).element as HTMLButtonElement).disabled).toBe(true)
    expect(chip(103).text()).toContain('教师有课')
    expect((chip(104).element as HTMLButtonElement).disabled).toBe(true)
    expect(chip(104).text()).toContain('禁排时段')
    expect((chip(105).element as HTMLButtonElement).disabled).toBe(true)
    expect(chip(105).text()).toContain('已锁定')
    expect((chip(106).element as HTMLButtonElement).disabled).toBe(false)

    await chip(102).trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([102])
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false])

    await chip(103).trigger('click')
    expect(wrapper.emitted('select')).toHaveLength(1)
    wrapper.unmount()
  })
})
