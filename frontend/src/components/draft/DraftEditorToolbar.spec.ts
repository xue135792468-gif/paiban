import { describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DraftEditorToolbar from './DraftEditorToolbar.vue'

const { push } = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

function mountToolbar(overrides: Record<string, unknown> = {}) {
  return mount(DraftEditorToolbar, {
    props: {
      draftName: '高一课表',
      status: 'PUBLISHED',
      undoCount: 0,
      canUndo: false,
      undoing: false,
      canEdit: true,
      canPublish: true,
      canOptimize: true,
      lockedCount: 0,
      lessonCount: 4,
      publishable: true,
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

describe('DraftEditorToolbar 撤回入口', () => {
  it('已发布且管理员有发布权限时显示撤回按钮', () => {
    const wrapper = mountToolbar()

    expect(wrapper.get('[data-testid=withdraw-draft]').text()).toBe('撤回为草稿')
  })

  it('草稿状态不显示撤回按钮', () => {
    const wrapper = mountToolbar({ status: 'DRAFT' })

    expect(wrapper.find('[data-testid=withdraw-draft]').exists()).toBe(false)
    expect(wrapper.text()).toContain('发布课表')
  })

  it('没有 timetable:publish 权限时不显示撤回按钮', () => {
    const wrapper = mountToolbar({ canPublish: false })

    expect(wrapper.find('[data-testid=withdraw-draft]').exists()).toBe(false)
  })

  it('点击撤回向页面抛出 withdraw 事件', async () => {
    const wrapper = mountToolbar()

    await wrapper.get('[data-testid=withdraw-draft]').trigger('click')

    expect(wrapper.emitted('withdraw')).toHaveLength(1)
  })
})

describe('DraftEditorToolbar 溢出菜单', () => {
  it('低频操作收进更多，保留继续优化和含锁提示', async () => {
    const wrapper = mountToolbar({ status: 'DRAFT', lockedCount: 2 })
    expect(wrapper.text()).not.toContain('比较草稿')
    await wrapper.get('[data-testid="draft-more-menu"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('继续优化')
    expect(document.body.textContent).toContain('继承锁定 2 节')
    expect(document.body.textContent).toContain('比较草稿')
    expect(document.body.textContent).toContain('重命名')
    expect(document.body.textContent).toContain('刷新')
    expect(document.body.textContent).toContain('删除草稿')
    wrapper.unmount()
  })

  it('全部锁定时禁用优化并写明原因', async () => {
    const wrapper = mountToolbar({ status: 'DRAFT', lockedCount: 4, lessonCount: 4 })
    await wrapper.get('[data-testid="draft-more-menu"]').trigger('click')
    await flushPromises()
    const items = document.querySelectorAll('[data-testid="optimization-entry"]')
    const item = items[items.length - 1]
    expect(item.getAttribute('aria-disabled')).toBe('true')
    expect(item.textContent).toContain('全部课节已锁定')
    wrapper.unmount()
  })
})
describe('DraftEditorToolbar 重新发布后的调代提示', () => {
  it('发布后仍有撤回遗留的调代时显示提示并可跳转记录页', async () => {
    const wrapper = mountToolbar({ cancelledSubstitutions: 2 })

    const alert = wrapper.get('[data-testid=reprocess-substitutions]')
    expect(alert.text()).toContain('有 2 条撤回时取消的调代需要重新处理')

    await wrapper.get('[data-testid=reprocess-substitutions-link]').trigger('click')
    expect(push).toHaveBeenCalledWith({
      path: '/substitution/requests',
      query: { tab: 'records' },
    })
  })

  it('没有遗留调代时不显示提示', () => {
    const wrapper = mountToolbar({ cancelledSubstitutions: 0 })

    expect(wrapper.find('[data-testid=reprocess-substitutions]').exists()).toBe(false)
  })

  it('关闭提示时向页面抛出 dismiss-reprocess，让状态回到 0', async () => {
    const wrapper = mountToolbar({ cancelledSubstitutions: 1 })

    await wrapper.get('[data-testid=reprocess-substitutions] .el-alert__close-btn').trigger('click')

    expect(wrapper.emitted('dismiss-reprocess')).toHaveLength(1)
  })
})
