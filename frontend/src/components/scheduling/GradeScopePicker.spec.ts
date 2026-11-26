import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import GradeScopePicker from './GradeScopePicker.vue'

const GRADES = [
  { id: 1, name: '高一' },
  { id: 2, name: '高二' },
]

function mountPicker(modelValue: number[], partialAllowed = true) {
  return mount(GradeScopePicker, {
    props: { modelValue, grades: GRADES, partialAllowed },
    global: { plugins: [ElementPlus] },
  })
}

describe('按年级排课的年级选择器', () => {
  it('默认全选时提示本次排全部年级', () => {
    const wrapper = mountPicker([1, 2])

    expect(wrapper.get('[data-testid="grade-scope-hint"]').text()).toContain('全部 2 个年级')
    expect(wrapper.findComponent({ name: 'ElSelect' }).props('multiple')).toBe(true)
    wrapper.unmount()
  })

  it('只选一个年级且允许局部重排时提示其他年级保持不动', () => {
    const wrapper = mountPicker([1], true)

    expect(wrapper.get('[data-testid="grade-scope-hint"]').text()).toContain(
      '本次排 1 个年级 / 其他 1 个年级保持不动',
    )
    wrapper.unmount()
  })

  it('其他年级未发布时选择部分年级会给出警告且不再承诺保持不动', () => {
    const wrapper = mountPicker([1], false)

    const hint = wrapper.get('[data-testid="grade-scope-hint"]')
    expect(hint.classes()).toContain('is-warning')
    expect(hint.text()).toContain('其他年级尚未发布课表')
    expect(hint.text()).toContain('仅支持全校排课')
    expect(hint.text()).not.toContain('保持不动')
    wrapper.unmount()
  })

  it('其他年级未发布时全选仍提示本次排全部年级', () => {
    const wrapper = mountPicker([1, 2], false)

    const hint = wrapper.get('[data-testid="grade-scope-hint"]')
    expect(hint.classes()).not.toContain('is-warning')
    expect(hint.text()).toContain('本次排全部 2 个年级')
    wrapper.unmount()
  })

  it('多选变化会向上回传年级 id', async () => {
    const wrapper = mountPicker([1, 2])

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', [2])
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([[2]])
    wrapper.unmount()
  })
})
