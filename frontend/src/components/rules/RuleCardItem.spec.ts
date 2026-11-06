import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RuleCardItem from './RuleCardItem.vue'
import type { RuleCard } from '@/rules/compiler'

function card(invalidReferences: string[]): RuleCard {
  return {
    key: 'card:pt-a',
    cardId: 'pt-a',
    enabled: true,
    sentences: [
      {
        key: 'group:pt-a:0',
        groupKey: 'pt-a:0',
        type: 'TEACHER_UNAVAILABLE',
        classId: null,
        teacherId: 7,
        courseId: null,
        days: [1],
        periodNos: [1],
        amount: null,
        strength: 'HARD',
        enabled: true,
        entryIds: { '1-1': 10 },
        exceptionClassIds: [],
        teacherGroupId: null,
        courseGroupId: null,
        timeGroupId: null,
        invalidReferences,
      },
    ],
  }
}

describe('RuleCardItem: invalid references', () => {
  it('shows a 已失效 tag with the server reasons when a referenced object is paused', () => {
    const wrapper = mount(RuleCardItem, {
      props: { card: card(['教师已停用：王老师']), sentences: ['王老师 周一第1节 不排'] },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.find('[data-testid="rule-invalid-tag"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('已失效')
  })

  it('shows no tag when every reference resolves', () => {
    const wrapper = mount(RuleCardItem, {
      props: { card: card([]), sentences: ['王老师 周一第1节 不排'] },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.find('[data-testid="rule-invalid-tag"]').exists()).toBe(false)
  })
})
