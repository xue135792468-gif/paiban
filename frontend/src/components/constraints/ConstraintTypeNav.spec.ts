import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConstraintTypeNav from './ConstraintTypeNav.vue'
import { groupRows } from './draftRules'
import type { ParamDef, TypeDraft } from './types'

const TEACHER_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

function row(overrides: Partial<TypeDraft> & { code: string }): TypeDraft {
  return {
    name: overrides.code,
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: [],
    values: {},
    entries: [],
    enabled: false,
    hard: true,
    weight: 100,
    touched: false,
    ...overrides,
  }
}

function navRows(): TypeDraft[] {
  return [
    row({ code: 'TEACHER_CONFLICT', category: 'CONFLICT', categoryLabel: '冲突类', enabled: true }),
    row({ code: 'CLASS_CONFLICT', category: 'CONFLICT', categoryLabel: '冲突类', enabled: false }),
    row({
      code: 'TEACHER_UNAVAILABLE',
      category: 'PREFERENCE',
      categoryLabel: '教师偏好',
      name: '教师禁排时段',
      params: TEACHER_PARAMS,
      enabled: true,
      entries: [
        { key: 'e1', id: null, values: { teacherId: 1, dayOfWeek: 3, periodNo: 2 } },
        { key: 'e2', id: null, values: { teacherId: 2, dayOfWeek: 4, periodNo: 5 } },
      ],
    }),
    row({
      code: 'TEACHER_DAY_OFF',
      category: 'PREFERENCE',
      categoryLabel: '教师偏好',
      name: '教师固定无课日',
      params: TEACHER_PARAMS.slice(0, 2),
    }),
  ]
}

function mountNav(overrides: Record<string, unknown> = {}) {
  return mount(ConstraintTypeNav, {
    props: {
      groups: groupRows(navRows()),
      selected: 'TEACHER_UNAVAILABLE',
      errorTypes: [],
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConstraintTypeNav', () => {
  it('按四组分区，每项显示类型名与条目数，开关型显示「开关」', () => {
    const wrapper = mountNav()
    expect(wrapper.findAll('[data-testid="constraint-nav-group"]')).toHaveLength(2)
    const items = wrapper.findAll('[data-testid="constraint-nav-item"]')
    expect(items).toHaveLength(4)
    expect(
      wrapper.get('[data-type="TEACHER_UNAVAILABLE"] [data-testid="constraint-nav-count"]').text(),
    ).toBe('2 条')
    expect(
      wrapper.get('[data-type="TEACHER_CONFLICT"] [data-testid="constraint-nav-count"]').text(),
    ).toBe('开关')
  })

  it('状态点区分启用、未启用与有错误', () => {
    const wrapper = mountNav({ errorTypes: ['TEACHER_UNAVAILABLE'] })
    const dot = (code: string) =>
      wrapper.get(`[data-type="${code}"] [data-testid="constraint-nav-dot"]`)
    expect(dot('TEACHER_CONFLICT').attributes('data-state')).toBe('on')
    expect(dot('CLASS_CONFLICT').attributes('data-state')).toBe('off')
    expect(dot('TEACHER_UNAVAILABLE').attributes('data-state')).toBe('error')
  })

  it('选中项带标记，点击切换当前类型', async () => {
    const wrapper = mountNav()
    expect(wrapper.get('[data-type="TEACHER_UNAVAILABLE"]').classes()).toContain('is-active')
    await wrapper.get('[data-type="CLASS_CONFLICT"]').trigger('click')
    expect(wrapper.emitted('select')).toEqual([['CLASS_CONFLICT']])
  })

  it('分组头分别给出类型数与条目数', () => {
    const wrapper = mountNav()
    const group = wrapper.findAll('[data-testid="constraint-nav-group"]')[1]
    expect(group.get('[data-testid="constraint-nav-group-counts"]').text()).toBe(
      '1/2 类型 · 2 条',
    )
  })
})
