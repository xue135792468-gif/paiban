import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import AiProvidersView from './AiProvidersView.vue'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))
vi.mock('@/api/http', () => ({ http: httpMock }))

const provider = {
  id: 5,
  name: '测试供应商',
  type: 'custom',
  protocol: 'openai',
  baseUrl: 'https://example.invalid/v1',
  hasApiKey: true,
  maskedApiKey: 'sk-****',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  models: [],
}

beforeEach(() => {
  vi.clearAllMocks()
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/ai-providers') return Promise.resolve({ data: [provider] })
    if (url === '/ai-providers/presets') return Promise.resolve({ data: [] })
    if (url === '/ai-task-config') {
      return Promise.resolve({ data: { mode: 'unified', tasks: [] } })
    }
    return Promise.resolve({ data: [] })
  })
  httpMock.delete.mockResolvedValue({ data: {} })
})

describe('AiProvidersView: delete enters the recycle bin', () => {
  it('confirms with the restore promise and calls the soft-delete endpoint', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mount(AiProvidersView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('[data-testid="ai-provider-delete"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0][0])).toContain('最近删除')
    expect(httpMock.delete).toHaveBeenCalledWith('/ai-providers/5')
    wrapper.unmount()
  })
})
