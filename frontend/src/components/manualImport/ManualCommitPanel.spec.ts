import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ManualCommitPanel from './ManualCommitPanel.vue'
import type { ManualPreview } from '@/api/imports'

const PREVIEW: ManualPreview = {
  slotCount: 2,
  lessonCount: 2,
  emptySlotCount: 0,
  teacherCount: 1,
  courseCount: 2,
  rows: [],
  truncated: false,
  teacherWarnings: [
    { value: '信息技术', reasons: [{ code: 'placeholder', message: '命中占位词' }], lessonCount: 2, rowNumbers: [1, 2] },
  ],
  pendingTeachers: ['信息技术'],
  suggestedRule: null,
  recipeMatch: { kind: 'NONE', differences: [] },
  warnings: [],
  fingerprint: {
    sheetName: 'CSV',
    headerRow: 0,
    columnCount: 3,
    headers: ['班级|星期|节次', '课程', '教师'],
    delimiter: '|',
    layout: 'LONG',
  },
  layout: 'LONG',
}

function mountPanel(overrides: Record<string, unknown> = {}) {
  return mount(ManualCommitPanel, {
    props: {
      preview: PREVIEW,
      semesters: [{ id: 7, name: '2026 秋季学期' }],
      teachers: [{ id: 3, name: '张三' }],
      semesterId: 7,
      draftName: '草稿',
      applyRule: false,
      teacherMappings: {},
      keptPlaceholders: [],
      pendingTeachers: ['信息技术'],
      committing: false,
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('占位教师处理面板', () => {
  it('没选完时禁用入库并列出待处理名单', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('[data-testid="placeholder-pending"]').text()).toContain('信息技术')
    expect(wrapper.get('[data-testid="manual-commit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="manual-teacher-mapping"]').exists()).toBe(false)
  })

  it('映射模式：已有映射时展开教师下拉并回显选中的教师', async () => {
    const wrapper = mountPanel({ teacherMappings: { 信息技术: '3' }, pendingTeachers: [] })
    await flushPromises()

    expect(wrapper.find('[data-testid="manual-teacher-mapping"]').exists()).toBe(true)
    expect(wrapper.findAllComponents({ name: 'ElSelect' }).at(-1)?.props('modelValue')).toBe('3')
    expect(wrapper.find('[data-testid="placeholder-pending"]').exists()).toBe(false)
  })

  it('选择“保留为教师”后上报保留名单，并清掉同名映射', async () => {
    const wrapper = mountPanel({ teacherMappings: { 信息技术: '3' } })
    await wrapper.get('input[value="keep"]').setValue()
    await flushPromises()

    expect(wrapper.emitted('update:keptPlaceholders')?.at(-1)).toEqual([['信息技术']])
    expect(wrapper.emitted('update:teacherMappings')?.at(-1)).toEqual([{}])
  })

  it('已有同名教师的占位提醒只要提示，不要求用户选', async () => {
    const wrapper = mountPanel({ pendingTeachers: [], preview: { ...PREVIEW, pendingTeachers: [] } })
    await flushPromises()

    expect(wrapper.find('input[value="keep"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="placeholder-row"]').text()).toContain('已有同名教师')
    expect(wrapper.get('[data-testid="manual-commit"]').attributes('disabled')).toBeUndefined()
  })

  it('全部选完后不再显示待处理提示，入库按钮可用', async () => {
    const wrapper = mountPanel({ keptPlaceholders: ['信息技术'], pendingTeachers: [] })
    await flushPromises()

    expect(wrapper.find('[data-testid="placeholder-pending"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="manual-commit"]').attributes('disabled')).toBeUndefined()
  })
})
