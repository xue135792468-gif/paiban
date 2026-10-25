import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TimetablePreviewGrid from './TimetablePreviewGrid.vue'
import type { PreviewRow, TeacherWarning } from './timetablePreview'

const ROWS: PreviewRow[] = [
  {
    values: {
      className: '八（1）',
      dayOfWeek: '星期一',
      periodNo: '1',
      courseName: '信息科技',
      teacherName: '信息技术',
    },
  },
  {
    values: {
      className: '八（1）',
      dayOfWeek: '星期一',
      periodNo: '2',
      courseName: '信息科技',
      teacherName: '张伟',
    },
  },
]

function warning(overrides: Partial<TeacherWarning> = {}): TeacherWarning {
  return {
    value: '信息技术',
    reasons: [{ code: 'placeholder', message: '命中占位词「信息技术」' }],
    lessonCount: 1,
    rowNumbers: [1],
    ...overrides,
  }
}

async function mountGrid(teacherWarnings: TeacherWarning[] = []) {
  const wrapper = mount(TimetablePreviewGrid, {
    props: { rows: ROWS, teacherWarnings },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('TimetablePreviewGrid 的疑似教师值提示', () => {
  it('把可疑值所在课节标黄，并在网格上方列出教师值', async () => {
    const wrapper = await mountGrid([warning()])

    const hint = wrapper.get('[data-testid=teacher-warning-hint]').text()
    expect(hint).toContain('信息技术')
    expect(hint).toContain('1 个')
    expect(wrapper.findAll('.lesson.warning')).toHaveLength(1)
    expect(wrapper.text()).toContain('黄框：教师值疑似课程名或占位词')
  })

  it('没有可疑值时不高亮、不显示提示', async () => {
    const wrapper = await mountGrid()

    expect(wrapper.find('[data-testid=teacher-warning-hint]').exists()).toBe(false)
    expect(wrapper.findAll('.lesson.warning')).toHaveLength(0)
  })
})
