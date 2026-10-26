import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConstraintEntryList from './ConstraintEntryList.vue'
import type { EntryDraft, ParamDef, TypeDraft } from './types'

const COURSE_PARAMS: ParamDef[] = [{ name: 'courseId', label: '课程', type: 'ID', required: true }]

const SAME_COURSE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程（留空表示全部）', type: 'ID', required: false },
]

const ROOM_TYPE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'roomType', label: '教室类型', type: 'ROOM_TYPE', required: true },
]

const SLOT_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

function entry(
  key: string,
  values: Record<string, unknown>,
  slots?: EntryDraft['slots'],
): EntryDraft {
  return { key, id: null, values, slots }
}

function row(overrides: Partial<TypeDraft> & { code: string }): TypeDraft {
  return {
    name: overrides.code,
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: [],
    values: {},
    entries: [],
    enabled: true,
    hard: true,
    weight: 100,
    touched: false,
    ...overrides,
  }
}

const TEACHERS = [
  { id: 1, name: '张伟' },
  { id: 2, name: '李娜' },
]
const COURSES = [
  { id: 9, name: '数学' },
  { id: 4, name: '语文' },
]

function mountList(rowValue: TypeDraft, entryErrors: Record<number, string[]> = {}) {
  return mount(ConstraintEntryList, {
    props: {
      row: rowValue,
      editable: true,
      teachers: TEACHERS,
      courses: COURSES,
      entryErrors,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConstraintEntryList', () => {
  it('空列表说明「未配置」，不会自动补一条规则', () => {
    const wrapper = mountList(row({ code: 'COURSE_NOT_IN_EVENING', params: COURSE_PARAMS }))
    expect(wrapper.get('[data-testid="constraint-entry-empty"]').text()).toBe(
      '空列表＝该类型未配置（不会自动补一条规则）',
    )
    expect(wrapper.findAll('[data-testid="constraint-entry"]')).toHaveLength(0)
  })

  it('每条卡片一行摘要，条件字段按目录渲染', () => {
    const wrapper = mountList(
      row({
        code: 'COURSE_REQUIRED_ROOM_TYPE',
        name: '课程指定教室类型',
        params: ROOM_TYPE_PARAMS,
        entries: [entry('e1', { courseId: 9, roomType: 'LAB' })],
      }),
    )
    const card = wrapper.get('[data-testid="constraint-entry"]')
    expect(card.get('[data-testid="constraint-entry-summary"]').text()).toBe('数学 · 实验室')
    expect(card.find('[data-testid="constraint-entry-course"]').exists()).toBe(true)
    expect(card.find('[data-testid="constraint-entry-room-type"]').exists()).toBe(true)
    // 必填对象没有「全部」入口，控件也不会因为空值被禁用。
    expect(card.find('[data-testid="constraint-entry-all-target"]').exists()).toBe(false)
    expect(
      card.get('[data-testid="constraint-entry-course"] input').attributes('disabled'),
    ).toBeUndefined()
  })

  it('可空课程给出显式的「适用于全部课程」勾选，勾上后课程下拉不可用', async () => {
    const wrapper = mountList(
      row({
        code: 'SAME_COURSE_ONCE_PER_DAY',
        name: '同一课程一天最多一节',
        params: SAME_COURSE_PARAMS,
        entries: [entry('e1', {})],
      }),
    )
    const card = wrapper.get('[data-testid="constraint-entry"]')
    const all = card.get('[data-testid="constraint-entry-all-target"]')
    expect(all.text()).toContain('适用于全部课程')
    expect(all.get('input').element).toHaveProperty('checked', true)
    expect(card.get('[data-testid="constraint-entry-summary"]').text()).toBe('全部课程')
    // 勾选「全部」时课程下拉禁用：不允许同时表达两种目标。
    expect(
      card.get('[data-testid="constraint-entry-course"] input').attributes('disabled'),
    ).toBeDefined()

    await all.get('input').setValue(false)
    const toggles = wrapper.emitted('all-target')!
    expect(toggles[toggles.length - 1]).toEqual(['e1', 'courseId', false])
  })

  it('偏好时段一条卡片多个时段，摘要按「或」；可增删时段', async () => {
    const wrapper = mountList(
      row({
        code: 'TEACHER_PREFERRED_SLOT',
        name: '教师偏好时段',
        params: SLOT_PARAMS,
        entries: [
          entry('e1', { teacherId: 1 }, [
            { key: 's1', id: 21, dayOfWeek: 1, periodNo: 1 },
            { key: 's2', id: null, dayOfWeek: 3, periodNo: 2 },
          ]),
        ],
      }),
    )
    const card = wrapper.get('[data-testid="constraint-entry"]')
    expect(card.get('[data-testid="constraint-entry-summary"]').text()).toBe(
      '张伟 · 周一第1节 或 周三第2节（命中任一即满足）',
    )
    expect(card.findAll('[data-testid="constraint-entry-slot"]')).toHaveLength(2)
    // 一条卡片里没有「全部」入口：偏好时段必须点名教师和时段。
    expect(card.find('[data-testid="constraint-entry-all-target"]').exists()).toBe(false)

    await card.get('[data-testid="constraint-entry-slot-add"]').trigger('click')
    expect(wrapper.emitted('add-slot')?.[0]).toEqual(['e1'])
  })

  it('重复条目与服务器错误都显示在对应卡片里', () => {
    const wrapper = mountList(
      row({
        code: 'SAME_COURSE_ONCE_PER_DAY',
        name: '同一课程一天最多一节',
        params: SAME_COURSE_PARAMS,
        entries: [entry('e1', {}), entry('e2', { courseId: 4 })],
      }),
      { 2: ['参数「课程」指向不存在的课程: 4'] },
    )
    const cards = wrapper.findAll('[data-testid="constraint-entry"]')
    expect(cards[1].get('[data-testid="constraint-entry-error"]').text()).toContain(
      '指向不存在的课程',
    )
  })

  it('同一课程两个教室类型：互斥提示直接落在两张卡片上', () => {
    const wrapper = mountList(
      row({
        code: 'COURSE_REQUIRED_ROOM_TYPE',
        name: '课程指定教室类型',
        params: ROOM_TYPE_PARAMS,
        entries: [
          entry('e1', { courseId: 9, roomType: 'NORMAL' }),
          entry('e2', { courseId: 9, roomType: 'LAB' }),
        ],
      }),
    )
    const cards = wrapper.findAll('[data-testid="constraint-entry"]')
    expect(cards[0].get('[data-testid="constraint-entry-conflict"]').text()).toContain(
      '同一目标不能并存',
    )
    expect(cards[1].get('[data-testid="constraint-entry-conflict"]').text()).toContain(
      '课程「数学」',
    )
  })

  it('添加条目按钮把事件交给页面', async () => {
    const wrapper = mountList(row({ code: 'COURSE_NOT_IN_EVENING', params: COURSE_PARAMS }))
    await wrapper.get('[data-testid="constraint-entry-add"]').trigger('click')
    expect(wrapper.emitted('add')).toBeTruthy()
  })
})
