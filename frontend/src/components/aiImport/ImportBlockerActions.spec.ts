import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ImportBlockerActions from './ImportBlockerActions.vue'
import type { PreviewIssue } from './timetablePreview'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

function blocker(overrides: Partial<PreviewIssue> = {}): PreviewIssue {
  return {
    row: 0,
    field: 'semester',
    value: '',
    candidates: [],
    required: true,
    message: '请先设置当前学期，再确认入库',
    ...overrides,
  }
}

function mountBlockers(issues: PreviewIssue[]) {
  return mount(ImportBlockerActions, {
    props: { issues },
    global: { plugins: [ElementPlus] },
  })
}

describe('ImportBlockerActions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockResolvedValue({ data: { id: 7 } })
    httpMock.post.mockResolvedValue({ data: [] })
  })

  it('缺学期时给出跳转入口', async () => {
    const wrapper = mountBlockers([blocker()])

    await wrapper.get('[data-testid=go-semesters]').trigger('click')

    expect(push).toHaveBeenCalledWith('/admin/semesters')
    expect(wrapper.find('[data-testid=generate-periods]').exists()).toBe(false)
  })

  it('缺课节时一键生成默认作息并刷新预览', async () => {
    const wrapper = mountBlockers([
      blocker({ field: 'periods', message: '当前学期还没有课节，请先生成默认作息' }),
    ])

    await wrapper.get('[data-testid=generate-periods]').trigger('click')
    await flushPromises()

    expect(httpMock.get).toHaveBeenCalledWith('/semesters/current')
    expect(httpMock.post).toHaveBeenCalledWith('/semesters/7/periods/default', {
      morningCount: 4,
      afternoonCount: 4,
      eveningCount: 0,
      days: [1, 2, 3, 4, 5],
    })
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })

  it('其他行级阻塞只展示原因，不给按钮', () => {
    const wrapper = mountBlockers([
      blocker({ field: 'className', message: '工作表「数据源」第 3 行：班级不能为空' }),
    ])

    expect(wrapper.text()).toContain('班级不能为空')
    expect(wrapper.find('[data-testid=go-semesters]').exists()).toBe(false)
    expect(wrapper.find('[data-testid=generate-periods]').exists()).toBe(false)
  })
})
