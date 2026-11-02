import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ElementPlus from 'element-plus'
import WeeklyClassScope from './WeeklyClassScope.vue'

describe('weekly class selection', () => {
  it('shows all-school explicitly, and empty selected scope stays selected', async () => {
    const wrapper = mount(WeeklyClassScope, {
      props: { entry: { key: 'a', id: null, values: { classScope: 'ALL', classIds: [] } }, disabled: false },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).toContain('包括以后新增的班级')
    await wrapper.setProps({ entry: { key: 'a', id: null, values: { classScope: 'SELECTED', classIds: [] } } })
    expect(wrapper.find('[data-testid="constraint-entry-class"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="constraint-entry-class"]').text()).toContain('请选择至少一个班级')
    expect(wrapper.text()).not.toContain('包括以后新增的班级')
  })
})
