import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import OnboardingView from './OnboardingView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const confirmMock = vi.hoisted(() => vi.fn())
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<Record<string, any>>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: confirmMock } }
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>home</div>' } },
      { path: '/onboarding', component: { template: '<div>wizard</div>' } },
      { path: '/help/:slug', component: { template: '<div>help</div>' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div>other</div>' } },
    ],
  })
}

async function mountWizard(): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = makeRouter()
  await router.push('/onboarding')
  await router.isReady()
  const wrapper = mount(OnboardingView, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text() === label)
  if (!found) throw new Error(`button not found: ${label}`)
  return found
}

describe('OnboardingView progress and help', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) =>
      Promise.resolve({
        data:
          url === '/onboarding'
            ? { currentStep: 0, completedSteps: [], data: {}, skipped: false }
            : { showWizard: true, skipped: false, hasProgress: false, hints: [] },
      }),
    )
    httpMock.put.mockResolvedValue({ data: {} })
  })

  it('shows the current step number against the real step count', async () => {
    const { wrapper } = await mountWizard()

    expect(wrapper.get('[data-testid="wizard-progress"]').text()).toBe('第 1/8 步：学校信息')
  })

  it('links the current step to its help section', async () => {
    const { wrapper } = await mountWizard()

    expect(wrapper.get('a.help-link').attributes('href')).toBe(
      '/help/flow-term-start#school',
    )

    await button(wrapper, '标记完成并继续').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="wizard-progress"]').text()).toBe('第 2/8 步：学期与作息表')
    expect(wrapper.get('a.help-link').attributes('href')).toBe(
      '/help/flow-term-start#periods',
    )
  })
})

describe('OnboardingView skip', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) =>
      Promise.resolve({
        data:
          url === '/onboarding'
            ? { currentStep: 0, completedSteps: [], data: {}, skipped: false }
            : { showWizard: true, skipped: false, hasProgress: false, hints: [] },
      }),
    )
    httpMock.put.mockResolvedValue({ data: {} })
  })

  it('keeps the wizard open when the confirmation is dismissed', async () => {
    confirmMock.mockRejectedValueOnce(new Error('cancel'))
    const { wrapper, router } = await mountWizard()

    await button(wrapper, '跳过向导').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledWith(
      '确定跳过初始化向导吗？您可以随时在设置中重新进入',
      '跳过初始化向导',
      expect.anything(),
    )
    expect(httpMock.put).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/onboarding')
  })

  it('persists the skip only after confirming', async () => {
    confirmMock.mockResolvedValueOnce('confirm')
    const { wrapper, router } = await mountWizard()

    await button(wrapper, '跳过向导').trigger('click')
    await flushPromises()

    expect(httpMock.put).toHaveBeenCalledWith(
      '/onboarding',
      expect.objectContaining({ skipped: true }),
    )
    expect(router.currentRoute.value.path).toBe('/')
  })
})

describe('OnboardingView completion', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) =>
      Promise.resolve({
        data:
          url === '/onboarding'
            ? { currentStep: 0, completedSteps: [], data: {}, skipped: false }
            : { showWizard: true, skipped: false, hasProgress: false, hints: [] },
      }),
    )
    httpMock.put.mockResolvedValue({ data: {} })
  })

  it('celebrates on the same page, then starts on demand', async () => {
    const { wrapper, router } = await mountWizard()

    for (let step = 0; step < 7; step += 1) {
      await button(wrapper, '标记完成并继续').trigger('click')
      await flushPromises()
    }
    await button(wrapper, '完成向导').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('初始化完成！')
    expect(router.currentRoute.value.path).toBe('/onboarding')
    expect(httpMock.put).toHaveBeenLastCalledWith(
      '/onboarding',
      expect.objectContaining({ currentStep: 8, skipped: true }),
    )

    await button(wrapper, '开始使用').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/')
  })
})
