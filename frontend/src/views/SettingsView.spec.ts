import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SettingsView from './SettingsView.vue'
import { useAuthStore } from '@/stores/auth'

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

const SUPER_ADMIN_PERMISSIONS = [
  'settings:manage',
  'user:manage',
  'foundation:manage',
  'integration:manage',
  'audit:view:account',
  'substitution:view',
]

async function mountView(permissions: string[] = SUPER_ADMIN_PERMISSIONS) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'tester',
    displayName: '测试账号',
    enabled: true,
    roles: ['super_admin'],
    permissions,
  }
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
  scanComplete: true,
  deactivationWarning: null,
  deactivations: [],
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
      {
        excludedDepartments: [],
        excludedUserIds: [],
        suspectedDecisions: [],
        deactivationSelections: [],
      },
      { timeout: 180000 },
    )
    wrapper.unmount()
  })

  it('预演进行中把按钮文案换成正在拉取通讯录', async () => {
    let release: ((value: unknown) => void) | undefined
    httpMock.post.mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve
        }),
    )
    const wrapper = await mountView()

    await wrapper.find('[data-testid="dingtalk-preview"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="dingtalk-preview"]').text()).toContain('正在拉取通讯录')

    release?.({ data: previewFixture })
    await flushPromises()
    wrapper.unmount()
  })
})

describe('SettingsView: 钉钉配置只对集成配置权限可见', () => {
  it('教务管理员看不到钉钉区块，也不会请求集成配置接口', async () => {
    const wrapper = await mountView(['settings:manage', 'foundation:manage'])

    expect(wrapper.find('#dingtalk-integration').exists()).toBe(false)
    const urls = httpMock.get.mock.calls.map((call) => String(call[0]))
    expect(urls.some((url) => url.includes('/integrations/dingtalk'))).toBe(false)
    wrapper.unmount()
  })

  it('超级管理员能看到钉钉区块', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('#dingtalk-integration').exists()).toBe(true)
    wrapper.unmount()
  })

  it('教务保存设置时不回写钉钉字段，避免整单被拒', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/settings') {
        return Promise.resolve({
          data: {
            'school.name': '本校',
            'substitution.max-plan-teachers': '5',
            'integration.dingtalk.event-token': '',
          },
        })
      }
      return Promise.resolve({ data: [] })
    })
    const wrapper = await mountView(['settings:manage', 'foundation:manage'])

    await wrapper.get('.toolbar .el-button').trigger('click')
    await flushPromises()

    expect(httpMock.put).toHaveBeenCalledWith('/settings', {
      'school.name': '本校',
      'substitution.max-plan-teachers': '5',
    })
    wrapper.unmount()
  })
})

describe('SettingsView: 调代通知发送记录入口', () => {
  it('超级管理员看到发送记录入口', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('通知发送记录')
    wrapper.unmount()
  })

  it('教务管理员看到折叠入口，不再加载旧额外收件人，展开时才查询日志', async () => {
    const wrapper = await mountView(['settings:manage', 'substitution:view'])
    const requestedUrls = httpMock.get.mock.calls.map((call) => String(call[0]))

    expect(wrapper.text()).toContain('通知发送记录')
    expect(wrapper.text()).not.toContain('调代通知收件人')
    expect(requestedUrls).not.toContain('/settings/substitution-recipients')
    expect(requestedUrls).not.toContain('/notification-delivery-logs')
    wrapper.unmount()
  })

  it('老师不渲染发送记录入口，也不会请求日志接口', async () => {
    const wrapper = await mountView(['substitution:view:own'])
    const requestedUrls = httpMock.get.mock.calls.map((call) => String(call[0]))

    expect(wrapper.text()).not.toContain('通知发送记录')
    expect(requestedUrls.some((url) => url.includes('/notification-delivery-logs'))).toBe(false)
    wrapper.unmount()
  })
})
