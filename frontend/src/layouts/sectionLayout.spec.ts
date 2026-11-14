import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SettingsLayout from './SettingsLayout.vue'
import WorkbenchLayout from './WorkbenchLayout.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/settings/:rest(.*)*', component: { template: '<div />' } },
    { path: '/workbench/:rest(.*)*', component: { template: '<div />' } },
  ],
})

async function mountLayout(component: typeof SettingsLayout, permissions: string[], path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'tester',
    displayName: '测试账号',
    enabled: true,
    roles: [],
    permissions,
  }
  await router.push(path)
  await router.isReady()
  return mount(component, { global: { plugins: [pinia, router] } })
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('设置页签跟随权限', () => {
  it('教务管理员看不到账号页签', async () => {
    const wrapper = await mountLayout(SettingsLayout, ['settings:manage', 'foundation:manage'], '/settings/general')
    const labels = wrapper.findAll('.page-tab').map((tab) => tab.text())
    expect(labels).toEqual(['设置', '最近删除'])
    wrapper.unmount()
  })

  it('超级管理员能看到账号页签', async () => {
    const wrapper = await mountLayout(
      SettingsLayout,
      ['settings:manage', 'foundation:manage', 'user:manage', 'integration:manage'],
      '/settings/general',
    )
    const labels = wrapper.findAll('.page-tab').map((tab) => tab.text())
    expect(labels).toEqual(['设置', '账号', '最近删除'])
    wrapper.unmount()
  })
})

describe('课表工作台页签跟随权限', () => {
  it('老师只看到课表页签', async () => {
    const wrapper = await mountLayout(
      WorkbenchLayout,
      ['timetable:view', 'timetable:view:own', 'assignment:view:own', 'substitution:submit'],
      '/workbench/timetable',
    )
    const labels = wrapper.findAll('.page-tab').map((tab) => tab.text())
    expect(labels).toEqual(['课表'])
    wrapper.unmount()
  })
})
