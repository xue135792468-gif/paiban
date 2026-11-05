import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import GradeScopePicker from './GradeScopePicker.vue'

const GRADES = [
  { id: 1, name: '高一' },
  { id: 2, name: '高二' },
]

function mountPicker(modelValue: number[]) {
  return mount(GradeScopePicker, {
    props: { modelValue, grades: GRADES },
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

  it('只选一个年级时提示其他年级保持不动', () => {
    const wrapper = mountPicker([1])

    expect(wrapper.get('[data-testid="grade-scope-hint"]').text()).toContain(
      '本次排 1 个年级 / 其他 1 个年级保持不动',
    )
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
