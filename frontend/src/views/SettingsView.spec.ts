import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SettingsView from './SettingsView.vue'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))
vi.mock('@/api/http', () => ({ http: httpMock }))

const dingtalkConfig = {
  enabled: true,
  appKey: 'ding-key',
  appSecret: '',
  hasSecret: true,
  agentId: '123',
  corpId: 'corp-1',
  syncStrategy: 'dingtalk',
}

beforeEach(() => {
  vi.clearAllMocks()
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/integrations/dingtalk/config') return Promise.resolve({ data: dingtalkConfig })
    if (url === '/integrations/feishu/config') {
      return Promise.resolve({ data: { enabled: false, syncStrategy: 'feishu' } })
    }
    if (url === '/settings') return Promise.resolve({ data: {} })
    return Promise.resolve({ data: [] })
  })
  httpMock.delete.mockResolvedValue({ data: {} })
})

async function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/settings/general')
  await router.isReady()
  const wrapper = mount(SettingsView, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('SettingsView: 清除钉钉配置', () => {
  it('确认后调用清除接口并说明可在最近删除恢复', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountView()

    await wrapper.find('[data-testid="dingtalk-clear"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0][0])).toContain('最近删除')
    expect(httpMock.delete).toHaveBeenCalledWith('/integration/dingtalk')
    wrapper.unmount()
  })
})
