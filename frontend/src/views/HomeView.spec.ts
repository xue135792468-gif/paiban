import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeView from './HomeView.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))

async function mountHome(permissions: string[]): Promise<VueWrapper> {
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
  await router.push('/')
  await router.isReady()
  const wrapper = mount(HomeView, {
    global: { plugins: [pinia, router, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

function substitutionCard(wrapper: VueWrapper) {
  return wrapper.findAll('.quick-card').find((card) => card.text().includes('调代'))
}

describe('首页调代入口文案', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockResolvedValue({ data: { status: 'UP' } })
  })

  it('老师的调代卡片说明提交后等待教务审批', async () => {
    const wrapper = await mountHome(['substitution:submit'])

    const card = substitutionCard(wrapper)
    expect(card).toBeTruthy()
    expect(card!.text()).toContain('提交代课申请')
    expect(card!.text()).toContain('等待教务审批')
  })

  it('有审批权限的管理员看到待审批与工作台直接调整的区分', async () => {
    const wrapper = await mountHome(['substitution:submit', 'substitution:approve'])

    const card = substitutionCard(wrapper)
    expect(card).toBeTruthy()
    expect(card!.text()).toContain('待审批')
    expect(card!.text()).toContain('工作台')
  })
})
