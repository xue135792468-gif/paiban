import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import CyclePlanPanel from './CyclePlanPanel.vue'
import type { CyclePlan, DraftLesson, DraftPeriod } from './types'

const periods: DraftPeriod[] = [
  { id: 100, dayOfWeek: 1, periodNo: 1, label: '第1节', startTime: '', endTime: '' },
  { id: 200, dayOfWeek: 1, periodNo: 2, label: '第2节', startTime: '', endTime: '' },
]

function lesson(
  id: number,
  courseName: string,
  teacherName: string,
  overrides: Partial<DraftLesson> = {},
): DraftLesson {
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
    ...overrides,
  }
}

/** The plan moves teachers 9 and 10, so the draft has to carry those ids. */
const teacherLessons: DraftLesson[] = [
  lesson(11, '数学', '张老师', { teacherId: 9, className: '高一(1)班', periodId: 100, dayOfWeek: 1, periodNo: 1 }),
  lesson(12, '语文', '李老师', { teacherId: 10, className: '高一(2)班', periodId: 200, dayOfWeek: 1, periodNo: 2 }),
]

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
      lessons: teacherLessons,
      periods,
      ...props,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true } },
  })
  await flushPromises()
  return wrapper
}

/** The grid cell rendering one lesson in the preview, so positions are read as rendered. */
function cellOf(wrapper: VueWrapper, lessonId: number) {
  const preview = wrapper.get('[data-testid="cycle-plan-teacher-preview"]')
  const cells = preview
    .findAll('.cell')
    .filter((cell) => cell.find(`[data-lesson-id="${lessonId}"]`).exists())
  expect(cells, `no cell renders lesson ${lessonId}`).toHaveLength(1)
  return cells[0]
}

describe('CyclePlanPanel', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  function stubNarrow(narrow: boolean) {
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockImplementation((query: string) => ({
        query,
        matches: narrow,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      })),
    )
  }

  it('渲染方案的前后位置、教师、影响面和软分变化', async () => {
    const wrapper = await mountPanel()

    const card = wrapper.get('[data-testid="cycle-plan-0"]')
    expect(card.text()).toContain('联动换位')
    expect(wrapper.text()).toContain('移动 2 节')
    expect(wrapper.text()).toContain('涉及 2 位教师')
    expect(wrapper.text()).toContain('1 天')
    expect(wrapper.text()).toContain('软分变化 -3')
    // Teacher names became their own links, so each move row is read separately.
    const rows = card.findAll('.move-item')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('张老师')
    expect(rows[0].text()).toContain('高一(1)班')
    expect(rows[0].text()).toContain('数学：周一第1节 → 周一第2节')
    expect(rows[1].text()).toContain('李老师')
    expect(rows[1].text()).toContain('语文：周一第2节 → 周一第1节')
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

    await wrapper.get('[data-testid="cycle-plan-apply"]').trigger('click')
    expect(wrapper.emitted('apply')?.[0]?.[0]).toMatchObject({ token: 'tok-1' })

    await wrapper.get('.el-dialog__footer button').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })

  it('应用中禁用应用按钮', async () => {
    const wrapper = await mountPanel({ applying: true })
    const button = wrapper.get('[data-testid="cycle-plan-apply"]')

    expect(button.attributes('disabled')).toBeDefined()
  })

  it('点教师姓名展开该教师完整周课表，默认应用后预览并标注尚未生效', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="cycle-plan-teacher-preview"]').exists()).toBe(false)

    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()

    const preview = wrapper.get('[data-testid="cycle-plan-teacher-preview"]')
    expect(preview.text()).toContain('张老师 · 完整周课表')
    expect(preview.text()).toContain('含该教师全部行政班')
    const notice = wrapper.get('[data-testid="cycle-plan-preview-notice"]')
    expect(notice.text()).toContain('应用后预览')
    expect(notice.text()).toContain('尚未生效')
    // Applied view: 数学 moved to Monday period 2 and the card says where it came from.
    expect(cellOf(wrapper, 11).attributes('data-period-id')).toBe('200')
    expect(wrapper.get('[data-lesson-id="11"]').text()).toContain('原周一第1节 → 现周一第2节')
    expect(wrapper.get('[data-lesson-id="11"]').classes()).toContain('highlight')
    // The preview is read-only and opening it writes nothing.
    expect(wrapper.get('[data-lesson-id="11"]').attributes('draggable')).toBe('false')
    expect(wrapper.emitted('apply')).toBeUndefined()
  })

  it('可切回当前安排，课节回到草稿里的真实位置', async () => {
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()

    const radios = wrapper.get('[data-testid="cycle-plan-preview-mode"]').findAll('input')
    expect(radios).toHaveLength(2)
    await radios[1].setValue()
    await flushPromises()

    expect(wrapper.get('[data-testid="cycle-plan-preview-notice"]').text()).toContain(
      '当前显示：草稿里的实际安排',
    )
    expect(cellOf(wrapper, 11).attributes('data-period-id')).toBe('100')
    expect(wrapper.get('[data-lesson-id="11"]').text()).toContain('应用后移至 周一第2节')

    await radios[0].setValue()
    await flushPromises()
    expect(cellOf(wrapper, 11).attributes('data-period-id')).toBe('200')
  })

  it('预览顶部按教师 id 切换本方案教师，同名不同 id 不混人', async () => {
    const lessons = [
      teacherLessons[0],
      // Teacher 9 also takes 语文 in another class, under the same name as teacher 99.
      lesson(12, '语文', '张老师', { teacherId: 9, classId: 2, className: '高一(2)班', periodId: 200, dayOfWeek: 1, periodNo: 2 }),
      lesson(13, '英语', '张老师', { teacherId: 99, classId: 3, className: '高一(3)班', periodId: 100, dayOfWeek: 1, periodNo: 1 }),
    ]
    const twoTeachers: CyclePlan = {
      ...plan,
      affectedTeachers: 2,
      moves: [
        { lessonId: 11, fromPeriodId: 100, toPeriodId: 200, teacherId: 9, courseId: 1, classId: 1 },
        { lessonId: 12, fromPeriodId: 200, toPeriodId: 100, teacherId: 9, courseId: 2, classId: 1 },
        { lessonId: 13, fromPeriodId: 100, toPeriodId: 200, teacherId: 99, courseId: 3, classId: 3 },
      ],
    }
    const wrapper = await mountPanel({ plans: [twoTeachers], lessons })

    await wrapper.get('[data-testid="cycle-plan-teacher-0-99"]').trigger('click')
    await flushPromises()

    const preview = wrapper.get('[data-testid="cycle-plan-teacher-preview"]')
    expect(preview.text()).toContain('张老师 · 完整周课表')
    // Only lesson 13 belongs to teacher 99 even though the name is shared.
    expect(preview.findAll('[data-lesson-id]')).toHaveLength(1)
    expect(cellOf(wrapper, 13).attributes('data-period-id')).toBe('200')

    const chips = wrapper.get('[data-testid="cycle-plan-preview-teachers"]')
    expect(chips.find('[data-testid="cycle-plan-preview-teacher-9"]').exists()).toBe(true)
    await chips.get('[data-testid="cycle-plan-preview-teacher-9"]').trigger('click')
    await flushPromises()

    const switched = wrapper.get('[data-testid="cycle-plan-teacher-preview"]')
    expect(switched.findAll('[data-lesson-id]')).toHaveLength(2)
    expect(switched.find('[data-lesson-id="13"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="cycle-plan-preview-teacher-9"]').classes()).toContain('on')
  })

  it('切到另一个方案时重新投影，不累计上一个方案的结果', async () => {
    const first: CyclePlan = { ...plan, moves: [plan.moves[0]] }
    const second: CyclePlan = {
      token: 'tok-2',
      kind: 'DIRECT',
      moves: [
        { lessonId: 12, fromPeriodId: 200, toPeriodId: 100, teacherId: 10, courseId: 2, classId: 1 },
      ],
      movedLessons: 1,
      affectedTeachers: 1,
      affectedDays: 1,
      softPenaltyAfter: 5,
      softPenaltyDelta: -1,
      expiresAt: '2026-01-01T00:00:00Z',
    }
    const wrapper = await mountPanel({ plans: [first, second] })

    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="cycle-plan-teacher-preview"]').text()).toContain('张老师')
    expect(cellOf(wrapper, 11).attributes('data-period-id')).toBe('200')

    // Switching plans re-projects: the previous plan's move is no longer shown.
    await wrapper.get('[data-testid="cycle-plan-1"] [data-testid="cycle-plan-teacher-1-10"]').trigger('click')
    await flushPromises()

    const preview = wrapper.get('[data-testid="cycle-plan-teacher-preview"]')
    expect(preview.text()).toContain('李老师 · 完整周课表')
    expect(preview.find('[data-lesson-id="11"]').exists()).toBe(false)
    expect(cellOf(wrapper, 12).attributes('data-period-id')).toBe('100')
    expect(wrapper.get('[data-testid="cycle-plan-1"]').classes()).toContain('active')
    // Only the newly selected plan is applied, so nothing accumulates.
    expect(preview.text()).toContain('直接交换')
  })

  it('窄屏全屏预览并保留方案滚动位置，返回后回到原处', async () => {
    stubNarrow(true)
    const wrapper = await mountPanel({ plans: [plan, { ...plan, token: 'tok-2' }] })
    const paneElement = wrapper.get('[data-testid="cycle-plan-list-pane"]').element
    // jsdom scrolls nothing: this pins the wiring only — the element the panel saves and
    // restores is the same `.list-pane` the narrow CSS bounds as the scroller. Real phone
    // scrolling is checked by the user, not here.
    expect(paneElement.classList.contains('list-pane')).toBe(true)
    Object.defineProperty(paneElement, 'scrollTop', { value: 0, writable: true, configurable: true })
    paneElement.scrollTop = 180

    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.panel-body').classes()).toContain('preview-full')
    expect(wrapper.find('[data-testid="cycle-plan-preview-back"]').exists()).toBe(true)
    expect(wrapper.get('.el-dialog__footer').text()).toContain('返回方案列表')

    // The list pane is remounted while the full-screen preview is up, so it starts at the top.
    expect(wrapper.get('[data-testid="cycle-plan-list-pane"]').element.scrollTop).toBe(0)

    await wrapper.get('[data-testid="cycle-plan-preview-back"]').trigger('click')
    await flushPromises()
    await nextTick()

    expect(wrapper.get('.panel-body').classes()).not.toContain('preview-full')
    expect(wrapper.get('[data-testid="cycle-plan-list-pane"]').element.scrollTop).toBe(180)
    expect(wrapper.findAll('.plan-card')).toHaveLength(2)
    expect(wrapper.emitted('apply')).toBeUndefined()
  })

  it('方案里的课节已不在预期位置时整组不投影，只给当前课表', async () => {
    const stale = teacherLessons.map((item) =>
      item.id === 11 ? { ...item, periodId: 200, dayOfWeek: 1, periodNo: 2 } : item,
    )
    const wrapper = await mountPanel({ lessons: stale })

    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="cycle-plan-preview-unmatched"]').text()).toContain(
      '没有一步投影',
    )
    expect(wrapper.get('[data-testid="cycle-plan-preview-moves"]').text()).toContain('未投影')
    // No partial「应用后」: the grid keeps the draft position and the notice says why.
    expect(wrapper.get('[data-testid="cycle-plan-preview-notice"]').text()).toContain(
      '不提供应用后预览',
    )
    expect(cellOf(wrapper, 11).attributes('data-period-id')).toBe('200')
    expect(wrapper.text()).not.toContain('这节课已移至')
  })

  it('要移动的课已锁定时不展示可用的应用后预览', async () => {
    const locked = teacherLessons.map((item) => (item.id === 11 ? { ...item, locked: true } : item))
    const wrapper = await mountPanel({ lessons: locked })

    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="cycle-plan-preview-notice"]').text()).toContain(
      '不提供应用后预览',
    )
    expect(cellOf(wrapper, 11).attributes('data-period-id')).toBe('100')
    const afterRadio = wrapper
      .get('[data-testid="cycle-plan-preview-mode"]')
      .findAll('input')[0]
    expect((afterRadio.element as HTMLInputElement).disabled).toBe(true)
  })

  it('教师A原位置被别的班占据时仍写明课已移走', async () => {
    // 张老师 (9) moves 周一第1节 → 周一第2节; 李老师 (10) already teaches 周一第1节 in
    // another class. Only the teacher's own week decides whether the slot is empty for them.
    const lessons = [
      lesson(11, '数学', '张老师', { teacherId: 9, className: '高一(1)班', periodId: 100, dayOfWeek: 1, periodNo: 1 }),
      lesson(12, '语文', '李老师', { teacherId: 10, className: '高一(2)班', periodId: 100, dayOfWeek: 1, periodNo: 1 }),
    ]
    const single: CyclePlan = { ...plan, kind: 'DIRECT', moves: [plan.moves[0]], affectedTeachers: 1 }
    const wrapper = await mountPanel({ plans: [single], lessons })

    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()

    const vacatedCell = wrapper.get('[data-period-id="100"]')
    expect(vacatedCell.text()).toContain('这节课已移至 周一第2节')
    expect(cellOf(wrapper, 11).attributes('data-period-id')).toBe('200')
  })

  it('教师自己的另一节课移入原位置时不画虚假空格说明', async () => {
    const lessons = [
      lesson(11, '数学', '张老师', { teacherId: 9, className: '高一(1)班', periodId: 100, dayOfWeek: 1, periodNo: 1 }),
      lesson(12, '语文', '张老师', { teacherId: 9, className: '高一(2)班', periodId: 200, dayOfWeek: 1, periodNo: 2 }),
    ]
    const selfSwap: CyclePlan = {
      ...plan,
      moves: [
        { lessonId: 11, fromPeriodId: 100, toPeriodId: 200, teacherId: 9, courseId: 1, classId: 1 },
        { lessonId: 12, fromPeriodId: 200, toPeriodId: 100, teacherId: 9, courseId: 2, classId: 2 },
      ],
    }
    const wrapper = await mountPanel({ plans: [selfSwap], lessons })

    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()

    // The swap lands 语文 in 周一第1节, so nothing there reads as an emptied slot.
    expect(wrapper.text()).not.toContain('这节课已移至')
    expect(wrapper.get('[data-lesson-id="12"]').text()).toContain('语文')
  })


  it('关闭面板再打开时回到方案列表，不残留上次的预览状态', async () => {
    const wrapper = await mountPanel()
    await wrapper.get('[data-testid="cycle-plan-teacher-0-9"]').trigger('click')
    await flushPromises()
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="cycle-plan-teacher-preview"]').exists()).toBe(false)
    expect(wrapper.get('.el-dialog__footer').text()).toContain('取消')
  })
})
