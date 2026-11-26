import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SubstitutionsView from './SubstitutionsView.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

async function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    enabled: true,
    roles: [],
    permissions: ['substitution:view', 'substitution:submit'],
    teacherId: 7,
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/substitutions')
  await router.isReady()
  const wrapper = mount(SubstitutionsView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('SubstitutionsView 无学期与有学期行为', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('没有当前学期（204）时不请求 /classes、/substitution-requests、/substitutions 或课表，且显示引导', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/current') return Promise.resolve({ status: 204, data: '' })
      return Promise.resolve({ status: 200, data: [] })
    })

    const wrapper = await mountView()

    const urls = httpMock.get.mock.calls.map((call) => call[0])
    expect(urls).toEqual(['/semesters/current'])
    expect(urls).not.toContain('/classes')
    expect(urls).not.toContain('/substitution-requests')
    expect(urls).not.toContain('/substitutions')
    expect(wrapper.find('[data-testid="empty-state-guide"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('还没有学期')
    wrapper.unmount()
  })

  it('有当前学期时正常请求 /classes、/substitution-requests 与 /substitutions', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: { id: 1, name: '2025 秋' } })
      if (url === '/classes') return Promise.resolve({ status: 200, data: [{ id: 1, name: '高一(1)班', active: true }] })
      return Promise.resolve({ status: 200, data: [] })
    })

    const wrapper = await mountView()

    const urls = httpMock.get.mock.calls.map((call) => call[0])
    expect(urls).toContain('/semesters/current')
    expect(urls).toContain('/classes')
    expect(urls).toContain('/substitution-requests')
    expect(urls).toContain('/substitutions')
    wrapper.unmount()
  })
})
