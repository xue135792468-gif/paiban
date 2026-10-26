import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConstraintSummaryBar from './ConstraintSummaryBar.vue'
import type { ConstraintCounts } from './types'

const COUNTS: ConstraintCounts = {
  enabledTypes: 19,
  totalTypes: 24,
  hardTypes: 8,
  entries: 5,
}

function mountBar(overrides: Record<string, unknown> = {}) {
  return mount(ConstraintSummaryBar, {
    props: {
      counts: COUNTS,
      dirty: false,
      canManage: true,
      saving: false,
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConstraintSummaryBar', () => {
  it('三个指标分开显示：启用类型 / 硬约束类型 / 具体条目', () => {
    const wrapper = mountBar()
    expect(wrapper.get('[data-testid="constraint-summary-enabled"]').text()).toContain(
      '已启用 19 / 24 类',
    )
    expect(wrapper.get('[data-testid="constraint-summary-hard"]').text()).toContain('硬约束 8 类')
    expect(wrapper.get('[data-testid="constraint-summary-entries"]').text()).toContain('具体条目 5 条')
  })

  it('未保存时提示，保存后不提示', async () => {
    const wrapper = mountBar({ dirty: true })
    expect(wrapper.get('[data-testid="constraint-unsaved"]').text()).toContain('未保存')
    await wrapper.setProps({ dirty: false })
    expect(wrapper.find('[data-testid="constraint-unsaved"]').exists()).toBe(false)
  })

  it('保存按钮只在有权限时出现，点击后交由页面提交', async () => {
    const wrapper = mountBar()
    await wrapper.get('[data-testid="constraint-save"]').trigger('click')
    expect(wrapper.emitted('save')).toHaveLength(1)

    const readOnly = mountBar({ canManage: false })
    expect(readOnly.find('[data-testid="constraint-save"]').exists()).toBe(false)
  })
})
