import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import TimetableMappingStep from './TimetableMappingStep.vue'
import type { PreviewIssue, PreviewRow } from './timetablePreview'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

function issue(overrides: Partial<PreviewIssue> = {}): PreviewIssue {
  return {
    row: 1,
    field: 'className',
    value: '一年级(1)班',
    candidates: ['一年级1班'],
    required: true,
    ...overrides,
  }
}

function rows(): PreviewRow[] {
  return [
    {
      values: {
        className: '一年级(1)班',
        dayOfWeek: '周一',
        periodNo: '第九节',
        courseName: '语文',
        teacherName: '张三',
        roomName: '101',
      },
      confidence: {},
      lowConfidence: [],
    },
    {
      values: {
        className: '一年级(1)班',
        dayOfWeek: '周二',
        periodNo: '第2节',
        courseName: '数学',
        teacherName: '李四',
        roomName: '101',
      },
      confidence: {},
      lowConfidence: [],
    },
  ]
}

async function mountStep(
  issues: PreviewIssue[],
  rowsOverride: PreviewRow[] = rows(),
): Promise<VueWrapper> {
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/grades') return Promise.resolve({ data: [{ id: 5, name: '一年级' }] })
    return Promise.resolve({ data: [] })
  })
  const wrapper = mount(TimetableMappingStep, {
    props: { sessionId: 9, rows: rowsOverride, issues },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

function selectByTestId(wrapper: VueWrapper, testId: string) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === testId)
  if (!found) throw new Error(`select not found: ${testId}`)
  return found
}

function previewResponse(issues: PreviewIssue[]) {
  return {
    data: {
      sessionId: 9,
      targetType: 'timetable',
      columns: [],
      rows: rows(),
      mappings: [],
      explanation: '',
      threshold: 0.7,
      status: 'EDITED',
      issues,
      summary: null,
    },
  }
}

describe('课表映射步骤', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('选择系统记录后按字段与原值提交映射', async () => {
    const wrapper = await mountStep([issue()])
    httpMock.get.mockResolvedValue({ data: [{ id: 3, name: '一年级1班', extra: '一年级' }] })
    httpMock.post.mockResolvedValue(previewResponse([]))

    selectByTestId(wrapper, 'resolve-className').vm.$emit('change', '一年级1班')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/mapping', {
      mappings: [{ field: 'className', source: '一年级(1)班', targetId: 3 }],
      creates: [],
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('创建班级必须选年级，选好后显式提交创建', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const wrapper = await mountStep([issue()])
    httpMock.post.mockResolvedValue(previewResponse([]))

    wrapper.findComponent({ name: 'ElCheckbox' }).vm.$emit('update:modelValue', true)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '确认创建')!.trigger('click')
    await flushPromises()
    expect(warning).toHaveBeenCalledWith('创建班级需要先选择年级')
    expect(httpMock.post).not.toHaveBeenCalled()

    const grade = wrapper
      .findAllComponents({ name: 'ElSelect' })
      .find((component) => component.props('placeholder') === '选择年级')
    grade!.vm.$emit('update:modelValue', 5)
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '确认创建')!.trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/mapping', {
      mappings: [],
      creates: [
        { field: 'className', source: '一年级(1)班', name: '一年级(1)班', gradeId: 5 },
      ],
    })
    warning.mockRestore()
  })

  it('节次问题通过行编辑修正，只改相关行', async () => {
    const customRows = rows()
    customRows[1].values.periodNo = '第3节'
    const wrapper = await mountStep(
      [issue({ field: 'periodNo', value: '第九节', candidates: ['第1节', '第2节'] })],
      customRows,
    )
    httpMock.put.mockResolvedValue(previewResponse([]))

    selectByTestId(wrapper, 'resolve-periodNo').vm.$emit('change', '第2节')
    await flushPromises()

    const [url, body] = httpMock.put.mock.calls[0]
    expect(url).toBe('/ai/import/sessions/9/rows')
    expect(body.rows[0].values.periodNo).toBe('第2节')
    expect(body.rows[1].values.periodNo).toBe('第3节')
  })
})
