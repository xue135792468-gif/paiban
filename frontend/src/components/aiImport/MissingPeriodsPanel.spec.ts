import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import MissingPeriodsPanel from './MissingPeriodsPanel.vue'
import type { MissingPeriodsPlan } from './missingPeriods'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

function plan(overrides: Partial<MissingPeriodsPlan> = {}): MissingPeriodsPlan {
  return {
    semesterId: 3,
    semesterName: '2026 秋',
    published: false,
    items: [
      {
        dayOfWeek: 1,
        periodNo: 9,
        status: 'PROPOSED',
        label: '第9节',
        section: 'AFTERNOON',
        startTime: '17:40:00',
        endTime: '18:25:00',
        reason: null,
      },
      {
        dayOfWeek: 2,
        periodNo: 9,
        status: 'PROPOSED',
        label: '第9节',
        section: 'AFTERNOON',
        startTime: '17:40:00',
        endTime: '18:25:00',
        reason: null,
      },
      {
        dayOfWeek: 6,
        periodNo: 9,
        status: 'MANUAL',
        label: '第9节',
        section: null,
        startTime: null,
        endTime: null,
        reason: '没有可参照的作息时间，请到作息管理手工设置',
      },
    ],
    ...overrides,
  }
}

async function mountPanel() {
  const wrapper = mount(MissingPeriodsPanel, {
    props: { sessionId: 7 },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
  httpMock.get.mockResolvedValue({ data: plan() })
  httpMock.post.mockResolvedValue({ data: { created: 2, plan: plan({ items: [] }) } })
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
})

describe('MissingPeriodsPanel', () => {
  it('列出可推算项的时间与需要人工的原因', async () => {
    const wrapper = await mountPanel()

    expect(httpMock.get).toHaveBeenCalledWith('/ai/import/sessions/7/missing-periods')
    const table = wrapper.get('[data-testid=missing-periods-table]').text()
    expect(table).toContain('周一')
    expect(table).toContain('第9节')
    expect(table).toContain('下午')
    expect(table).toContain('17:40–18:25')
    expect(table).toContain('可自动补')
    expect(table).toContain('需人工')
    expect(table).toContain('没有可参照的作息时间')
    expect(wrapper.findAll('[data-testid=missing-periods-manage]')).toHaveLength(1)
  })

  it('默认勾选全部可推算项，人工项没有勾选框', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.get('[data-testid=missing-periods-apply]').text()).toContain('补齐选中的 2 节')
    expect(wrapper.find('[data-testid=missing-period-check-6-9]').exists()).toBe(false)
    const first = wrapper.get('[data-testid=missing-period-check-1-9]')
    expect((first.find('input').element as HTMLInputElement).checked).toBe(true)
  })

  it('确认后提交学期与时间，刷新差集并通知页面', async () => {
    const wrapper = await mountPanel()

    await wrapper.get('[data-testid=missing-periods-apply]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/7/missing-periods', {
      semesterId: 3,
      items: [
        { dayOfWeek: 1, periodNo: 9, startTime: '17:40:00', endTime: '18:25:00' },
        { dayOfWeek: 2, periodNo: 9, startTime: '17:40:00', endTime: '18:25:00' },
      ],
    })
    expect(wrapper.emitted('applied')).toHaveLength(1)
    // The refreshed plan is empty, so the panel disappears instead of claiming success.
    expect(wrapper.find('[data-testid=missing-periods]').exists()).toBe(false)
  })

  it('已发布学期只说明不可补，不给勾选和补齐入口', async () => {
    httpMock.get.mockResolvedValue({ data: plan({ published: true }) })
    const wrapper = await mountPanel()

    expect(wrapper.get('[data-testid=missing-periods-published]').text()).toContain('已发布课表')
    expect(wrapper.find('[data-testid=missing-periods-apply]').exists()).toBe(false)
    expect(wrapper.find('[data-testid=missing-periods-manage]').exists()).toBe(false)
  })

  it('人工项给出去作息管理的入口', async () => {
    const wrapper = await mountPanel()

    await wrapper.get('[data-testid=missing-periods-manage]').trigger('click')

    expect(push).toHaveBeenCalledWith('/admin/semesters')
  })
})
