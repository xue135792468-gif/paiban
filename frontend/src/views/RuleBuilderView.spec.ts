import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import RuleBuilderView from './RuleBuilderView.vue'
import type { RuleImpact } from '@/api/rules'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(), post: vi.fn(), delete: vi.fn() }))
const impactMock = vi.hoisted(() => ({ fetchRuleImpact: vi.fn() }))

vi.mock('@/api/http', () => ({ http: httpMock }))
vi.mock('@/api/rules', () => impactMock)

const impact: RuleImpact = {
  classes: [{ id: 1, name: '九（1）班' }],
  teachers: [{ id: 7, name: '王老师' }],
  slots: [{ dayOfWeek: 1, periodNo: 1 }],
  lessonCount: 1,
  conflicts: [{ lessonId: 1, type: 'CLASS_UNAVAILABLE', message: '九（1）班 周一第1节 不上课' }],
  errors: [],
  warnings: [],
}

const semesters = [{ id: 1, name: '2025 秋', current: true }]
const teachers = [{ id: 7, name: '王老师' }, { id: 8, name: '李老师' }]
const classes = [{ id: 1, name: '九（1）班' }, { id: 2, name: '九（2）班' }]
const courses = [{ id: 9, name: '数学', active: true }]
const periodRows = [1, 2, 3].flatMap((day) => [1, 2, 3].map((periodNo) => ({
  dayOfWeek: day,
  periodNo,
  section: periodNo <= 2 ? 'MORNING' : 'AFTERNOON',
})))

const stateConfigs = [
  {
    type: 'CLASS_UNAVAILABLE',
    params: {},
    weight: 100,
    hard: true,
    enabled: true,
    entries: [
      { id: 1, params: { dayOfWeek: 2, periodNo: 2 }, enabled: true, hard: true, weight: null, groupKey: 'pt-school' },
      { id: 2, params: { classId: 2, dayOfWeek: 3, periodNo: 1 }, enabled: true, hard: true, weight: null, groupKey: 'pt-class' },
    ],
  },
  {
    type: 'TEACHER_UNAVAILABLE',
    params: {},
    weight: 100,
    hard: true,
    enabled: true,
    entries: [
      { id: 3, params: { teacherId: 7, dayOfWeek: 1, periodNo: 1 }, enabled: true, hard: true, weight: null, groupKey: 'pt-teacher' },
    ],
  },
  {
    type: 'COURSE_MAX_PER_DAY',
    params: {},
    weight: 50,
    hard: false,
    enabled: true,
    entries: [
      { id: 4, params: { courseId: 9, maxPerDay: 1 }, enabled: true, hard: null, weight: null, groupKey: 'pt-course' },
    ],
  },
  { type: 'TEACHER_CONFLICT', params: {}, weight: 100, hard: true, enabled: true, entries: [] },
]

beforeEach(() => {
  httpMock.get.mockReset()
  httpMock.put.mockReset()
  httpMock.post.mockReset()
  httpMock.delete.mockReset()
  impactMock.fetchRuleImpact.mockReset()
  impactMock.fetchRuleImpact.mockResolvedValue(impact)
  httpMock.put.mockResolvedValue({ data: [] })
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/semesters') return Promise.resolve({ status: 200, data: semesters })
    if (url === '/semesters/current') return Promise.resolve({ status: 200, data: semesters[0] })
    if (url === '/teachers/options') return Promise.resolve({ data: teachers })
    if (url === '/courses') return Promise.resolve({ data: courses })
    if (url === '/constraint-types') return Promise.resolve({ data: [] })
    if (url === '/classes') return Promise.resolve({ data: classes })
    if (url === '/constraints/state') {
      return Promise.resolve({ data: { version: 3, configs: stateConfigs } })
    }
    if (url === '/semesters/1/periods') return Promise.resolve({ data: periodRows })
    return Promise.resolve({ data: [] })
  })
})

async function mountView(): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/rules', component: { template: '<div />' } },
      { path: '/rules/advanced', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/rules')
  await router.isReady()
  const wrapper = mount(RuleBuilderView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('RuleBuilderView: grouped list, search and impact', () => {
  it('groups cards by 对谁 and keeps the other-type note', async () => {
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="rule-group-SCHOOL"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-group-CLASS"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-group-TEACHER"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-group-COURSE"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="rule-card"]')).toHaveLength(4)
    expect(wrapper.find('[data-testid="rule-other-note"]').text()).toContain('1 条其它类型规则')
    wrapper.unmount()
  })

  it('filters by teacher name and shows an empty search state', async () => {
    const wrapper = await mountView()
    const search = wrapper.find('[data-testid="rule-search"]')
    await search.setValue('王老师')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="rule-card"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="rule-group-TEACHER"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-group-SCHOOL"]').exists()).toBe(false)

    await search.setValue('不存在的规则')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="rule-card"]')).toHaveLength(0)
    expect(wrapper.find('[data-testid="rule-search-empty"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('calls the impact endpoint when previewing a card', async () => {
    const wrapper = await mountView()
    await wrapper.findAll('[data-testid="rule-impact"]')[0].trigger('click')
    await flushPromises()
    expect(impactMock.fetchRuleImpact).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="rule-impact-summary"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-impact-counts"]').text()).toContain('影响 1 个班')
    expect(wrapper.find('[data-testid="rule-impact-conflicts"]').text()).toContain('周一第1节')
    wrapper.unmount()
  })

  it('previews before saving and then PUTs the merged configuration', async () => {
    const wrapper = await mountView()
    await wrapper.findAll('[data-testid="rule-toggle"]')[0].trigger('click')
    await flushPromises()
    const save = wrapper.find('[data-testid="rule-save"]')
    expect(save.attributes('disabled')).toBeUndefined()
    await save.trigger('click')
    await flushPromises()
    expect(impactMock.fetchRuleImpact).toHaveBeenCalledTimes(1)
    expect(httpMock.put).toHaveBeenCalledTimes(1)
    const payload = httpMock.put.mock.calls[0][1] as { configs: unknown[] }
    expect(payload.configs.length).toBeGreaterThan(0)
    wrapper.unmount()
  })

  it('shows the impact before deleting and removes the card on confirm', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountView()
    await wrapper.findAll('[data-testid="rule-delete"]')[0].trigger('click')
    await flushPromises()
    expect(impactMock.fetchRuleImpact).toHaveBeenCalledTimes(1)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0][0])).toContain('影响 1 个班')
    // This fixture carries no config ids, so the card was never saved and is only
    // dropped from the page; the saved path is covered by the next test.
    expect(String(confirm.mock.calls[0][0])).toContain('还没有保存')
    expect(wrapper.findAll('[data-testid="rule-card"]')).toHaveLength(3)
    wrapper.unmount()
  })

  it('deletes a saved card through the recycle-bin endpoint and reloads the state', async () => {
    let configs = stateConfigs.map((config, index) => ({ ...config, id: index + 1 }))
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ status: 200, data: semesters })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: semesters[0] })
      if (url === '/teachers/options') return Promise.resolve({ data: teachers })
      if (url === '/courses') return Promise.resolve({ data: courses })
      if (url === '/constraint-types') return Promise.resolve({ data: [] })
      if (url === '/classes') return Promise.resolve({ data: classes })
      if (url === '/constraints/state') {
        return Promise.resolve({ data: { version: 4, configs } })
      }
      if (url === '/semesters/1/periods') return Promise.resolve({ data: periodRows })
      return Promise.resolve({ data: [] })
    })
    httpMock.delete.mockImplementation(() => {
      configs = configs.filter((config) => config.type !== 'TEACHER_UNAVAILABLE')
      return Promise.resolve({ data: {} })
    })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountView()

    const teacherCard = wrapper
      .findAll('[data-testid="rule-card"]')
      .find((card) => card.text().includes('王老师'))
    expect(teacherCard).toBeTruthy()
    await teacherCard!.find('[data-testid="rule-delete"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(String(confirm.mock.calls[0][0])).toContain('最近删除')
    expect(httpMock.delete).toHaveBeenCalledWith('/rules/2', {
      params: { cardKey: 'pt-teacher' },
    })
    expect(wrapper.findAll('[data-testid="rule-card"]')).toHaveLength(3)
    wrapper.unmount()
  })

  it('blocks the save when the impact reports hard errors', async () => {
    impactMock.fetchRuleImpact.mockResolvedValue({ ...impact, errors: ['课程参数缺失'] })
    const wrapper = await mountView()
    await wrapper.findAll('[data-testid="rule-toggle"]')[0].trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="rule-save"]').trigger('click')
    await flushPromises()
    expect(httpMock.put).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="rule-impact-errors"]').text()).toContain('课程参数缺失')
    wrapper.unmount()
  })
})
