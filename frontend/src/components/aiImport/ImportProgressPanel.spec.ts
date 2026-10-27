import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ImportProgressPanel from './ImportProgressPanel.vue'
import type { ImportProgress } from './timetablePreview'

function mountPanel(progress: ImportProgress = { total: 4, completed: 1, failed: 0, currentBatch: '一年级2班' }) {
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

  it('规划阶段不显示伪造的批次数', () => {
    const wrapper = mountPanel({ total: 0, completed: 0, failed: 0, currentBatch: null, phase: 'PLANNING' })
    expect(wrapper.get('[data-testid="progress-count"]').text()).toContain('正在识别工作表结构')
    expect(wrapper.get('.card-title').text()).toContain('正在识别工作簿结构')
    expect(wrapper.text()).not.toContain('已完成 0/0 批')
  })

  it('工作簿取消后未完成的批次可重试，提示不说成整表重来', () => {
    const wrapper = mount(ImportProgressPanel, {
      props: { progress: { total: 3, completed: 1, failed: 0, phase: 'EXTRACTING' }, keptForRetry: true },
      global: { plugins: [ElementPlus] },
    })
    const hint = wrapper.get('.hint').text()
    expect(hint).toContain('未完成的批次会列为失败批次')
    expect(hint).not.toContain('已停止的批次不会保留')
  })
})
