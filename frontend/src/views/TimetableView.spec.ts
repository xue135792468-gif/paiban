import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TimetableView from './TimetableView.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

async function mountView(
  permissions: string[] = ['timetable:view', 'timetable:view:class', 'scheduling:run', 'export:timetable'],
  user: { teacherId?: number | null; roles?: string[] } = {},
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    enabled: true,
    teacherId: user.teacherId ?? null,
    roles: user.roles ?? [],
    permissions,
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

describe('TimetableView 老师默认看自己的课表', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/classes') {
        return Promise.resolve({ status: 200, data: [{ id: 3, name: '高一1班' }] })
      }
      if (url === '/teachers/options') {
        return Promise.resolve({
          status: 200,
          data: [
            { id: 7, name: '张老师' },
            { id: 9, name: '李老师' },
          ],
        })
      }
      if (url === '/semesters/current') {
        return Promise.resolve({ status: 200, data: { id: 1, name: '测试学期' } })
      }
      if (url === '/timetable/publish-info') {
        return Promise.resolve({ status: 200, data: { published: true, publishedAt: '2026-01-01' } })
      }
      return Promise.resolve({ status: 200, data: [] })
    })
  })

  it('拿到全校只读权限后仍先落到自己的教师课表，并保留切到全校的入口', async () => {
    const wrapper = await mountView(['timetable:view', 'timetable:view:own', 'substitution:submit'], {
      teacherId: 7,
      roles: ['teacher'],
    })

    const urls = httpMock.get.mock.calls.map((call) => String(call[0]))
    expect(urls).toContain('/timetable/teacher/7')
    expect(urls).not.toContain('/timetable/class/3')
    expect(wrapper.text()).toContain('全校')
    wrapper.unmount()
  })

  it('教务管理员仍然默认进行政班视图', async () => {
    const wrapper = await mountView(
      ['timetable:view', 'timetable:view:class', 'scheduling:run', 'timetable:edit'],
      { teacherId: null, roles: ['admin'] },
    )

    const urls = httpMock.get.mock.calls.map((call) => String(call[0]))
    expect(urls).toContain('/timetable/class/3')
    wrapper.unmount()
  })
})

describe('TimetableView 学校还没有当前学期', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/current') {
        // 引导期允许存在 Teacher 却没有任何当前学期，后端返回 204。
        return Promise.resolve({ status: 204, data: null })
      }
      if (url === '/teachers/options') {
        return Promise.resolve({
          status: 200,
          data: [
            { id: 7, name: '张老师' },
            { id: 9, name: '李老师' },
          ],
        })
      }
      if (url === '/courses') {
        return Promise.resolve({ status: 200, data: [] })
      }
      // 没有当前学期时，任何依赖学期的接口（班级、作息、课表）都会 400。
      return Promise.reject({
        response: { status: 400, data: { status: 400, message: '没有当前学期' } },
      })
    })
  })

  it('老师没有当前学期时显示空态而不是抛错，切全校也不再请求依赖学期的接口', async () => {
    const wrapper = await mountView(['timetable:view', 'timetable:view:own', 'substitution:submit'], {
      teacherId: 7,
      roles: ['teacher'],
    })

    const urlsAfterMount = httpMock.get.mock.calls.map((call) => String(call[0]))
    expect(urlsAfterMount).toContain('/semesters/current')
    expect(urlsAfterMount).toContain('/teachers/options')
    expect(urlsAfterMount).not.toContain('/classes')
    expect(urlsAfterMount.filter((url) => url.startsWith('/timetable/'))).toEqual([])

    expect(wrapper.text()).toContain('学校尚未设置当前学期，请联系教务处')
    expect(wrapper.text()).toContain('张老师')

    const schoolRadio = wrapper
      .findAll('input[type="radio"]')
      .find((input) => (input.element as HTMLInputElement).value === 'school')
    expect(schoolRadio).toBeTruthy()
    await schoolRadio!.setValue(true)
    await flushPromises()

    const urlsAfterSchool = httpMock.get.mock.calls.map((call) => String(call[0]))
    expect(urlsAfterSchool.filter((url) => url.startsWith('/timetable/'))).toEqual([])
    expect(urlsAfterSchool).not.toContain('/classes')
    expect(wrapper.text()).toContain('学校尚未设置当前学期，请联系教务处')

    wrapper.unmount()
  })
})