import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import TimetableMappingStep from './TimetableMappingStep.vue'
import { mappingGroups, type MappingGroup, type PreviewIssue, type PreviewRow } from './timetablePreview'

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

const GRADE_SHEET = [
  { id: 5, name: '八年级' },
  { id: 6, name: '七年级' },
]

async function mountStep(
  issues: PreviewIssue[],
  rowsOverride: PreviewRow[] = rows(),
  grades: { id: number; name: string }[] = [{ id: 5, name: '一年级' }],
): Promise<VueWrapper> {
  httpMock.get.mockImplementation((url: string) => {
    if (url === '/grades') return Promise.resolve({ data: grades })
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

async function selectGroups(wrapper: VueWrapper, groups: MappingGroup[]) {
  wrapper.findComponent({ name: 'ElTable' }).vm.$emit('selection-change', groups)
  await flushPromises()
}

async function clickBatchCreate(wrapper: VueWrapper) {
  await wrapper
    .findAll('button')
    .find((button) => button.text().includes('批量创建所选'))!
    .trigger('click')
  await flushPromises()
}

async function enableCreate(wrapper: VueWrapper) {
  wrapper
    .findAllComponents({ name: 'ElCheckbox' })
    .find((component) => component.text().includes('系统中没有，创建'))!
    .vm.$emit('update:modelValue', true)
  await flushPromises()
}

function createButton(wrapper: VueWrapper) {
  const found = wrapper.findAll('button').find((button) => button.text() === '确认创建')
  if (!found) throw new Error('create button not found')
  return found
}

function studentCountInput(wrapper: VueWrapper) {
  const found = wrapper
    .findAllComponents({ name: 'ElInputNumber' })
    .find((component) => component.attributes('data-testid') === 'student-count')
  if (!found) throw new Error('student count input not found')
  return found
}

function previewResponse(issues: PreviewIssue[]) {  return {
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

  it('按推断年级显示新建与已有', async () => {
    const wrapper = await mountStep(
      [
        issue({
          row: 1,
          value: '八（1）',
          inferredGrade: '八年级',
          gradeCandidates: ['七年级', '八年级'],
        }),
        issue({
          row: 2,
          value: '高一1班',
          inferredGrade: '高一',
          gradeCandidates: ['七年级', '八年级'],
        }),
      ],
      rows(),
      GRADE_SHEET,
    )

    const hints = wrapper.findAll('[data-testid="grade-hint"]').map((hint) => hint.text())
    expect(hints).toEqual(['八年级 已有', '高一 新建'])
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

  it('创建班级默认人数 45，改过的值随创建提交', async () => {
    const wrapper = await mountStep(
      [issue({ inferredGrade: '八年级', gradeCandidates: ['八年级'] })],
      rows(),
      GRADE_SHEET,
    )
    httpMock.post.mockResolvedValue(previewResponse([]))
    await enableCreate(wrapper)

    expect(studentCountInput(wrapper).props('modelValue')).toBe(45)
    studentCountInput(wrapper).vm.$emit('update:modelValue', 50)
    await flushPromises()

    await createButton(wrapper).trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/mapping', {
      mappings: [],
      creates: [
        {
          field: 'className',
          source: '一年级(1)班',
          name: '一年级(1)班',
          gradeId: 5,
          studentCount: 50,
        },
      ],
    })
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

  it('批量创建按组提交各自的年级与人数', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const issues = [
      issue({ row: 1, value: '八（1）', inferredGrade: '八年级', gradeCandidates: ['八年级'] }),
      issue({ row: 2, value: '高一1班', inferredGrade: '高一', gradeCandidates: ['八年级'] }),
      issue({ row: 3, field: 'courseName', value: '道法法治' }),
    ]
    const wrapper = await mountStep(issues, rows(), GRADE_SHEET)
    httpMock.post.mockResolvedValue(previewResponse([]))

    await selectGroups(wrapper, mappingGroups(issues))
    await clickBatchCreate(wrapper)

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/mapping', {
      mappings: [],
      creates: [
        { field: 'className', source: '八（1）', name: '八（1）', gradeId: 5, studentCount: 45 },
        {
          field: 'className',
          source: '高一1班',
          name: '高一1班',
          gradeName: '高一',
          studentCount: 45,
        },
        { field: 'courseName', source: '道法法治', name: '道法法治' },
      ],
    })
  })

  it('推断不出年级的班级要求手动选年级后才能批量创建', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const issues = [issue({ value: '综合班', gradeCandidates: ['七年级'] })]
    const wrapper = await mountStep(issues, rows(), GRADE_SHEET)
    httpMock.post.mockResolvedValue(previewResponse([]))

    await selectGroups(wrapper, mappingGroups(issues))
    await clickBatchCreate(wrapper)

    expect(warning).toHaveBeenCalledWith('有班级推断不出年级，请先选择年级')
    expect(httpMock.post).not.toHaveBeenCalled()

    selectByTestId(wrapper, 'manual-grade').vm.$emit('update:modelValue', 6)
    await flushPromises()
    await clickBatchCreate(wrapper)

    expect(httpMock.post).toHaveBeenCalledWith('/ai/import/sessions/9/mapping', {
      mappings: [],
      creates: [
        { field: 'className', source: '综合班', name: '综合班', gradeId: 6, studentCount: 45 },
      ],
    })
    warning.mockRestore()
  })

  it('单个创建时推断不出年级会提示且不提交', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const wrapper = await mountStep([issue({ value: '综合班' })], rows())
    httpMock.post.mockResolvedValue(previewResponse([]))
    await enableCreate(wrapper)

    await createButton(wrapper).trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith('创建班级需要先选择年级')
    expect(httpMock.post).not.toHaveBeenCalled()
    warning.mockRestore()
  })

  it('节次组不能勾选批量创建', async () => {
    const wrapper = await mountStep([
      issue({ field: 'periodNo', value: '第九节', candidates: ['第1节'] }),
    ])
    const selectionColumn = wrapper.findAllComponents({ name: 'ElTableColumn' })[0]
    const selectable = selectionColumn.props('selectable') as (row: { kind: string }) => boolean

    expect(selectable({ kind: 'slot' })).toBe(false)
    expect(selectable({ kind: 'entity' })).toBe(true)
  })
})
