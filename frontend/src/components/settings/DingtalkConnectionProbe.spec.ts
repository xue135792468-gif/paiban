import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DingtalkConnectionProbe from './DingtalkConnectionProbe.vue'

const httpMock = vi.hoisted(() => ({
  post: vi.fn(),
}))

vi.mock('@/api/http', () => ({ http: httpMock }))

function mountProbe(overrides: Record<string, unknown> = {}) {
  return mount(DingtalkConnectionProbe, {
    props: {
      appKey: '',
      appSecret: '',
      hasSecret: false,
      savedAppKey: '',
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('DingtalkConnectionProbe', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses the current unsaved AppKey and AppSecret', async () => {
    httpMock.post.mockResolvedValue({
      data: {
        success: true,
        code: 'OK',
        message: '连接测试成功：本次凭据可获取访问令牌并读取根部门',
      },
    })
    const wrapper = mountProbe()

    await wrapper.get('[data-testid="dingtalk-probe-app-key"]').setValue('new-key')
    await wrapper.get('[data-testid="dingtalk-probe-app-secret"]').setValue('new-secret')
    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/integrations/dingtalk/test-connection', {
      appKey: 'new-key',
      appSecret: 'new-secret',
      useSavedSecret: false,
    })
    const result = wrapper.get('[data-testid="dingtalk-probe-result"]')
    expect(result.text()).toContain('访问令牌')
    expect(result.text()).toContain('根部门')
    expect(result.text()).not.toContain('通知')
    expect(result.text()).toContain('本次测试不会保存配置')
    wrapper.unmount()
  })

  it('does not let a success response claim unrelated integrations were verified', async () => {
    httpMock.post.mockResolvedValue({
      data: {
        success: true,
        code: 'OK',
        message: '通知、手机号、CorpId、AgentId 均已验证',
      },
    })
    const wrapper = mountProbe({ appKey: 'key', appSecret: 'secret' })

    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()

    const result = wrapper.get('[data-testid="dingtalk-probe-result"]')
    expect(result.text()).toContain('访问令牌')
    expect(result.text()).toContain('根部门')
    for (const unrelated of ['通知', '手机', 'CorpId', 'AgentId']) {
      expect(result.text()).not.toContain(unrelated)
    }
    wrapper.unmount()
  })

  it('reuses the saved secret only when the saved AppKey is unchanged', async () => {
    httpMock.post.mockResolvedValue({
      data: { success: true, code: 'OK', message: '测试成功' },
    })
    const wrapper = mountProbe({
      appKey: 'saved-key',
      appSecret: '',
      hasSecret: true,
      savedAppKey: 'saved-key',
    })

    expect(wrapper.text()).toContain('留空将复用已保存的 AppSecret')
    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/integrations/dingtalk/test-connection', {
      appKey: 'saved-key',
      appSecret: '',
      useSavedSecret: true,
    })
    wrapper.unmount()
  })

  it('requires a new secret after the AppKey changes', async () => {
    const wrapper = mountProbe({
      appKey: 'changed-key',
      appSecret: '',
      hasSecret: true,
      savedAppKey: 'saved-key',
    })

    const probe = wrapper.get('[data-testid="dingtalk-probe"]')
    expect(wrapper.text()).toContain('请为新 AppKey 输入对应的 AppSecret')
    expect(wrapper.get('[data-testid="dingtalk-probe-app-secret"]').attributes('placeholder')).toBe(
      '请输入与当前 AppKey 配套的 AppSecret',
    )
    expect(probe.attributes('disabled')).toBeDefined()
    await probe.trigger('click')
    await flushPromises()

    expect(httpMock.post).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="dingtalk-probe-app-key"]').setValue('')
    expect(wrapper.text()).not.toContain('请为新 AppKey 输入对应的 AppSecret')
    wrapper.unmount()
  })

  it('shows a fixed safe message instead of the rejected Axios body', async () => {
    httpMock.post.mockRejectedValue({
      response: {
        status: 500,
        data: { message: 'raw provider body appSecret=do-not-render' },
      },
    })
    const wrapper = mountProbe({ appKey: 'key', appSecret: 'secret' })

    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()

    const result = wrapper.get('[data-testid="dingtalk-probe-result"]')
    expect(result.text()).toContain('测试连接未能完成，请稍后重试')
    expect(result.text()).not.toContain('raw provider body')
    expect(result.text()).not.toContain('secret')
    wrapper.unmount()
  })

  it('shows the server-provided safe Chinese reason for a tested vendor failure', async () => {
    httpMock.post.mockResolvedValue({
      data: {
        success: false,
        code: 'TOKEN_REJECTED',
        message: '未通过访问令牌校验，请检查 AppKey 和 AppSecret',
      },
    })
    const wrapper = mountProbe({ appKey: 'key', appSecret: 'secret' })

    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="dingtalk-probe-result"]').text()).toBe(
      '未通过访问令牌校验，请检查 AppKey 和 AppSecret',
    )
    wrapper.unmount()
  })

  it('clears a previous result when either current credential changes', async () => {
    httpMock.post.mockResolvedValue({
      data: { success: true, code: 'OK', message: '旧参数测试成功' },
    })
    const wrapper = mountProbe({ appKey: 'key', appSecret: 'secret' })

    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="dingtalk-probe-result"]').exists()).toBe(true)

    await wrapper.get('[data-testid="dingtalk-probe-app-key"]').setValue('changed-key')
    expect(wrapper.find('[data-testid="dingtalk-probe-result"]').exists()).toBe(false)

    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="dingtalk-probe-app-secret"]').setValue('changed-secret')
    expect(wrapper.find('[data-testid="dingtalk-probe-result"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('ignores a late response for credentials that changed while the request was pending', async () => {
    let resolveProbe: ((value: unknown) => void) | undefined
    httpMock.post.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveProbe = resolve
        }),
    )
    const wrapper = mountProbe({ appKey: 'old-key', appSecret: 'old-secret' })

    await wrapper.get('[data-testid="dingtalk-probe"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="dingtalk-probe-app-key"]').setValue('new-key')
    resolveProbe?.({
      data: { success: true, code: 'OK', message: '旧参数测试成功' },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="dingtalk-probe-result"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="dingtalk-probe"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('uses an independent busy state and prevents duplicate probes', async () => {
    let resolveProbe: ((value: unknown) => void) | undefined
    httpMock.post.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveProbe = resolve
        }),
    )
    const wrapper = mountProbe({ appKey: 'key', appSecret: 'secret' })
    const probe = wrapper.get('[data-testid="dingtalk-probe"]')

    await probe.trigger('click')
    await probe.trigger('click')

    expect(httpMock.post).toHaveBeenCalledTimes(1)
    expect(probe.text()).toContain('正在测试连接')
    resolveProbe?.({
      data: { success: true, code: 'OK', message: '测试成功' },
    })
    await flushPromises()
    expect(probe.text()).toContain('测试连接')
    wrapper.unmount()
  })
})
