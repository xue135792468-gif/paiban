import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import SubstitutionConversionPanel from './SubstitutionConversionPanel.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasPermission: (permission: string) => permission === 'substitution:approve',
  }),
}))

const mountedWrappers: VueWrapper[] = []

function statusPayload(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      semesterId: 1,
      legacyRowCount: 1,
      convertibleCount: 1,
      blockedCount: 0,
      converted: false,
      canConfirm: true,
      conversions: [],
      ...overrides,
    },
  }
}

function reportPayload(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      semesterId: 1,
      generatedAt: '2026-10-29T01:00:00Z',
      summary: { totalRows: 2, convertible: 1, blocked: 1, alreadyConverted: 0, changes: 4 },
      items: [
        {
          substitutionId: 11,
          requestId: 5,
          planId: null,
          entryId: 100,
          type: 'COVER',
          status: 'CONVERTIBLE',
          basis: '沿用来源申请 #5 的生效日期 2025-09-01',
          effectiveDate: '2025-09-01',
          lessonDate: '2025-09-01',
          blockerCode: null,
          blockerReason: null,
          className: '高一1班',
          courseName: '语文',
          substituteTeacherName: '代课老师',
          fromPeriodLabel: '周一第1节',
          affectedDates: [
            {
              date: '2025-09-08',
              restoredToBaseline: true,
              before: { substitutionId: 11, teacherId: 22, periodNo: 1, periodLabel: '周一第1节' },
              after: null,
            },
          ],
        },
        {
          substitutionId: 12,
          requestId: 6,
          planId: 8,
          entryId: 101,
          type: 'SWAP',
          status: 'BLOCKED',
          basis: null,
          blockerCode: 'AMBIGUOUS_MOVE',
          blockerReason: '原方案 #8 中条目 101 有 2 条移动，无法唯一解释',
          className: '高一1班',
          courseName: '数学',
          affectedDates: [],
        },
      ],
      ...overrides,
    },
  }
}

async function mountPanel() {
  const wrapper = mount(SubstitutionConversionPanel, { global: { plugins: [ElementPlus] } })
  mountedWrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  httpMock.get.mockReset()
  httpMock.post.mockReset()
  httpMock.get.mockResolvedValue(statusPayload())
})

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})

describe('SubstitutionConversionPanel', () => {
  it('加载转换状态并在未确认时提示旧语义照旧', async () => {
    const wrapper = await mountPanel()
    expect(httpMock.get).toHaveBeenCalledWith('/substitution-conversion/status')
    expect(wrapper.text()).toContain('未通过数据确认门前不会切换历史解释')
    expect(wrapper.text()).toContain('确认记录')
  })

  it('预检报告的汇总数量与逐条数据同源，阻塞行展示原因', async () => {
    httpMock.get.mockImplementation((url: string) =>
      url === '/substitution-conversion/precheck'
        ? Promise.resolve(reportPayload())
        : Promise.resolve(statusPayload()),
    )
    const wrapper = await mountPanel()
    await wrapper.find('button').trigger('click')
    // 第二个按钮是「生成预检报告」
    const buttons = wrapper.findAll('button')
    const precheckButton = buttons.find((button) => button.text().includes('生成预检报告'))
    await precheckButton!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('旧行总数 2')
    expect(text).toContain('可转换 1')
    expect(text).toContain('阻塞 1')
    expect(text).toContain('日期课表差异 4')
    expect(text).toContain('方案移动歧义')
    // 确认按钮因存在阻塞记录而禁用
    const confirmButton = buttons.find((button) => button.text().includes('确认转换'))
    expect(confirmButton).toBeTruthy()
    expect(confirmButton!.classes()).toContain('is-disabled')
  })

  it('无阻塞且已生成报告时确认按钮可用，点击后提交确认并刷新状态', async () => {
    const messageSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)
    httpMock.get.mockImplementation((url: string) =>
      url === '/substitution-conversion/precheck'
        ? Promise.resolve(reportPayload({
            summary: { totalRows: 1, convertible: 1, blocked: 0, alreadyConverted: 0, changes: 4 },
            items: [reportPayload().data.items[0]],
          }))
        : Promise.resolve(statusPayload()),
    )
    httpMock.post.mockResolvedValue({ data: { convertedCount: 1, alreadyConverted: false } })

    const wrapper = await mountPanel()
    const precheckButton = wrapper.findAll('button').find((button) => button.text().includes('生成预检报告'))
    await precheckButton!.trigger('click')
    await flushPromises()

    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认转换'))
    expect(confirmButton).toBeTruthy()
    expect(confirmButton!.classes()).not.toContain('is-disabled')
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/substitution-conversion/confirm', { comment: '' })
    expect(messageSpy).toHaveBeenCalled()
    messageSpy.mockRestore()
  })
})
