import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TimetableView from './TimetableView.vue'
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
    permissions: ['timetable:view', 'timetable:view:class', 'scheduling:run', 'export:timetable'],
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/workbench/timetable')
  await router.isReady()
  const wrapper = mount(TimetableView, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('TimetableView 无已发布基线', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/current') {
        return Promise.resolve({ status: 200, data: { id: 1, name: '测试学期' } })
      }
      if (url === '/timetable/publish-info') {
        return Promise.resolve({
          status: 200,
          data: { published: false, status: 'WITHDRAWN', withdrawnAt: '2026-01-01T00:00:00Z' },
        })
      }
      return Promise.resolve({ status: 200, data: [] })
    })
  })

  it('撤回后显示空态并引导去课表工作台，而不是报错', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('当前学期还没有发布课表，可在「课表工作台」发布后再查看。')
    const guide = wrapper.get('[data-testid="empty-state-guide"]')
    expect(guide.text()).toContain('当前学期还没有发布课表，可在「课表工作台」发布后再查看')
    expect(guide.text()).toContain('去课表工作台')
  })
})
