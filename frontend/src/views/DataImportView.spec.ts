import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import DataImportView from './DataImportView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

async function mountImport(path = '/data/import', configured = false) {
  httpMock.get.mockResolvedValue({
    data: { tasks: { data_import: { configured } } },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/data/import', component: DataImportView },
      { path: '/data/import/manual', component: DataImportView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(DataImportView, {
    global: {
      plugins: [router, ElementPlus],
      stubs: {
        ManualImportView: { template: '<div data-testid="manual-pane" />' },
        AiDataImportView: { template: '<div data-testid="ai-pane" />' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('DataImportView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('默认进入零 AI 依赖的手动选列', async () => {
    const { wrapper } = await mountImport()
    expect(wrapper.find('[data-testid="manual-pane"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="ai-pane"]').exists()).toBe(false)
  })

  it('mode=ai 深链自动进入 AI 模式', async () => {
    const { wrapper } = await mountImport('/data/import?mode=ai', true)
    expect(wrapper.find('[data-testid="ai-pane"]').exists()).toBe(true)
  })

  it('未配置 AI 时只给引导，不加载目标卡片', async () => {
    const { wrapper } = await mountImport('/data/import?target=teacher', false)
    expect(wrapper.find('[data-testid="ai-pane"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="ai-not-configured"]').text()).toContain('去 AI 助手配置')
    expect(wrapper.get('[data-testid="ai-not-configured"]').text()).toContain('返回手动导入')
  })

  it('旧手动入口直接进入手动模式', async () => {
    const { wrapper } = await mountImport('/data/import/manual', true)
    expect(wrapper.find('[data-testid="manual-pane"]').exists()).toBe(true)
  })

  it('显式 manual 优先于遗留会话参数，刷新后仍停在手动', async () => {
    const { wrapper } = await mountImport('/data/import?mode=manual&session=9', true)
    expect(wrapper.find('[data-testid="manual-pane"]').exists()).toBe(true)
  })

  it('手动模式顶部提示未完成的 AI 会话', async () => {
    localStorage.setItem(
      'paiban.aiImport.lastSession',
      JSON.stringify({
        id: 9,
        fileName: '课表.xlsx',
        status: 'PREVIEW',
      }),
    )
    const { wrapper } = await mountImport('/data/import', true)
    expect(wrapper.get('[data-testid="pending-ai-session"]').text()).toContain('课表.xlsx')
  })
})
