import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DraftEditorToolbar from './DraftEditorToolbar.vue'

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
      publishable: true,
      isJunior: false,
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
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
