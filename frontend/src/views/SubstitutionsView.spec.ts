import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SubstitutionsView from './SubstitutionsView.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

const PENDING_ROW = {
  id: 21,
  entryId: 31,
  className: '高一1班',
  courseName: '数学',
  originalTeacherName: '张老师',
  effectiveDate: '2026-11-16',
  periodLabel: '周一第 1 节',
  type: 'COVER',
  reason: '家中有事',
  status: 'SUBMITTED',
  createdAt: '2026-11-11T09:00:00Z',
  expectedSubstituteTeacherId: 2,
  expectedSubstituteTeacherName: '李老师',
}

async function mountView(permissions: string[]): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'u1',
    displayName: '用户',
    enabled: true,
    roles: [],
    permissions,
    teacherId: 1,
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/substitution')
  await router.isReady()
  const wrapper = mount(SubstitutionsView, {
    attachTo: document.body,
    // ElSelect 在 jsdom 的弹层里会递归更新（同 RuleGroupSection），这里只验证
    // 页签、请求参数与刷新行为；真实下拉由 Playwright 覆盖。
    global: {
      plugins: [pinia, router, ElementPlus],
      stubs: {
        ElSelect: {
          name: 'ElSelect',
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<div class="el-select-stub" />',
        },
        ElOption: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('调代课页面', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/current') {
        return Promise.resolve({ status: 200, data: { id: 1, name: '2026 秋季学期' } })
      }
      if (url === '/teachers/options') {
        return Promise.resolve({ data: [{ id: 2, name: '李老师' }] })
      }
      if (url === '/classes') {
        return Promise.resolve({ data: [] })
      }
      if (url === '/substitution-requests') {
        return Promise.resolve({ data: [] })
      }
      return Promise.resolve({ data: [] })
    })
  })

  it('只有代课提交权限的老师看不到待审批入口，也不会去查待批列表', async () => {
    const wrapper = await mountView(['substitution:submit'])

    expect(wrapper.text()).not.toContain('待审批')
    const pendingCalls = httpMock.get.mock.calls.filter(
      ([, config]) => config?.params?.status === 'SUBMITTED',
    )
    expect(pendingCalls).toHaveLength(0)
  })

  it('管理员有待审批页签，按 SUBMITTED 加载，批准后重新拉列表', async () => {
    httpMock.get.mockImplementation((url: string, config?: { params?: Record<string, unknown> }) => {
      if (url === '/semesters/current') {
        return Promise.resolve({ status: 200, data: { id: 1, name: '2026 秋季学期' } })
      }
      if (url === '/teachers/options') {
        return Promise.resolve({ data: [{ id: 2, name: '李老师' }] })
      }
      if (url === '/substitution-requests' && config?.params?.status === 'SUBMITTED') {
        return Promise.resolve({ data: [PENDING_ROW] })
      }
      return Promise.resolve({ data: [] })
    })
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/substitution-requests/21/approve') {
        // 批准后待批列表应为空，页面要重新取数才看得见。
        httpMock.get.mockImplementation((target: string) => {
          if (target === '/semesters/current') {
            return Promise.resolve({ status: 200, data: { id: 1, name: '2026 秋季学期' } })
          }
          if (target === '/teachers/options') {
            return Promise.resolve({ data: [{ id: 2, name: '李老师' }] })
          }
          return Promise.resolve({ data: [] })
        })
        return Promise.resolve({ data: { id: 21, status: 'EFFECTIVE' } })
      }
      return Promise.resolve({ data: {} })
    })

    const wrapper = await mountView(['substitution:submit', 'substitution:approve'])

    const pendingTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes('待审批'))
    expect(pendingTab).toBeTruthy()
    await pendingTab!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('李老师')

    const approve = document.querySelector<HTMLElement>('[data-testid="approve-21"]')
    expect(approve).not.toBeNull()
    approve!.click()
    await flushPromises()
    const confirm = document.querySelector<HTMLElement>('[data-testid="approve-confirm"]')
    confirm!.click()
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/substitution-requests/21/approve', {
      substituteTeacherId: 2,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('没有待审批的申请')
  })

  it('没有当前学期（204）时只显示说明，不请求班级、教师和调代依赖接口', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/current') {
        return Promise.resolve({ status: 204, data: null })
      }
      throw new Error(`unexpected request without semester: ${url}`)
    })

    const wrapper = await mountView(['substitution:submit'])

    expect(wrapper.text()).toContain('当前没有进行中的学期')
    expect(wrapper.find('[data-testid="substitution-no-semester"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('提交调代申请')
    const urls = httpMock.get.mock.calls.map(([url]) => url)
    expect(urls).toEqual(['/semesters/current'])
  })

  it('学期接口的真实错误原样提示，不伪装成没有学期', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters/current') {
        return Promise.reject({
          response: { status: 500, data: { message: '学期服务暂时不可用' } },
        })
      }
      throw new Error(`unexpected request after semester failure: ${url}`)
    })

    const wrapper = await mountView(['substitution:submit'])

    expect(wrapper.text()).toContain('学期服务暂时不可用')
    expect(wrapper.text()).not.toContain('当前没有进行中的学期')
  })

  it('待审批先失败、我的列表后成功时，待批错误不会被清掉', async () => {
    let rejectPending!: (reason: unknown) => void
    const pendingResponse = new Promise((resolve, reject) => {
      void resolve
      rejectPending = reject
    })
    let resolveMine!: (value: unknown) => void
    const mineResponse = new Promise((resolve) => {
      resolveMine = resolve
    })
    httpMock.get.mockImplementation((url: string, config?: { params?: Record<string, unknown> }) => {
      if (url === '/semesters/current') {
        return Promise.resolve({ status: 200, data: { id: 1, name: '2026 秋季学期' } })
      }
      if (url === '/teachers/options') {
        return Promise.resolve({ data: [] })
      }
      if (url === '/classes') {
        return Promise.resolve({ data: [] })
      }
      if (url === '/substitution-requests' && config?.params?.status === 'SUBMITTED') {
        return pendingResponse
      }
      return mineResponse
    })

    const wrapper = await mountView(['substitution:submit', 'substitution:approve'])
    expect(httpMock.get.mock.calls.some(([url]) => url === '/substitution-requests')).toBe(true)

    rejectPending({
      response: { status: 500, data: { message: '待审批列表暂时不可用' } },
    })
    await flushPromises()
    resolveMine({ data: [] })
    await flushPromises()

    expect(wrapper.text()).toContain('待审批列表暂时不可用')
  })
})
