import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ImportProgressPanel from './ImportProgressPanel.vue'

function mountPanel(progress = { total: 4, completed: 1, failed: 0, currentBatch: '一年级2班' }) {
  return mount(ImportProgressPanel, {
    props: { progress },
    global: { plugins: [ElementPlus] },
  })
}

describe('解析进度面板', () => {
  it('显示已完成批数与正在解析的班', () => {
    const wrapper = mountPanel()
    expect(wrapper.get('[data-testid="progress-count"]').text()).toContain('已完成 1/4 批')
    expect(wrapper.get('[data-testid="progress-current"]').text()).toContain('一年级2班')
  })

  it('停止说明与真相一致：停止的批次不保留，重析是整表重来', () => {
    const hint = mountPanel().get('.hint').text()
    expect(hint).toContain('已停止的批次不会保留')
    expect(hint).toContain('重新解析整张表')
    // The old wording promised the dropped batches could be resumed later, which they
    // cannot: a re-parse starts the whole table again.
    expect(hint).not.toContain('剩下的可以之后重新解析')
  })
})
