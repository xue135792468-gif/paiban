import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WorkbookSummaryPanel from './WorkbookSummaryPanel.vue'
import type { WorkbookSummary } from './timetablePreview'

const COLUMNS = [
  { field: 'className', label: '班级' },
  { field: 'dayOfWeek', label: '星期' },
  { field: 'periodNo', label: '节次' },
  { field: 'courseName', label: '课程' },
  { field: 'teacherName', label: '教师' },
]

function workbook(overrides: Partial<WorkbookSummary> = {}): WorkbookSummary {
  return {
    structureConfirmed: true,
    sheets: [
      {
        sheet: '数据源',
        category: 'timetable',
        columns: { className: '列1·班级', dayOfWeek: '列1·星期', periodNo: '列1·节次' },
        recognizedRows: 560,
        newRows: 546,
        skippedReason: null,
        note: '无表头，复合列已拆分',
      },
      {
        sheet: '使用说明',
        category: 'explanation',
        columns: {},
        recognizedRows: 0,
        newRows: 0,
        skippedReason: '非空单元格少于 5 个，判断为说明，已跳过',
        note: '',
      },
    ],
    ...overrides,
  }
}

async function mountPanel(props: {
  workbook?: WorkbookSummary
  warnings?: string[]
  toolTrace?: { round: number; tool: string; args: string; result: string }[]
}) {
  const wrapper = mount(WorkbookSummaryPanel, {
    props: {
      workbook: props.workbook ?? workbook(),
      columns: COLUMNS,
      warnings: props.warnings ?? [],
      toolTrace: props.toolTrace ?? [],
    },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('WorkbookSummaryPanel', () => {
  it('列出每张工作表的类别、行数与跳过原因', async () => {
    const wrapper = await mountPanel({})

    const text = wrapper.get('[data-testid=workbook-sheets]').text()
    expect(text).toContain('数据源')
    expect(text).toContain('课表')
    expect(text).toContain('560')
    expect(text).toContain('546')
    expect(text).toContain('使用说明')
    expect(text).toContain('说明')
    expect(text).toContain('非空单元格少于 5 个')
  })

  it('把列映射按系统字段名展示出来', async () => {
    const wrapper = await mountPanel({})

    expect(wrapper.text()).toContain('班级←列1·班级')
    expect(wrapper.text()).toContain('星期←列1·星期')
  })

  it('结构未确认时显示标记与警告', async () => {
    const wrapper = await mountPanel({
      workbook: workbook({ structureConfirmed: false }),
      warnings: ['结构未确认，请人工核对', '空课位 14 个（没有课程与教师），已跳过'],
    })

    expect(wrapper.text()).toContain('结构未确认')
    const warnings = wrapper.findAll('[data-testid=workbook-warning]')
    expect(warnings).toHaveLength(2)
    expect(warnings[0].text()).toContain('结构未确认，请人工核对')
    expect(warnings[1].text()).toContain('空课位 14 个')
  })

  it('展示模型调用轨迹', async () => {
    const wrapper = await mountPanel({
      toolTrace: [
        { round: 1, tool: 'list_sheets', args: '', result: '{"sheets":[...]}' },
        { round: 2, tool: 'finish', args: '{"sheets":[...]}', result: '结构已收到' },
      ],
    })

    expect(wrapper.text()).toContain('模型调用轨迹（2 轮）')
    expect(wrapper.text()).toContain('list_sheets')
  })
})
