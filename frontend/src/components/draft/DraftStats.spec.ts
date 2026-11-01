import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DraftStats from './DraftStats.vue'

function mountStats(props: Record<string, unknown> = {}) {
  return mount(DraftStats, {
    props: {
      hardConflicts: 1,
      scoreLabel: '-10 hard',
      lessonCount: 12,
      unassignedCount: 1,
      ...props,
    },
  })
}

describe('DraftStats', () => {
  it('把得分标为求解得分，并提示手工调整后可能过期', () => {
    const wrapper = mountStats()
    const text = wrapper.text()

    expect(text).toContain('求解得分')
    expect(text).toContain('-10 hard')
    expect(text).toContain('参考值；手工调整后可能过期')
  })

  it('硬冲突仍是独立指标', () => {
    const wrapper = mountStats()

    expect(wrapper.text()).toContain('硬冲突')
    expect(wrapper.findAll('.stat')).toHaveLength(4)
    expect(wrapper.get('.stat-hint').text()).toBe('参考值；手工调整后可能过期')
  })
})
