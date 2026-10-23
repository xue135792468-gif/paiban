import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PartialResultPanel from './PartialResultPanel.vue'
import type { FailedBatch, ImportProgress, PreviewRow } from './timetablePreview'

function lessonRow(className: string, period: string, course: string): PreviewRow {
  return {
    values: {
      className,
      dayOfWeek: '周一',
      periodNo: period,
      courseName: course,
      teacherName: '张三',
      roomName: '101',
    },
    confidence: {},
    lowConfidence: [],
  }
}

function failedBatch(index: number, label: string, reason: string): FailedBatch {
  return { index, label, reason }
}

function mountPanel(
  rows: PreviewRow[],
  failedBatches: FailedBatch[],
  progress: ImportProgress | null = null,
  retrying = false,
) {
  return mount(PartialResultPanel, {
    props: { rows, failedBatches, progress, retrying },
    global: { plugins: [ElementPlus] },
  })
}

describe('部分结果面板', () => {
  it('把已解析的课与失败批次分组展示，并说明入库被暂时关闭', () => {
    const wrapper = mountPanel(
      [lessonRow('一年级1班', '1', '语文'), lessonRow('一年级3班', '2', '数学')],
      [
        failedBatch(2, '一年级2班', '第 2/4 批（一年级2班）解析失败：模型返回为空'),
        failedBatch(4, '一年级4班', '第 4/4 批（一年级4班）解析失败：超时'),
      ],
      { total: 4, completed: 2, failed: 2, currentBatch: null },
    )

    const header = wrapper.get('[data-testid="partial-summary"]').text()
    expect(header).toContain('部分结果')
    expect(header).toContain('已解析 2/4 批')

    const success = wrapper.get('[data-testid="partial-success"]').text()
    expect(success).toContain('已解析 2 节课')
    expect(success).toContain('2 个班')

    const failed = wrapper.get('[data-testid="partial-failed"]')
    expect(failed.text()).toContain('失败批次')
    expect(failed.text()).toContain('一年级2班')
    expect(failed.text()).toContain('一年级4班')
    expect(failed.text()).toContain('模型返回为空')
    expect(failed.text()).toContain('超时')

    expect(wrapper.get('[data-testid="partial-hint"]').text()).toContain(
      '请先重试失败批次或放弃导入',
    )
  })

  it('成功批次的课默认收起，展开后能看到每一节', async () => {
    const wrapper = mountPanel(
      [lessonRow('一年级1班', '1', '语文'), lessonRow('一年级3班', '2', '数学')],
      [failedBatch(2, '一年级2班', '第 2/4 批（一年级2班）解析失败：模型返回为空')],
      { total: 4, completed: 2, failed: 2, currentBatch: null },
    )

    expect(wrapper.find('[data-testid="partial-success-table"]').exists()).toBe(false)

    await wrapper.get('[data-testid="partial-toggle-rows"]').trigger('click')
    await flushPromises()

    const table = wrapper.get('[data-testid="partial-success-table"]')
    expect(table.text()).toContain('一年级1班')
    expect(table.text()).toContain('语文')
    expect(table.text()).toContain('一年级3班')
    expect(table.text()).toContain('数学')
  })

  it('没有进度信息时也能显示已有的课与失败班（重启后恢复的旧会话）', () => {
    const wrapper = mountPanel(
      [lessonRow('一年级1班', '1', '语文')],
      [failedBatch(2, '一年级2班', '第 2/2 批（一年级2班）解析失败：服务重启中断了这一批，请重试')],
    )

    const success = wrapper.get('[data-testid="partial-success"]').text()
    expect(success).toContain('已解析 1 节课')
    expect(wrapper.get('[data-testid="partial-failed"]').text()).toContain('一年级2班')
    expect(wrapper.get('[data-testid="partial-hint"]').text()).toContain('请先重试失败批次或放弃导入')
  })

  it('提供重试失败批次的入口，点下去把重试交给页面', async () => {
    const wrapper = mountPanel(
      [lessonRow('一年级1班', '1', '语文')],
      [failedBatch(2, '一年级2班', '第 2/4 批（一年级2班）解析失败：模型返回为空')],
      { total: 4, completed: 3, failed: 1, currentBatch: null },
    )

    const retry = wrapper.get('[data-testid="partial-retry"]')
    expect(retry.text()).toContain('重试失败批次')
    expect(wrapper.get('[data-testid="partial-retry-hint"]').text()).toContain('1 个班')

    await retry.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('重试请求发出后按钮进入加载，避免重复点', () => {
    const wrapper = mountPanel(
      [lessonRow('一年级1班', '1', '语文')],
      [failedBatch(2, '一年级2班', '第 2/4 批（一年级2班）解析失败：模型返回为空')],
      { total: 4, completed: 3, failed: 1, currentBatch: null },
      true,
    )

    expect(wrapper.get('[data-testid="partial-retry"]').classes()).toContain('is-loading')
  })
})
