import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RuleUnusablePanel from './RuleUnusablePanel.vue'
import type { RuleCard, RuleNames, RuleSentence } from '@/rules/compiler'

const names: RuleNames = {
  teacher: (id) => `教师${id}`,
  course: (id) => `课程${id}`,
  klass: (id) => `班级${id}`,
  teacherGroup: () => '教师组',
  courseGroup: () => '课程组',
  timeGroup: () => '时间组',
}

const periods = [{ dayOfWeek: 1, periodNo: 1 }]

function sentence(overrides: Partial<RuleSentence> = {}): RuleSentence {
  return {
    key: 'group:pt-a:0',
    groupKey: 'pt-a:0',
    type: 'TEACHER_UNAVAILABLE',
    classId: null,
    teacherId: 9,
    courseId: null,
    days: [1],
    periodNos: [1],
    amount: null,
    strength: 'HARD',
    enabled: false,
    entryIds: { '1-1': 10 },
    exceptionClassIds: [],
    teacherGroupId: null,
    courseGroupId: null,
    timeGroupId: null,
    ...overrides,
  }
}

function card(sentences: RuleSentence[]): RuleCard {
  return { key: 'card:pt-a', cardId: 'pt-a', enabled: false, sentences }
}

describe('RuleUnusablePanel: copied rules that no longer resolve', () => {
  it('shows the sentences and the server reasons', () => {
    const wrapper = mount(RuleUnusablePanel, {
      props: {
        cards: [card([sentence({ invalidReferences: ['教师已删除：#9'] })])],
        names,
        periods,
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.get('[data-testid="rule-unusable-panel"]').text())
      .toContain('本学期用不上（1）')
    expect(wrapper.text()).toContain('教师9')
    expect(wrapper.text()).toContain('教师已删除：#9')
    wrapper.unmount()
  })

  it('falls back to the missing parts when the server has no invalid reference', () => {
    const wrapper = mount(RuleUnusablePanel, {
      props: {
        cards: [card([sentence({ teacherId: null, days: [], periodNos: [] })])],
        names,
        periods,
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('还差：选择教师')
    wrapper.unmount()
  })

  it('deduplicates the same reason repeated by two sentences', () => {
    const invalid = { invalidReferences: ['教师已删除：#9'] }
    const wrapper = mount(RuleUnusablePanel, {
      props: {
        cards: [card([sentence(invalid), sentence({ key: 'group:pt-a:1', ...invalid })])],
        names,
        periods,
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.findAll('.reasons li')).toHaveLength(1)
    wrapper.unmount()
  })

  it('emits edit and remove for its own card', async () => {
    const current = card([sentence({ invalidReferences: ['教师已删除：#9'] })])
    const wrapper = mount(RuleUnusablePanel, {
      props: { cards: [current], names, periods },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('[data-testid="rule-unusable-edit-pt-a"]').trigger('click')
    await wrapper.get('[data-testid="rule-unusable-remove-pt-a"]').trigger('click')

    expect(wrapper.emitted('edit')?.[0]).toEqual([current])
    expect(wrapper.emitted('remove')?.[0]).toEqual([current])
    wrapper.unmount()
  })

  it('renders nothing for an empty list', () => {
    const wrapper = mount(RuleUnusablePanel, {
      props: { cards: [], names, periods },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.find('[data-testid="rule-unusable-panel"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
