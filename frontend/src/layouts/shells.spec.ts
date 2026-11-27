import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import DataLayout from './DataLayout.vue'
import SettingsLayout from './SettingsLayout.vue'
import SubstitutionLayout from './SubstitutionLayout.vue'
import WorkbenchLayout from './WorkbenchLayout.vue'

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasPermission: () => true }),
}))

async function mountLayout(component: object, path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/workbench', name: 'scheduling', component: { template: '<div />' } },
      { path: '/workbench/drafts/:id', name: 'draft-editor', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(component, { global: { plugins: [router, ElementPlus] } })
}

describe('域级壳层只保留局部导航', () => {
  it.each([
    [DataLayout, '/data/grades'],
    [SettingsLayout, '/settings/general'],
    [SubstitutionLayout, '/substitution'],
    [WorkbenchLayout, '/workbench'],
  ])('不重复渲染外层页面标题和副标题', async (component, path) => {
    const wrapper = await mountLayout(component, path)
    expect(wrapper.find('.page-head').exists()).toBe(false)
    expect(wrapper.find('h2').exists()).toBe(false)
    wrapper.unmount()
  })

  it('数据管理按建校顺序分为三组共九项', async () => {
    const wrapper = await mountLayout(DataLayout, '/data/grades')
    expect(wrapper.findAll('.group-label').map((group) => group.text())).toEqual([
      '基础档案',
      '排课输入',
      '批量导入',
    ])
    expect(wrapper.findAll('.page-tab').map((tab) => tab.text())).toEqual([
      '学期与作息',
      '年级',
      '行政班',
      '课程',
      '教师',
      '授课任务',
      '任课表',
      '数据导入',
      '导入配方',
    ])
    expect(wrapper.find('[data-tour="/data/classes"]').text()).toBe('行政班')
    wrapper.unmount()
  })

  it('进入草稿编辑器时隐藏工作台顶层页签', async () => {
    const wrapper = await mountLayout(WorkbenchLayout, '/workbench/drafts/12')
    expect(wrapper.find('.page-tabs').exists()).toBe(false)
    await wrapper.vm.$router.push('/workbench')
    expect(wrapper.find('.page-tabs').exists()).toBe(true)
    wrapper.unmount()
  })
})
