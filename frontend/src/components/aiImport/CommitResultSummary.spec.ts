import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import CommitResultSummary from './CommitResultSummary.vue'
import type { CategoryResult, TeacherWarning } from './timetablePreview'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const CATEGORIES = [
  { category: 'teacher', created: 3, updated: 1, skipped: 0, errors: [] },
  { category: 'course', created: 16, updated: 0, skipped: 0, errors: [] },
  { category: 'class', created: 14, updated: 0, skipped: 0, errors: [] },
  { category: 'timetable', created: 546, updated: 0, skipped: 2, errors: [] },
]

async function mountSummary(
  categories: CategoryResult[] = CATEGORIES,
  draftId: number | null = 99,
  teacherWarnings: TeacherWarning[] = [],
) {
  const wrapper = mount(CommitResultSummary, {
    props: { categories, draftId, teacherWarnings },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('CommitResultSummary', () => {
  it('按类别显示实际写入数量', async () => {
    const wrapper = await mountSummary()

    const text = wrapper.get('[data-testid=commit-categories]').text()
    expect(text).toContain('教师名单')
    expect(text).toContain('546')
    expect(text).toContain('课程清单')
    expect(text).toContain('16')
  })

  it('有草稿时提供查看入口', async () => {
    const wrapper = await mountSummary()

    await wrapper.findAll('button').find((button) => button.text() === '查看草稿')!.trigger('click')

    expect(push).toHaveBeenCalledWith('/admin/drafts/99')
  })

  it('失败类别的错误逐条展示', async () => {
    const wrapper = await mountSummary([
      { category: 'timetable', created: 0, updated: 0, skipped: 3, errors: ['第 2 行：教师不能为空'] },
    ])

    expect(wrapper.text()).toContain('第 2 行：教师不能为空')
  })

  it('结果页列出去重后的疑似教师值与原因', async () => {
    const wrapper = await mountSummary(CATEGORIES, 99, [
      {
        value: '信息技术',
        reasons: [{ code: 'placeholder', message: '命中占位词「信息技术」' }],
        lessonCount: 14,
        rowNumbers: [1, 2, 3, 4],
      },
      {
        value: '待定',
        reasons: [{ code: 'placeholder', message: '命中占位词「待定」' }],
        lessonCount: 2,
        rowNumbers: [7, 8],
      },
    ])

    const text = wrapper.get('[data-testid=teacher-warnings]').text()
    expect(text).toContain('疑似教师值 2 个')
    expect(text).toContain('信息技术')
    expect(text).toContain('命中占位词「信息技术」')
    expect(text).toContain('待定')
  })

  it('结果页给出到教师页改名的入口', async () => {
    const wrapper = await mountSummary(CATEGORIES, 99, [
      { value: '待定', reasons: [], lessonCount: 2, rowNumbers: [1, 2] },
    ])

    await wrapper.get('[data-testid=teacher-warnings-rename]').trigger('click')

    expect(push).toHaveBeenCalledWith('/admin/teachers')
  })
})
