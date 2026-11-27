import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import DraftStats from './DraftStats.vue'

async function mountStats(props: Record<string, unknown> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [] })
  return mount(DraftStats, {
    props: {
      hardConflicts: 1,
      scoreLabel: '-10 hard / 0 soft',
      lessonCount: 12,
      unassignedCount: 1,
      lockedCount: 2,
      conflictTotal: 1,
      ...props,
    },
    global: { plugins: [router, ElementPlus] },
  })
}

describe('DraftStats 紧凑摘要', () => {
  it('冲突数主显，原得分只出现在排课详情弹层', async () => {
    const wrapper = await mountStats()
    expect(wrapper.get('[data-testid="draft-status-summary"]').text()).toContain('1 处排课冲突')
    expect(wrapper.text()).toContain('已排 11 / 12 节')
    expect(wrapper.text()).toContain('已锁定 2 节')
    expect(wrapper.get('[data-testid="draft-status-summary"]').text()).not.toContain('-10 hard')
    expect(wrapper.get('[data-testid="draft-score-info"]').text()).toContain('排课详情')
    await wrapper.get('[data-testid="blocker-action-HARD_CONFLICTS"]').trigger('click')
    expect(wrapper.emitted('locate-conflict')).toHaveLength(1)
  })

  it('零冲突主显可发布', async () => {
    const wrapper = await mountStats({ hardConflicts: 0, conflictTotal: 0, unassignedCount: 0 })
    expect(wrapper.text()).toContain('无冲突 · 可发布')
    expect(wrapper.find('[data-testid="blocker-action-HARD_CONFLICTS"]').exists()).toBe(false)
  })
})
