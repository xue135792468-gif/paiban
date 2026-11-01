import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import SchedulingView from './SchedulingView.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const SEMESTER = { id: 1, name: '2025 秋季学期', current: true }

async function mountScheduling(
  permissions = ['scheduling:run', 'foundation:manage'],
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'scheduler',
    displayName: '排课员',
    enabled: true,
    roles: [],
    permissions,
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/scheduling', component: { template: '<div />' } },
      { path: '/admin/ai-import', component: { template: '<div />' } },
      { path: '/admin/drafts/:id', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/scheduling')
  await router.isReady()

  const wrapper = mount(SchedulingView, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text() === label)
  if (!found) throw new Error(`button not found: ${label}`)
  return found
}

describe('排课页的导入课表入口', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      return Promise.resolve({ data: [] })
    })
  })

  it('进入 AI 导入页并默认整本工作簿目标', async () => {
    const { wrapper, router } = await mountScheduling()

    await button(wrapper, '导入课表').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/admin/ai-import')
    expect(router.currentRoute.value.query.target).toBeUndefined()
  })

  it('没有基础数据管理权限时不显示导入入口', async () => {
    const { wrapper } = await mountScheduling(['scheduling:run'])

    expect(wrapper.findAll('button').some((candidate) => candidate.text() === '导入课表')).toBe(false)
  })
})

describe('空白学期的 warm start 提示', () => {
  const runningJob = {
    id: 1,
    semesterId: 1,
    status: 'RUNNING',
    score: null,
    progress: 10,
    elapsedMs: 1000,
    estimatedRemainingMs: 5000,
    errorMessage: null,
    draftId: null,
    draftName: '方案 A',
    warmStartSource: 'NONE',
    solveSeconds: 60,
    createdAt: '2026-01-01T00:00:00Z',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: [runningJob] })
      if (url === '/scheduling-jobs/1') return Promise.resolve({ data: runningJob })
      return Promise.resolve({ data: [] })
    })
  })

  it('来源为 NONE 时显示信息性提示', async () => {
    const { wrapper } = await mountScheduling()

    expect(wrapper.text()).toContain('本次将从零搜索')
    wrapper.unmount()
  })

  it('来源为 BASELINE 时不显示提示', async () => {
    const baselineJob = { ...runningJob, warmStartSource: 'BASELINE' }
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: [baselineJob] })
      if (url === '/scheduling-jobs/1') return Promise.resolve({ data: baselineJob })
      return Promise.resolve({ data: [] })
    })

    const { wrapper } = await mountScheduling()

    expect(wrapper.text()).not.toContain('本次将从零搜索')
    wrapper.unmount()
  })

  it('来源为 DRAFT 时不显示提示', async () => {
    const draftJob = { ...runningJob, warmStartSource: 'DRAFT' }
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: [draftJob] })
      if (url === '/scheduling-jobs/1') return Promise.resolve({ data: draftJob })
      return Promise.resolve({ data: [] })
    })

    const { wrapper } = await mountScheduling()

    expect(wrapper.text()).not.toContain('本次将从零搜索')
    wrapper.unmount()
  })
})
