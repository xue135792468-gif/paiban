import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import RuleTimeSlotEditor from './RuleTimeSlotEditor.vue'
import type { PeriodOption } from '@/rules/compiler'

const periods: PeriodOption[] = [
  { dayOfWeek: 1, periodNo: 1, startTime: '08:00:00' },
  { dayOfWeek: 1, periodNo: 2, startTime: '08:55:00' },
  { dayOfWeek: 1, periodNo: 5, startTime: '14:00:00' },
  { dayOfWeek: 3, periodNo: 5, startTime: '14:00:00' },
]

function mountEditor(overrides: Record<string, unknown> = {}) {
  return mount(RuleTimeSlotEditor, {
    props: {
      days: [],
      periodNos: [],
      timeGroupId: null,
      periods,
      timeGroups: [{ id: 3, name: '上午' }],
      allowPeriods: true,
      allowTimeGroup: true,
      index: 0,
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

function select(wrapper: VueWrapper, testId: string) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === testId)
  if (!found) throw new Error(`select not found: ${testId}`)
  return found
}

async function pickTimeGroup(wrapper: VueWrapper, value: string) {
  const component = select(wrapper, 'rule-time-group')
  component.vm.$emit('update:modelValue', value)
  component.vm.$emit('change', value)
  await flushPromises()
}

describe('RuleTimeSlotEditor: time group or explicit grid', () => {
  it('offers the time groups next to 按星期/节次', () => {
    const wrapper = mountEditor()
    const options = select(wrapper, 'rule-time-group')
      .findAllComponents({ name: 'ElOption' })
      .map((component) => String(component.props('label')))
    expect(options).toContain('按星期/节次')
    expect(options).toContain('时间组 · 上午')
    expect(wrapper.find('[data-testid="rule-days"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('switching to a time group clears the weekday and period selection', async () => {
    const wrapper = mountEditor({ days: [1], periodNos: [1, 2] })
    await pickTimeGroup(wrapper, 'time-group:3')

    expect(wrapper.emitted('update:timeGroupId')?.at(-1)).toEqual([3])
    expect(wrapper.emitted('update:days')?.at(-1)).toEqual([[]])
    expect(wrapper.emitted('update:periodNos')?.at(-1)).toEqual([[]])
    wrapper.unmount()
  })

  it('hides the grid while a time group is selected', () => {
    const wrapper = mountEditor({ timeGroupId: 3 })
    expect(wrapper.find('[data-testid="rule-days"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="rule-period-1"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="rule-time-group-hint"]').text())
      .toContain('时间组包含的课节在分组区修改')
    wrapper.unmount()
  })

  it('switching back to 按星期/节次 clears the time group', async () => {
    const wrapper = mountEditor({ timeGroupId: 3 })
    await pickTimeGroup(wrapper, '')
    expect(wrapper.emitted('update:timeGroupId')?.at(-1)).toEqual([null])
    wrapper.unmount()
  })

  it('creates a new time group from the dropdown and selects it', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '晚自习' } as never)
    const createTimeGroup = vi.fn().mockResolvedValue({ id: 9, name: '晚自习' })
    const wrapper = mountEditor({ createTimeGroup })

    await pickTimeGroup(wrapper, '__new_time_group__')

    expect(prompt).toHaveBeenCalledTimes(1)
    expect(createTimeGroup).toHaveBeenCalledWith('晚自习')
    expect(wrapper.emitted('update:timeGroupId')?.at(-1)).toEqual([9])
    wrapper.unmount()
  })

  it('keeps the old pick when the new-group prompt is cancelled', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockRejectedValue('cancel')
    const createTimeGroup = vi.fn()
    const wrapper = mountEditor({ createTimeGroup })

    await pickTimeGroup(wrapper, '__new_time_group__')

    expect(createTimeGroup).not.toHaveBeenCalled()
    expect(wrapper.emitted('update:timeGroupId')).toBeUndefined()
    wrapper.unmount()
  })

  it('filters periods to the intersection of the chosen weekdays', async () => {
    const wrapper = mountEditor({ days: [1], periodNos: [1, 2, 5] })
    const days = select(wrapper, 'rule-days')
    days.vm.$emit('update:modelValue', [3])
    days.vm.$emit('change', [3])
    await flushPromises()

    expect(wrapper.emitted('update:days')?.at(-1)).toEqual([[3]])
    // Day 3 only has period 5, so 1 and 2 must be dropped.
    expect(wrapper.emitted('update:periodNos')?.at(-1)).toEqual([[5]])
    wrapper.unmount()
  })

  it('hides periods for fixed days off but keeps the weekday picker', () => {
    const wrapper = mountEditor({ allowPeriods: false, allowTimeGroup: false, days: [1] })
    expect(wrapper.find('[data-testid="rule-days"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-period-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="rule-time-group"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
