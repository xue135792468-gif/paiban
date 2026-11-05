import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import CyclePlanPanel from './CyclePlanPanel.vue'
import type { CyclePlan, DraftLesson, DraftPeriod } from './types'

const periods: DraftPeriod[] = [
  { id: 100, dayOfWeek: 1, periodNo: 1, label: '第1节', startTime: '', endTime: '' },
  { id: 200, dayOfWeek: 1, periodNo: 2, label: '第2节', startTime: '', endTime: '' },
]

function lesson(id: number, courseName: string, teacherName: string): DraftLesson {
  return {
    id,
    assignmentId: id,
    courseId: id,
    courseName,
    teacherId: id,
    teacherName,
    classId: 1,
    studentCount: 40,
    locked: false,
  }
}

const plan: CyclePlan = {
  token: 'tok-1',
  kind: 'CYCLE',
  moves: [
    { lessonId: 11, fromPeriodId: 100, toPeriodId: 200, teacherId: 9, courseId: 1, classId: 1 },
    { lessonId: 12, fromPeriodId: 200, toPeriodId: 100, teacherId: 10, courseId: 2, classId: 1 },
  ],
  movedLessons: 2,
  affectedTeachers: 2,
  affectedDays: 1,
  softPenaltyAfter: 12,
  softPenaltyDelta: -3,
  expiresAt: '2026-01-01T00:00:00Z',
}

async function mountPanel(props: Record<string, unknown> = {}) {
  const wrapper = mount(CyclePlanPanel, {
    props: {
      modelValue: true,
      plans: [plan],
      lessons: [lesson(11, '数学', '张老师'), lesson(12, '语文', '李老师')],
      periods,
      ...props,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true } },
  })
  await flushPromises()
  return wrapper
}

describe('CyclePlanPanel', () => {
  it('渲染方案的前后位置、教师、影响面和软分变化', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.get('[data-testid="cycle-plan-0"]').text()).toContain('联动换位')
    expect(wrapper.text()).toContain('移动 2 节')
    expect(wrapper.text()).toContain('涉及 2 位教师')
    expect(wrapper.text()).toContain('1 天')
    expect(wrapper.text()).toContain('软分变化 -3')
    expect(wrapper.text()).toContain('数学（张老师）：周一第1节 → 周一第2节')
    expect(wrapper.text()).toContain('语文（李老师）：周一第2节 → 周一第1节')
  })

  it('FOUND 状态显示服务端消息，NONE 显示无方案提示', async () => {
    const found = await mountPanel({ status: 'FOUND', message: '找到 1 个方案' })
    expect(found.text()).toContain('找到 1 个方案')

    const none = await mountPanel({ status: 'NONE', message: '当前范围和上限内没有可用方案', plans: [] })
    expect(none.text()).toContain('当前范围和上限内没有可用方案')
  })

  it('BUDGET_EXHAUSTED 标注搜索未完成，不声称穷尽', async () => {
    const wrapper = await mountPanel({
      status: 'BUDGET_EXHAUSTED',
      message: '搜索预算耗尽，以下是已独立验证的部分方案，尚未穷尽',
    })

    expect(wrapper.text()).toContain('搜索预算耗尽')
    expect(wrapper.text()).toContain('尚未穷尽')
  })

  it('点击应用发出 apply，取消发出 cancel 并关闭', async () => {
    const wrapper = await mountPanel()

    await wrapper.get('[data-testid="cycle-plan-0"] button').trigger('click')
    expect(wrapper.emitted('apply')?.[0]?.[0]).toMatchObject({ token: 'tok-1' })

    await wrapper.get('.el-dialog__footer button').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })

  it('应用中禁用应用按钮', async () => {
    const wrapper = await mountPanel({ applying: true })
    const button = wrapper.get('[data-testid="cycle-plan-0"] button')

    expect(button.attributes('disabled')).toBeDefined()
  })
})
