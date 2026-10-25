import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DraftPublishBlockers from './DraftPublishBlockers.vue'
import type { DraftPublishBlocker } from './types'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

function blocker(overrides: Partial<DraftPublishBlocker> = {}): DraftPublishBlocker {
  return {
    code: 'MISSING_ROOMS',
    message: '有 546 节课还没有分配教室',
    count: 546,
    action: 'ASSIGN_ROOMS',
    ...overrides,
  }
}

function mountBlockers(blockers: DraftPublishBlocker[]) {
  return mount(DraftPublishBlockers, {
    props: { blockers },
    global: { plugins: [ElementPlus] },
  })
}

describe('DraftPublishBlockers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('没有阻塞项时不渲染任何提示', () => {
    const wrapper = mountBlockers([])

    expect(wrapper.find('[data-testid=publish-blockers]').exists()).toBe(false)
  })

  it('按类型展示原因与受影响条数', () => {
    const wrapper = mountBlockers([
      blocker(),
      blocker({
        code: 'UNSCHEDULED_LESSONS',
        message: '还有 2 节课未排课',
        count: 2,
        action: 'EDIT_LESSONS',
      }),
    ])

    const rows = wrapper.findAll('[data-testid=publish-blocker]')
    expect(rows).toHaveLength(2)
    expect(rows[0].attributes('data-code')).toBe('MISSING_ROOMS')
    expect(rows[0].text()).toContain('有 546 节课还没有分配教室')
    expect(rows[1].text()).toContain('还有 2 节课未排课')
  })

  it('缺教室给出教室管理入口', async () => {
    const wrapper = mountBlockers([blocker()])

    await wrapper.get('[data-testid=blocker-action-MISSING_ROOMS]').trigger('click')

    expect(push).toHaveBeenCalledWith('/admin/rooms')
  })

  it('未排课打开本页的未排课抽屉而不是跳转', async () => {
    const wrapper = mountBlockers([
      blocker({ code: 'UNSCHEDULED_LESSONS', message: '还有 2 节课未排课', count: 2, action: 'EDIT_LESSONS' }),
    ])

    await wrapper.get('[data-testid=blocker-action-UNSCHEDULED_LESSONS]').trigger('click')

    expect(wrapper.emitted('edit-lessons')).toHaveLength(1)
    expect(push).not.toHaveBeenCalled()
  })

  it('作息引用失效指向学期与作息', async () => {
    const wrapper = mountBlockers([
      blocker({ code: 'INVALID_PERIODS', message: '有 1 节课的作息引用失效', count: 1, action: 'EDIT_PERIODS' }),
    ])

    await wrapper.get('[data-testid=blocker-action-INVALID_PERIODS]').trigger('click')

    expect(push).toHaveBeenCalledWith('/admin/semesters')
  })

  it('硬冲突只展示原因，不误导成别的入口', () => {
    const wrapper = mountBlockers([
      blocker({ code: 'HARD_CONFLICTS', message: '课表存在 3 处排课冲突', count: 3, action: 'NONE' }),
    ])

    expect(wrapper.text()).toContain('课表存在 3 处排课冲突')
    expect(wrapper.find('[data-testid=blocker-action-HARD_CONFLICTS]').exists()).toBe(false)
  })
})
