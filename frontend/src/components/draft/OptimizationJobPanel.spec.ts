import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import OptimizationJobPanel from './OptimizationJobPanel.vue'
import type { OptimizationJob } from '@/api/scheduling'

function job(overrides: Partial<OptimizationJob> = {}): OptimizationJob {
  return {
    id: 7,
    semesterId: 3,
    status: 'RUNNING',
    progress: 40,
    elapsedMs: 4000,
    estimatedRemainingMs: 6000,
    solveSeconds: 10,
    createdAt: '2026-11-02T00:00:00Z',
    sourceDraftId: 42,
    sourceDraftVersion: 4,
    inheritedLockCount: 2,
    ...overrides,
  }
}

function mountPanel(jobValue: OptimizationJob, extra: Record<string, unknown> = {}) {
  return mount(OptimizationJobPanel, {
    props: { job: jobValue, sourceDraftId: 42, sourceDraftName: '原稿 A', ...extra },
    global: { plugins: [ElementPlus] },
  })
}

describe('OptimizationJobPanel', () => {
  it('运行中展示来源、版本、继承锁与进度，可取消', async () => {
    const wrapper = mountPanel(job())

    expect(wrapper.get('[data-testid=optimization-source]').text()).toContain('原稿 A')
    expect(wrapper.get('[data-testid=optimization-source]').text()).toContain('版本 v4')
    expect(wrapper.get('[data-testid=optimization-source]').text()).toContain('继承锁定 2 节')
    expect(wrapper.text()).toContain('40%')

    await wrapper.get('[data-testid=optimization-cancel]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('取消中按钮禁用，不会重复发起取消', async () => {
    const wrapper = mountPanel(job(), { cancelling: true })

    const cancel = wrapper.get('[data-testid=optimization-cancel]')
    expect(cancel.attributes('disabled')).toBeDefined()
    await cancel.trigger('click')
    expect(wrapper.emitted('cancel')).toBeUndefined()
  })

  it('COMPLETED 且可发布时只显示整稿校验通过，不提供发布按钮', async () => {
    const wrapper = mountPanel(
      job({ status: 'COMPLETED', progress: 100, draftId: 88, resultPublishable: true }),
    )

    expect(wrapper.get('[data-testid=optimization-result]').text()).toContain('整稿校验通过')
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('发布课表')
  })

  it('COMPLETED 但不可发布时列出阻碍并明确不等于可发布', async () => {
    const wrapper = mountPanel(
      job({
        status: 'COMPLETED',
        progress: 100,
        draftId: 88,
        resultPublishable: false,
        resultBlockers: ['有 2 节课未排课', '有 1 节课缺教室'],
      }),
    )

    const result = wrapper.get('[data-testid=optimization-result]')
    expect(result.text()).toContain('未达到发布标准')
    const blockers = wrapper.findAll('[data-testid=optimization-blocker]')
    expect(blockers).toHaveLength(2)
    expect(blockers[0].text()).toContain('有 2 节课未排课')
    expect(wrapper.text()).toContain('任务完成不等于可发布')
    expect(wrapper.find('[data-testid=optimization-open-draft]').exists()).toBe(true)
  })

  it('打开新草稿与原稿比较分别抛出目标草稿 id', async () => {
    const wrapper = mountPanel(
      job({ status: 'COMPLETED', progress: 100, draftId: 88, resultPublishable: true }),
    )

    await wrapper.get('[data-testid=optimization-open-draft]').trigger('click')
    await wrapper.get('[data-testid=optimization-compare]').trigger('click')

    expect(wrapper.emitted('open-draft')).toEqual([[88]])
    expect(wrapper.emitted('compare')).toEqual([[88]])
  })

  it('FAILED 展示错误并可重新发起', async () => {
    const wrapper = mountPanel(job({ status: 'FAILED', errorMessage: '授课任务已变更' }))

    expect(wrapper.get('[data-testid=optimization-failed]').text()).toContain('授课任务已变更')
    await wrapper.get('[data-testid=optimization-retry]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('CANCELLED 展示提示并可重新发起', async () => {
    const wrapper = mountPanel(job({ status: 'CANCELLED' }))

    expect(wrapper.get('[data-testid=optimization-cancelled]').text()).toContain('已取消')
    expect(wrapper.find('[data-testid=optimization-retry]').exists()).toBe(true)
  })
})
