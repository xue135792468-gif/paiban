import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConstraintThresholdPanel from './ConstraintThresholdPanel.vue'
import type { ParamDef, TypeDraft } from './types'

const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师（留空表示全局默认）', type: 'ID', required: false },
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12, defaultValue: 4 },
]

const TEACHERS = [
  { id: 1, name: '张伟' },
  { id: 2, name: '李娜' },
]

function row(
  threshold: TypeDraft['threshold'],
  overrides: Partial<TypeDraft> = {},
): TypeDraft {
  return {
    code: 'TEACHER_MAX_PER_DAY',
    name: '教师一天最多 N 节',
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: THRESHOLD_PARAMS,
    thresholdObjectField: 'teacherId',
    thresholdValueField: 'maxPerDay',
    entries: [],
    threshold,
    enabled: true,
    hard: false,
    weight: 60,
    ...overrides,
  }
}

function mountPanel(rowValue: TypeDraft, props: Record<string, unknown> = {}) {
  return mount(ConstraintThresholdPanel, {
    props: {
      row: rowValue,
      editable: true,
      objectOptions: TEACHERS,
      errors: {},
      ...props,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConstraintThresholdPanel', () => {
  it('全局默认一行：显示值并可删除', async () => {
    const wrapper = mountPanel(row({ globalId: 1, globalValue: 6, exceptions: [] }))
    const globalRow = wrapper.get('[data-testid="constraint-threshold-global"]')
    expect(globalRow.text()).toContain('全局默认')
    expect(
      (globalRow.get('[data-testid="constraint-threshold-global-value"] input').element as HTMLInputElement)
        .value,
    ).toBe('6')

    await globalRow.get('[data-testid="constraint-threshold-global-remove"]').trigger('click')
    expect(wrapper.emitted('set-global')?.[0]).toEqual([null])
  })

  it('没有全局默认时说清「未列出的对象不设上限」，并给添加入口', async () => {
    const wrapper = mountPanel(row({ globalId: null, globalValue: null, exceptions: [] }))
    expect(wrapper.get('[data-testid="constraint-threshold-no-global-hint"]').text()).toContain(
      '不设上限',
    )
    await wrapper.get('[data-testid="constraint-threshold-add-global"]').trigger('click')
    expect(wrapper.emitted('add-global')).toBeTruthy()
    // 没有全局与没有例外叠加时，空状态也要说明清楚。
    expect(wrapper.get('[data-testid="constraint-threshold-empty"]').text()).toContain('还没有对象例外')
  })

  it('例外行显示对象、阈值与「覆盖全局 N」标签', () => {
    const wrapper = mountPanel(
      row({
        globalId: 1,
        globalValue: 6,
        exceptions: [{ key: 'a', id: 2, objectId: 1, value: 3 }],
      }),
    )
    const exceptions = wrapper.findAll('[data-testid="constraint-threshold-exception"]')
    expect(exceptions).toHaveLength(1)
    expect(exceptions[0].get('[data-testid="constraint-threshold-override"]').text()).toBe(
      '覆盖全局 6',
    )
    expect(
      (exceptions[0].get('[data-testid="constraint-threshold-value"] input').element as HTMLInputElement)
        .value,
    ).toBe('3')
  })

  it('只有例外没有全局时，例外行标记「仅该教师」而不是覆盖谁', () => {
    const wrapper = mountPanel(
      row({ globalId: null, globalValue: null, exceptions: [{ key: 'a', id: null, objectId: 1, value: 3 }] }),
    )
    const exception = wrapper.get('[data-testid="constraint-threshold-exception"]')
    expect(exception.find('[data-testid="constraint-threshold-override"]').exists()).toBe(false)
    expect(exception.get('[data-testid="constraint-threshold-no-global-mark"]').text()).toContain('教师')
  })

  it('添加与删除例外都交给页面处理', async () => {
    const wrapper = mountPanel(
      row({ globalId: 1, globalValue: 6, exceptions: [{ key: 'a', id: null, objectId: 1, value: 3 }] }),
    )
    await wrapper.get('[data-testid="constraint-threshold-add-exception"]').trigger('click')
    expect(wrapper.emitted('add-exception')).toBeTruthy()

    await wrapper.get('[data-testid="constraint-threshold-remove"]').trigger('click')
    expect(wrapper.emitted('remove-exception')?.[0]).toEqual(['a'])
  })

  it('只读时不给任何修改入口', () => {
    const wrapper = mountPanel(
      row({ globalId: 1, globalValue: 6, exceptions: [{ key: 'a', id: null, objectId: 1, value: 3 }] }),
      { editable: false },
    )
    expect(wrapper.find('[data-testid="constraint-threshold-add-exception"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="constraint-threshold-remove"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="constraint-threshold-global-remove"]').exists()).toBe(false)
  })

  it('服务端「第N条」错误落在同一面板上', () => {
    const wrapper = mountPanel(
      row({
        globalId: 1,
        globalValue: 6,
        exceptions: [{ key: 'a', id: null, objectId: 1, value: 3 }],
      }),
      { errors: { 2: ['参数「每天最多节数」不能大于 12'] } },
    )
    expect(wrapper.findAll('[data-testid="constraint-threshold-error"]').map((item) => item.text())).toEqual([
      '参数「每天最多节数」不能大于 12',
    ])
  })
})
