import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Component } from 'vue'
import ClassesView from './ClassesView.vue'
import CoursesView from './CoursesView.vue'
import GradesView from './GradesView.vue'
import RoomsView from './RoomsView.vue'
import TeachersView from './TeachersView.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const SEMESTER = { id: 1, name: '测试学期', current: true }

/** Every foundation list page with an "empty list" guide that offers a create button. */
const VIEWS: Array<{ component: Component; title: string; createLabel: string }> = [
  { component: GradesView, title: '还没有年级数据', createLabel: '新建年级' },
  { component: ClassesView, title: '还没有行政班数据', createLabel: '新建行政班' },
  { component: TeachersView, title: '还没有教师数据', createLabel: '新建教师' },
  { component: RoomsView, title: '还没有教室数据', createLabel: '新建教室' },
  { component: CoursesView, title: '还没有课程数据', createLabel: '新建课程' },
]

async function mountView(component: Component, permissions: string[]): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'viewer',
    displayName: '只读用户',
    enabled: true,
    roles: [],
    permissions,
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/admin/grades')
  await router.isReady()

  const wrapper = mount(component, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('基础数据空状态的「新建」按钮', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      return Promise.resolve({ data: [] })
    })
  })

  it('只读用户（无 foundation:manage）看不到新建按钮', async () => {
    for (const view of VIEWS) {
      const wrapper = await mountView(view.component, ['foundation:view'])
      const guide = wrapper.get('[data-testid="empty-state-guide"]')

      expect(guide.text()).toContain(view.title)
      expect(guide.findAll('button')).toHaveLength(0)
      wrapper.unmount()
    }
  })

  it('有 foundation:manage 时显示新建按钮', async () => {
    for (const view of VIEWS) {
      const wrapper = await mountView(view.component, ['foundation:manage'])
      const guide = wrapper.get('[data-testid="empty-state-guide"]')
      const labels = guide.findAll('button').map((button) => button.text())

      expect(labels).toContain(view.createLabel)
      wrapper.unmount()
    }
  })
})
