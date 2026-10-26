import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConstraintTypePanel from './ConstraintTypePanel.vue'
import type { ParamDef, TypeDraft } from './types'

const TEACHER_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const COURSE_PARAMS: ParamDef[] = [{ name: 'courseId', label: '课程', type: 'ID', required: true }]

const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师（留空表示全局默认）', type: 'ID', required: false },
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12 },
]

const THRESHOLD_SCOPE = {
  thresholdObjectField: 'teacherId',
  thresholdValueField: 'maxPerDay',
}

function row(overrides: Partial<TypeDraft> & { code: string }): TypeDraft {
  return {
    name: overrides.code,
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: [],
    entries: [],
    enabled: true,
    hard: true,
    weight: 100,
    ...overrides,
  }
}

function mountPanel(props: Record<string, unknown>, rowValue: TypeDraft) {
  return mount(ConstraintTypePanel, {
    props: {
      row: rowValue,
      canManage: true,
      isJunior: false,
      teachers: [
        { id: 1, name: '张伟' },
        { id: 2, name: '李娜' },
      ],
      courses: [{ id: 9, name: '数学' }],
      classes: [{ id: 5, name: '高一1班' }],
      entryErrors: {},
      typeErrors: [],
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConstraintTypePanel', () => {
  it('硬约束隐藏权重，软约束显示权重', () => {
    const hard = mountPanel({}, row({ code: 'X', params: COURSE_PARAMS }))
    expect(hard.find('[data-testid="constraint-weight"]').exists()).toBe(false)

    const soft = mountPanel({}, row({ code: 'X', params: COURSE_PARAMS, hard: false, weight: 60 }))
    expect(soft.get('[data-testid="constraint-weight"]').text()).toContain('权重 60')
  })

  it('初中模式只保留必须/尽量，且不显示权重', () => {
    const wrapper = mountPanel(
      { isJunior: true },
      row({ code: 'X', params: COURSE_PARAMS, hard: false, weight: 60 }),
    )
    const group = wrapper.get('[data-testid="constraint-hard-group"]')
    expect(group.text()).toContain('必须')
    expect(group.text()).toContain('尽量')
    expect(wrapper.text()).not.toContain('权重')
  })

  it('类型级控制：开关与软硬都交给页面回写', async () => {
    const wrapper = mountPanel({}, row({ code: 'X', params: COURSE_PARAMS, hard: false }))
    await wrapper.get('[data-testid="constraint-enabled-switch"]').trigger('click')
    expect(wrapper.emitted('toggle-enabled')).toBeTruthy()

    const softRadio = wrapper.findAll('[data-testid="constraint-hard-group"] .el-radio-button')[0]
    // el-radio-button is a <label> around a radio input; the input carries the change.
    await softRadio.find('input').trigger('change')
    expect(wrapper.emitted('set-hard')).toBeTruthy()
  })

  it('教师禁排时段渲染多条条目卡片与添加按钮', async () => {
    const wrapper = mountPanel(
      {},
      row({
        code: 'TEACHER_UNAVAILABLE',
        name: '教师禁排时段',
        params: TEACHER_PARAMS,
        entries: [
          { key: 'e1', id: null, values: { teacherId: 1, dayOfWeek: 3, periodNo: 2 } },
          { key: 'e2', id: null, values: { teacherId: 2, dayOfWeek: 4, periodNo: 5 } },
        ],
      }),
    )
    const cards = wrapper.findAll('[data-testid="constraint-entry"]')
    expect(cards).toHaveLength(2)
    expect(cards[0].get('[data-testid="constraint-entry-summary"]').text()).toBe(
      '张伟 · 周三 第2节',
    )
    await wrapper.get('[data-testid="constraint-entry-add"]').trigger('click')
    expect(wrapper.emitted('add-entry')).toBeTruthy()
  })

  it('其余目标型也是条目列表，不再用单值表单', () => {
    const wrapper = mountPanel(
      {},
      row({ code: 'COURSE_NOT_IN_EVENING', name: '课程不排晚自习', params: COURSE_PARAMS }),
    )
    expect(wrapper.find('[data-testid="constraint-entry-list"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="constraint-param-form"]').exists()).toBe(false)
  })

  it('阈值渲染全局默认 + 对象例外面板，不出现通用条目卡片', () => {
    const wrapper = mountPanel(
      {},
      row({
        code: 'TEACHER_MAX_PER_DAY',
        name: '教师一天最多 N 节',
        params: THRESHOLD_PARAMS,
        ...THRESHOLD_SCOPE,
        threshold: { globalId: null, globalValue: 6, exceptions: [] },
      }),
    )
    expect(wrapper.find('[data-testid="constraint-threshold-panel"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="constraint-threshold-global"]').text()).toContain('全局默认')
    expect(wrapper.find('[data-testid="constraint-entry-add"]').exists()).toBe(false)
  })

  it('阈值面板的「添加例外」回传给页面', async () => {
    const wrapper = mountPanel(
      {},
      row({
        code: 'TEACHER_MAX_PER_DAY',
        params: THRESHOLD_PARAMS,
        ...THRESHOLD_SCOPE,
        threshold: { globalId: null, globalValue: null, exceptions: [] },
      }),
    )
    await wrapper.get('[data-testid="constraint-threshold-add-exception"]').trigger('click')
    expect(wrapper.emitted('add-threshold-exception')).toBeTruthy()
  })

  it('偏好时段说明同教师多条按「或」聚合', () => {
    const wrapper = mountPanel(
      {},
      row({ code: 'TEACHER_PREFERRED_SLOT', name: '教师偏好时段', params: TEACHER_PARAMS }),
    )
    expect(wrapper.get('[data-testid="constraint-slot-note"]').text()).toContain('命中任一时段即满足')
  })

  it('开关型说明启用即一条规则，不出现条目区', () => {
    const wrapper = mountPanel({}, row({ code: 'TEACHER_CONFLICT', name: '教师不冲突' }))
    expect(wrapper.get('[data-testid="constraint-switch-note"]').text()).toContain('开关类型')
    expect(wrapper.find('[data-testid="constraint-entry"]').exists()).toBe(false)
  })

  it('服务端类型级错误显示在面板顶部', () => {
    const wrapper = mountPanel(
      { typeErrors: ['该类型没有参数，不能配置条目'] },
      row({ code: 'TEACHER_CONFLICT' }),
    )
    expect(wrapper.get('[data-testid="constraint-type-error"]').text()).toContain(
      '该类型没有参数',
    )
  })
})
