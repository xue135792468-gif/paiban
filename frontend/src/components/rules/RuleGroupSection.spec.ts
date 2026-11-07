import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import RuleGroupSection from './RuleGroupSection.vue'
import type { RuleGroup, RuleGroupImpact } from '@/api/ruleGroups'

const teachers = [
  { id: 7, name: '王老师' },
  { id: 8, name: '李老师' },
]

const groups: RuleGroup[] = [
  {
    id: 12,
    type: 'TEACHER',
    name: '教导主任',
    semesterId: null,
    members: [
      { id: 1, kind: 'TEACHER', objectId: 7, name: '王老师', dayOfWeek: null, periodNo: null },
      { id: 2, kind: 'TEACHER', objectId: 8, name: '李老师', dayOfWeek: null, periodNo: null },
    ],
  },
]

const impactWithRules: RuleGroupImpact = {
  id: 12,
  name: '教导主任',
  type: 'TEACHER',
  rules: [{
    configId: 3,
    semesterId: 1,
    semesterName: '2025 秋',
    type: 'TEACHER_UNAVAILABLE',
    typeName: '不上课',
    groupKey: 'pt-card:0',
    entryIds: [41, 42],
  }],
  message: '有 1 条规则正在使用「教导主任」，删除后这些规则会失效',
}

function mountSection(overrides: Record<string, unknown> = {}): VueWrapper {
  return mount(RuleGroupSection, {
    props: {
      groupType: 'TEACHER',
      title: '教师分组',
      memberLabel: '老师',
      groups,
      options: teachers,
      loading: false,
      createGroup: vi.fn().mockResolvedValue(true),
      updateGroup: vi.fn().mockResolvedValue(true),
      deleteGroup: vi.fn().mockResolvedValue({ batchIds: ['b1'], deletedRules: 0 }),
      loadImpact: vi.fn().mockResolvedValue({ ...impactWithRules, rules: [] }),
      ...overrides,
    },
    // ElSelect's multiple mode recurses inside jsdom; the dialog's callback
    // contract is what this unit file checks, the real widget is covered by E2E.
    global: { plugins: [ElementPlus], stubs: { teleport: true, ElSelect: true } },
    attachTo: document.body,
  })
}

describe('RuleGroupSection: teacher group management', () => {
  it('lists groups with their member names', () => {
    const wrapper = mountSection()
    expect(wrapper.get('[data-testid="rule-group-name-12"]').text()).toBe('教导主任')
    expect(wrapper.get('[data-testid="rule-group-members-text-12"]').text()).toBe('王老师、李老师')
    wrapper.unmount()
  })

  it('creates a group from the dialog', async () => {
    const createGroup = vi.fn().mockResolvedValue(true)
    const wrapper = mountSection({ createGroup })
    await wrapper.get('[data-testid="rule-group-new"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="rule-group-name-input"]').setValue('教导主任')
    await flushPromises()

    await wrapper.get('[data-testid="rule-group-save"]').trigger('click')
    await flushPromises()
    expect(createGroup).toHaveBeenCalledWith('教导主任', [])
    wrapper.unmount()
  })

  it('updates an existing group with its current members', async () => {
    const updateGroup = vi.fn().mockResolvedValue(true)
    const wrapper = mountSection({ updateGroup })
    await wrapper.get('[data-testid="rule-group-edit-12"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="rule-group-name-input"]').setValue('教导处')
    await wrapper.get('[data-testid="rule-group-save"]').trigger('click')
    await flushPromises()
    expect(updateGroup).toHaveBeenCalledWith(12, '教导处', [7, 8])
    wrapper.unmount()
  })

  it('deletes an unreferenced group after a plain confirmation', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const deleteGroup = vi.fn().mockResolvedValue({ batchIds: ['b1'], deletedRules: 0 })
    const wrapper = mountSection({ deleteGroup })
    await wrapper.get('[data-testid="rule-group-delete-12"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0][0])).toContain('7 天内可在「最近删除」恢复')
    expect(deleteGroup).toHaveBeenCalledWith(12, false)
    wrapper.unmount()
  })

  it('lists referencing rules and deletes them together when confirmed', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const deleteGroup = vi.fn().mockResolvedValue({ batchIds: ['b1', 'b2'], deletedRules: 1 })
    const wrapper = mountSection({
      deleteGroup,
      loadImpact: vi.fn().mockResolvedValue(impactWithRules),
    })
    await wrapper.get('[data-testid="rule-group-delete-12"]').trigger('click')
    await flushPromises()

    const message = String(confirm.mock.calls[0][0])
    expect(message).toContain('2025 秋 · 不上课')
    expect(message).toContain('删除后这些规则会失效')
    const options = confirm.mock.calls[0][2] as { confirmButtonText: string; cancelButtonText: string }
    expect(options.confirmButtonText).toBe('连同规则一起删除')
    expect(options.cancelButtonText).toBe('先改规则')
    expect(deleteGroup).toHaveBeenCalledWith(12, true)
    wrapper.unmount()
  })

  it('deletes nothing when the user chooses 先改规则', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const deleteGroup = vi.fn()
    const wrapper = mountSection({
      deleteGroup,
      loadImpact: vi.fn().mockResolvedValue(impactWithRules),
    })
    await wrapper.get('[data-testid="rule-group-delete-12"]').trigger('click')
    await flushPromises()
    expect(deleteGroup).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
