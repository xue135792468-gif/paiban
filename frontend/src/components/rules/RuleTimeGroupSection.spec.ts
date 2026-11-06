import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import RuleTimeGroupSection from './RuleTimeGroupSection.vue'
import type { PeriodOption } from '@/rules/compiler'
import type { RuleGroup, RuleGroupImpact } from '@/api/ruleGroups'

const periods: PeriodOption[] = [1, 3].flatMap((day) => [
  { dayOfWeek: day, periodNo: 1, startTime: '08:00:00' },
  { dayOfWeek: day, periodNo: 2, startTime: '08:55:00' },
])

const morning: RuleGroup = {
  id: 5,
  type: 'TIME',
  name: '上午',
  semesterId: 1,
  autoFilled: true,
  members: [
    { id: 1, kind: 'TIME', objectId: 0, name: '周一第1节', dayOfWeek: 1, periodNo: 1 },
    { id: 2, kind: 'TIME', objectId: 0, name: '周一第2节', dayOfWeek: 1, periodNo: 2 },
  ],
}

const impactWithRules: RuleGroupImpact = {
  id: 5,
  name: '上午',
  type: 'TIME',
  rules: [{
    configId: 3,
    semesterId: 1,
    semesterName: '2025 秋',
    type: 'COURSE_UNAVAILABLE',
    typeName: '不排课',
    groupKey: 'pt-card:0',
    entryIds: [41],
  }],
  message: '有 1 条规则正在使用「上午」',
}

function mountSection(overrides: Record<string, unknown> = {}): VueWrapper {
  return mount(RuleTimeGroupSection, {
    props: {
      groups: [morning],
      periods,
      loading: false,
      semesterId: 1,
      createGroup: vi.fn().mockResolvedValue(true),
      updateGroup: vi.fn().mockResolvedValue(true),
      deleteGroup: vi.fn().mockResolvedValue({ batchIds: ['b1'], deletedRules: 0 }),
      loadImpact: vi.fn().mockResolvedValue({ ...impactWithRules, rules: [] }),
      ...overrides,
    },
    // ElCheckboxGroup recurses inside jsdom; the dialog's callback contract is what
    // this unit file checks, the real grid is covered by E2E.
    global: { plugins: [ElementPlus], stubs: { teleport: true, ElCheckboxGroup: true } },
    attachTo: document.body,
  })
}

function setSlots(wrapper: VueWrapper, values: string[]) {
  const group = wrapper.findComponent({ name: 'ElCheckboxGroup' })
  group.vm.$emit('update:modelValue', values)
}

describe('RuleTimeGroupSection: semester time groups', () => {
  it('lists the prebuilt tag and the member summary', () => {
    const wrapper = mountSection()
    const name = wrapper.get('[data-testid="rule-time-group-name-5"]')
    expect(name.text()).toContain('上午')
    expect(name.text()).toContain('预建')
    expect(wrapper.get('[data-testid="rule-time-group-members-5"]').text())
      .toBe('周一第1节、周一第2节')
    wrapper.unmount()
  })

  it('creates a group with the picked period slots', async () => {
    const createGroup = vi.fn().mockResolvedValue(true)
    const wrapper = mountSection({ createGroup })
    await wrapper.get('[data-testid="rule-time-group-new"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="rule-time-group-name-input"]').setValue('晚自习')
    setSlots(wrapper, ['1-1', '3-2'])
    await flushPromises()
    await wrapper.get('[data-testid="rule-time-group-save"]').trigger('click')
    await flushPromises()

    expect(createGroup).toHaveBeenCalledWith('晚自习', [
      { dayOfWeek: 1, periodNo: 1 },
      { dayOfWeek: 3, periodNo: 2 },
    ])
    wrapper.unmount()
  })

  it('edits a prebuilt group with its current members prefilled', async () => {
    const updateGroup = vi.fn().mockResolvedValue(true)
    const wrapper = mountSection({ updateGroup })
    await wrapper.get('[data-testid="rule-time-group-edit-5"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="rule-time-group-name-input"]').setValue('早上')
    await wrapper.get('[data-testid="rule-time-group-save"]').trigger('click')
    await flushPromises()

    expect(updateGroup).toHaveBeenCalledWith(5, '早上', [
      { dayOfWeek: 1, periodNo: 1 },
      { dayOfWeek: 1, periodNo: 2 },
    ])
    wrapper.unmount()
  })

  it('deletes an unreferenced group after a plain confirmation', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const deleteGroup = vi.fn().mockResolvedValue({ batchIds: ['b1'], deletedRules: 0 })
    const wrapper = mountSection({ deleteGroup })
    await wrapper.get('[data-testid="rule-time-group-delete-5"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0][0])).toContain('7 天内可在「最近删除」恢复')
    expect(deleteGroup).toHaveBeenCalledWith(5, false)
    wrapper.unmount()
  })

  it('lists referencing rules and deletes them together when confirmed', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const deleteGroup = vi.fn().mockResolvedValue({ batchIds: ['b1', 'b2'], deletedRules: 1 })
    const wrapper = mountSection({
      deleteGroup,
      loadImpact: vi.fn().mockResolvedValue(impactWithRules),
    })
    await wrapper.get('[data-testid="rule-time-group-delete-5"]').trigger('click')
    await flushPromises()

    const message = String(confirm.mock.calls[0][0])
    expect(message).toContain('2025 秋 · 不排课')
    expect(message).toContain('删除后这些规则会失效')
    const options = confirm.mock.calls[0][2] as { confirmButtonText: string; cancelButtonText: string }
    expect(options.confirmButtonText).toBe('连同规则一起删除')
    expect(options.cancelButtonText).toBe('先改规则')
    expect(deleteGroup).toHaveBeenCalledWith(5, true)
    expect(wrapper.emitted('changed')?.at(-1)).toEqual([{ deletedRules: 1 }])
    wrapper.unmount()
  })

  it('deletes nothing when the user chooses 先改规则', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const deleteGroup = vi.fn()
    const wrapper = mountSection({
      deleteGroup,
      loadImpact: vi.fn().mockResolvedValue(impactWithRules),
    })
    await wrapper.get('[data-testid="rule-time-group-delete-5"]').trigger('click')
    await flushPromises()
    expect(deleteGroup).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
