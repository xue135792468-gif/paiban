import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ActiveJobCard from './ActiveJobCard.vue'
import {
  getJobHardConflictCount,
  getJobStatusTag,
  isCompletedWithConflicts,
  parseHardConflicts,
} from './jobStatus'
import type { DraftItem, JobItem } from './types'

function makeJob(overrides: Partial<JobItem> = {}): JobItem {
  return {
    id: 10,
    semesterId: 1,
    status: 'COMPLETED',
    score: '0hard/0soft',
    progress: 100,
    elapsedMs: 2500,
    solveSeconds: 60,
    createdAt: '2026-01-01T00:00:00Z',
    draftId: 55,
    draftName: '方案 A',
    ...overrides,
  }
}

describe('jobStatus helper', () => {
  it('正确解析 score 中的硬冲突数', () => {
    expect(parseHardConflicts('-22hard/0soft')).toBe(22)
    expect(parseHardConflicts('-5 hard / -3 soft')).toBe(5)
    expect(parseHardConflicts('0hard/-10soft')).toBe(0)
    expect(parseHardConflicts(null)).toBe(0)
  })

  it('从 score / draft / diagnostics 识别带冲突的 COMPLETED 任务', () => {
    expect(isCompletedWithConflicts(makeJob({ score: '-22hard/0soft' }))).toBe(true)
    expect(
      isCompletedWithConflicts(
        makeJob({ score: '0hard/0soft' }),
        { scoreHard: -4 } as DraftItem,
      ),
    ).toBe(true)
    expect(
      isCompletedWithConflicts(
        makeJob({
          score: null,
          diagnostics: [
            { teacherId: 1, hardCount: 3, messages: ['x'], gradeIds: [1], classNames: ['1班'] },
          ],
        }),
      ),
    ).toBe(true)
    expect(getJobHardConflictCount(makeJob({ score: '-7hard/0soft' }))).toBe(7)
    expect(isCompletedWithConflicts(makeJob({ score: '0hard/0soft', resultPublishable: true }))).toBe(false)
    expect(getJobStatusTag('COMPLETED', true)).toEqual({
      type: 'warning',
      label: '已完成，但存在冲突暂不可发布',
    })
  })
})

describe('ActiveJobCard', () => {
  it('提交失败时展示失败提示且不渲染打开草稿按钮', () => {
    const wrapper = mount(ActiveJobCard, {
      props: {
        activeJob: null,
        associatedDraft: null,
        submitError: '其他年级还没有已排课表，无法保持不动',
        cancelling: false,
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.find('[data-testid="active-job-card"]').exists()).toBe(false)
    const errCard = wrapper.get('[data-testid="job-submit-error"]')
    expect(errCard.text()).toContain('提交失败')
    expect(errCard.text()).toContain('其他年级还没有已排课表，无法保持不动')
    expect(errCard.text()).not.toContain('打开草稿')
    wrapper.unmount()
  })

  it('COMPLETED 但存在硬冲突时展示橙色警告态而非绿色已完成', () => {
    const wrapper = mount(ActiveJobCard, {
      props: {
        activeJob: makeJob({ score: '-22hard/0soft' }),
        associatedDraft: null,
        submitError: null,
        cancelling: false,
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.get('[data-testid="active-job-status"]').text()).toBe(
      '已完成，但存在冲突暂不可发布',
    )
    expect(wrapper.get('.active-job-card').classes()).toContain('is-warning')
    expect(wrapper.findComponent({ name: 'ElProgress' }).props('status')).toBe('warning')
    expect(wrapper.get('[data-testid="job-conflict-warning"]').text()).toContain('22 处硬冲突')
    expect(wrapper.get('[data-testid="metrics-hard-conflicts"]').text()).toContain('22 处硬冲突')
    wrapper.unmount()
  })

  it('COMPLETED 且无冲突时展示绿色已完成态', () => {
    const wrapper = mount(ActiveJobCard, {
      props: {
        activeJob: makeJob({ score: '0hard/0soft', resultPublishable: true }),
        associatedDraft: null,
        submitError: null,
        cancelling: false,
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.get('[data-testid="active-job-status"]').text()).toBe('已完成')
    expect(wrapper.get('.active-job-card').classes()).not.toContain('is-warning')
    expect(wrapper.findComponent({ name: 'ElProgress' }).props('status')).toBe('success')
    expect(wrapper.find('[data-testid="job-conflict-warning"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
