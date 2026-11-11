import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from './LoginView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const originalUserAgent = navigator.userAgent

function setUserAgent(ua: string) {
  Object.defineProperty(window.navigator, 'userAgent', {
    value: ua,
    configurable: true,
  })
}

function installFakeDingTalk(authCode = 'fake-auth-code') {
  ;(window as any).dd = {
    ready: (cb: () => void) => cb(),
    runtime: {
      permission: {
        requestAuthCode: ({ onSuccess }: { onSuccess: (res: { code: string }) => void }) => {
          onSuccess({ code: authCode })
        },
      },
    },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const ADMIN_USER = {
  id: 1,
  username: 'admin',
  displayName: '超管老师',
  enabled: true,
  loginType: 'PASSWORD' as const,
  roles: ['super_admin'],
  permissions: ['onboarding:manage'],
}
async function mountLogin(initialPath = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/', name: 'home', component: { template: '<div data-testid="home-page">首页</div>' } },
      {
        path: '/onboarding',
        name: 'onboarding',
        component: { template: '<div data-testid="onboarding-page">向导</div>' },
      },
    ],
  })
  await router.push(initialPath)
  await router.isReady()
  const wrapper = mount(LoginView, {
    global: {
      plugins: [ElementPlus, createPinia(), router],
    },
  })
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  delete (window as any).dd
  setUserAgent('Mozilla/5.0 DingTalk/7.5.0')
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/auth/dingtalk/config') {
      return Promise.resolve({ data: { enabled: true, corpId: 'ding-corp-1' } })
    }
    if (url === '/onboarding/status') {
      return Promise.resolve({ data: { showWizard: false } })
    }
    return Promise.resolve({ data: {} })
  })
})

afterEach(() => {
  vi.useRealTimers()
  setUserAgent(originalUserAgent)
  delete (window as any).dd
})

describe('LoginView: 钉钉免登原因码说明页与管理员密码入口', () => {
  it.each([
    {
      code: 'NOT_PROVISIONED',
      expectedTitle: '你还没有开通',
      expectedDetail: '请联系教务处',
    },
    {
      code: 'AMBIGUOUS_IDENTITY',
      expectedTitle: '系统无法确认你是哪位老师',
      expectedDetail: '请联系教务处',
    },
    {
      code: 'ACCOUNT_DISABLED',
      expectedTitle: '账号已停用',
      expectedDetail: '超级管理员',
    },
    {
      code: 'DINGTALK_DISABLED',
      expectedTitle: '钉钉免登未启用',
      expectedDetail: '超级管理员',
    },
    {
      code: 'DINGTALK_CALL_FAILED',
      expectedTitle: '钉钉登录暂时无法完成',
      expectedDetail: '超级管理员',
    },
    {
      code: 'ADMIN_BINDING_REQUIRED',
      expectedTitle: '需超级管理员确认绑定',
      expectedDetail: '教师管理',
    },
  ])('按稳定原因码 $code 展示友好说明，不显示密码输入框或原始供应商异常', async ({ code, expectedTitle, expectedDetail }) => {
    installFakeDingTalk()
    httpMock.post.mockRejectedValueOnce({
      response: {
        status: 403,
        data: {
          status: 403,
          code,
          message: 'raw vendor exception: https://oapi.dingtalk.com/topapi?access_token=secret123',
        },
      },
    })

    const { wrapper } = await mountLogin()

    const notice = wrapper.find('[data-testid="dingtalk-login-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain(expectedTitle)
    expect(notice.text()).toContain(expectedDetail)
    if (code === 'ADMIN_BINDING_REQUIRED') {
      expect(notice.text()).not.toContain('钉钉同步')
    }
    expect(notice.text()).not.toContain('access_token=')
    expect(notice.text()).not.toContain('secret123')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="switch-password-login"]').text()).toContain('我是管理员，用密码登录')
    wrapper.unmount()
  })

  it('钉钉未启用、缺少 CorpId 或 SDK 获取免登码失败时同样显示可读说明，不静默退回密码表单', async () => {
    httpMock.get.mockResolvedValueOnce({ data: { enabled: false, corpId: '' } })

    const { wrapper } = await mountLogin()

    const notice = wrapper.find('[data-testid="dingtalk-login-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('钉钉免登未启用')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('SDK requestAuthCode 报错或 dd.ready 超时时显示友好说明而非静默回退密码页', async () => {
    ;(window as any).dd = {
      ready: () => {
        // Simulate dd.ready hanging forever
      },
    }
    vi.useFakeTimers()

    const { wrapper } = await mountLogin()
    await vi.advanceTimersByTimeAsync(6000)
    await flushPromises()

    const notice = wrapper.find('[data-testid="dingtalk-login-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('钉钉登录暂时无法完成')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('钉钉 JSAPI 脚本加载挂起超时（onload/onerror 均不触发）时显示友好说明与密码入口', async () => {
    delete (window as any).dd
    vi.useFakeTimers()

    const { wrapper } = await mountLogin()
    await vi.advanceTimersByTimeAsync(11000)
    await flushPromises()

    const notice = wrapper.find('[data-testid="dingtalk-login-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.attributes('data-reason-code')).toBe('DINGTALK_CALL_FAILED')
    expect(notice.text()).toContain('钉钉登录暂时无法完成')
    expect(wrapper.find('[data-testid="switch-password-login"]').text()).toContain('我是管理员，用密码登录')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('requestAuthCode 永不回调超时后显示友好说明与密码入口', async () => {
    ;(window as any).dd = {
      ready: (cb: () => void) => cb(),
      runtime: {
        permission: {
          requestAuthCode: () => {
            // Simulate requestAuthCode hanging forever
          },
        },
      },
    }
    vi.useFakeTimers()

    const { wrapper } = await mountLogin()
    await vi.advanceTimersByTimeAsync(11000)
    await flushPromises()

    const notice = wrapper.find('[data-testid="dingtalk-login-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.attributes('data-reason-code')).toBe('DINGTALK_CALL_FAILED')
    expect(notice.text()).toContain('钉钉登录暂时无法完成')
    expect(wrapper.find('[data-testid="switch-password-login"]').text()).toContain('我是管理员，用密码登录')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('钉钉免登响应延迟时切到密码入口，晚到的免登响应不得写入会话或跳转', async () => {
    installFakeDingTalk('code-late')
    const late = deferred<{ data: unknown }>()
    httpMock.post.mockReturnValueOnce(late.promise)

    const { wrapper, router } = await mountLogin('/login')
    // 免登进行中：明确展示等待态，且此时不暴露密码表单
    expect(wrapper.find('[data-testid="dingtalk-login-pending"]').exists()).toBe(true)
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)

    await wrapper.find('[data-testid="switch-password-login"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.mode).toBe('password')
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)

    late.resolve({
      data: { token: 'late-DingTalk-token', user: { ...ADMIN_USER, loginType: 'DINGTALK' } },
    })
    await flushPromises()

    expect(localStorage.getItem('paiban_token')).toBeNull()
    expect(localStorage.getItem('paiban_user')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.find('[data-testid="dingtalk-login-notice"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('组件卸载后晚到的钉钉免登响应不得写入会话或跳转', async () => {
    installFakeDingTalk('code-late-unmount')
    const late = deferred<{ data: unknown }>()
    httpMock.post.mockReturnValueOnce(late.promise)

    const { wrapper, router } = await mountLogin('/login')
    expect(wrapper.find('[data-testid="dingtalk-login-pending"]').exists()).toBe(true)

    const pushes = vi.spyOn(router, 'push')
    const replaces = vi.spyOn(router, 'replace')
    wrapper.unmount()

    late.resolve({
      data: { token: 'late-DingTalk-token-after-unmount', user: { ...ADMIN_USER, loginType: 'DINGTALK' } },
    })
    await flushPromises()

    // 卸载后迟到的钉钉响应：不写会话、不触发任何跳转
    expect(localStorage.getItem('paiban_token')).toBeNull()
    expect(localStorage.getItem('paiban_user')).toBeNull()
    expect(pushes).not.toHaveBeenCalled()
    expect(replaces).not.toHaveBeenCalled()
  })
  it('密码登录完成后，晚到的钉钉免登响应不得覆盖密码会话', async () => {
    installFakeDingTalk('code-late-override')
    const late = deferred<{ data: unknown }>()
    httpMock.post.mockReturnValueOnce(late.promise)

    const { wrapper } = await mountLogin('/login')
    await wrapper.find('[data-testid="switch-password-login"]').trigger('click')
    await flushPromises()

    httpMock.post.mockResolvedValueOnce({ data: { token: 'password-token', user: ADMIN_USER } })
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('admin')
    await inputs[1].setValue('secret-pass')
    await wrapper.find('button.submit').trigger('click')
    await flushPromises()

    expect(localStorage.getItem('paiban_token')).toBe('password-token')

    late.resolve({
      data: { token: 'late-DingTalk-token', user: { ...ADMIN_USER, loginType: 'DINGTALK' } },
    })
    await flushPromises()

    expect(localStorage.getItem('paiban_token')).toBe('password-token')
    expect(localStorage.getItem('paiban_user')).toContain('超管老师')
    wrapper.unmount()
  })
  it('钉钉免登已拿到会话但落地检查未返回时切到密码入口，旧落地不得把用户带离密码页', async () => {
    installFakeDingTalk('code-landing')
    httpMock.post.mockResolvedValueOnce({
      data: { token: 'dingtalk-session-token', user: { ...ADMIN_USER, loginType: 'DINGTALK' } },
    })
    const status = deferred<{ data: { showWizard: boolean } }>()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/auth/dingtalk/config') {
        return Promise.resolve({ data: { enabled: true, corpId: 'ding-corp-1' } })
      }
      if (url === '/onboarding/status') {
        return status.promise
      }
      return Promise.resolve({ data: {} })
    })

    const { wrapper, router } = await mountLogin('/login')
    expect(localStorage.getItem('paiban_token')).toBe('dingtalk-session-token')

    await wrapper.find('[data-testid="switch-password-login"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.mode).toBe('password')

    // 旧的落地检查此时才返回「要进向导」：不得再导航
    status.resolve({ data: { showWizard: true } })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.mode).toBe('password')
    wrapper.unmount()
  })
  it('点击「我是管理员，用密码登录」写入 ?mode=password 并显示密码表单；带 ?mode=password 刷新不触发自动免登循环', async () => {
    installFakeDingTalk()
    httpMock.post.mockRejectedValueOnce({
      response: { status: 403, data: { code: 'NOT_PROVISIONED', message: '未开通' } },
    })

    const { wrapper, router } = await mountLogin('/login')
    expect(wrapper.find('[data-testid="dingtalk-login-notice"]').exists()).toBe(true)

    await wrapper.find('[data-testid="switch-password-login"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.mode).toBe('password')
    expect(wrapper.find('[data-testid="dingtalk-login-notice"]').exists()).toBe(false)
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    wrapper.unmount()

    // 模拟在钉钉内刷新 /login?mode=password：直接显示密码表单，不显示说明页
    const refreshed = await mountLogin('/login?mode=password')
    expect(refreshed.wrapper.find('[data-testid="dingtalk-login-notice"]').exists()).toBe(false)
    expect(refreshed.wrapper.find('input[type="password"]').exists()).toBe(true)
    refreshed.wrapper.unmount()
  })

  it('普通浏览器直接展示原密码登录表单', async () => {
    setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0')
    const { wrapper } = await mountLogin('/login')

    expect(wrapper.find('[data-testid="dingtalk-login-notice"]').exists()).toBe(false)
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('免登成功后复用 useLoginLanding 落地规则（首次运行且有 onboarding:manage 进向导）', async () => {
    installFakeDingTalk('code-admin')
    httpMock.post.mockResolvedValueOnce({
      data: {
        token: 'jwt-dingtalk',
        user: {
          id: 9,
          username: 'dd_admin',
          displayName: '超管老师',
          enabled: true,
          loginType: 'DINGTALK',
          roles: ['super_admin'],
          permissions: ['onboarding:manage'],
        },
      },
    })
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/auth/dingtalk/config') {
        return Promise.resolve({ data: { enabled: true, corpId: 'ding-corp-1' } })
      }
      if (url === '/onboarding/status') {
        return Promise.resolve({ data: { showWizard: true } })
      }
      return Promise.resolve({ data: {} })
    })

    const { wrapper, router } = await mountLogin('/login')

    expect(router.currentRoute.value.path).toBe('/onboarding')
    wrapper.unmount()
  })
})
