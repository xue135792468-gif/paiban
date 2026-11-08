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
vi.mock('@/api/http', async () => {
  // Keep the real constant so a change to the timeout budget fails this spec, and only
  // swap the axios instance for the spies.
  const actual = await vi.importActual<typeof import('@/api/http')>('@/api/http')
  return { http: httpMock, DINGTALK_SYNC_TIMEOUT_MS: actual.DINGTALK_SYNC_TIMEOUT_MS }
})

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
    expect(httpMock.delete).toHaveBeenCalledWith('/integrations/dingtalk/config')
    wrapper.unmount()
  })
})

const previewFixture = {
  success: true,
  message: '预演完成：扫描 1 个部门 / 1 人，耗时 12 ms；新增 1',
  departmentsScanned: 1,
  usersScanned: 1,
  skippedDepartments: 0,
  failedDepartments: 0,
  elapsedMs: 12,
  skippedDepartmentNames: [],
  summary: { create: 1, merge: 0, suspected: 0, skip: 0 },
  departments: [
    {
      department: '全校教职工',
      items: [
        {
          userId: 'u-1',
          name: '王老师',
          employeeNo: 'EMP101',
          phone: null,
          departments: ['全校教职工'],
          active: true,
          category: 'CREATE',
          reason: '钉钉通讯录新增',
          matchedTeacherId: null,
          matchedTeacherName: null,
          fills: [],
          conflicts: [],
          candidates: [],
        },
      ],
    },
  ],
}

describe('SettingsView: 钉钉同步超时', () => {
  it('预演和确认都用 180 秒超时，不再被 30 秒默认值掐断', async () => {
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/integrations/dingtalk/sync/preview') {
        return Promise.resolve({ data: previewFixture })
      }
      if (url === '/integrations/dingtalk/sync/confirm') {
        return Promise.resolve({ data: { success: true, message: '同步完成' } })
      }
      return Promise.resolve({ data: {} })
    })
    const wrapper = await mountView()

    await wrapper.find('[data-testid="dingtalk-preview"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith(
      '/integrations/dingtalk/sync/preview',
      undefined,
      { timeout: 180000 },
    )

    await wrapper.find('[data-testid="dingtalk-confirm"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith(
      '/integrations/dingtalk/sync/confirm',
      { excludedDepartments: [], excludedUserIds: [], suspectedDecisions: [] },
      { timeout: 180000 },
    )
    wrapper.unmount()
  })
})
