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

  it('还没有学期时列表给出明确提示，而不是静默失败', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/teachers/options') {
        return Promise.resolve({ data: [] })
      }
      if (url === '/classes') {
        return Promise.resolve({ data: [] })
      }
      return Promise.reject({
        response: { status: 400, data: { message: '当前没有进行中的学期，请先在学期管理中设置' } },
      })
    })

    const wrapper = await mountView(['substitution:submit'])

    expect(wrapper.text()).toContain('当前没有进行中的学期')
  })
})
