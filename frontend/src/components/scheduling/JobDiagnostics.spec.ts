import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import JobDiagnostics from './JobDiagnostics.vue'

const DIAGNOSTICS = [
  {
    teacherId: 9,
    teacherName: '张老师',
    hardCount: 2,
    messages: ['张老师 在 周一第1节 不排课', '张老师 单日排了 3 节，超过上限 2'],
    gradeIds: [1, 2],
    classNames: ['高一1班', '高二1班'],
  },
]

describe('排课失败诊断', () => {
  it('展示教师、硬冲突数与原因', () => {
    const wrapper = mount(JobDiagnostics, {
      props: { diagnostics: DIAGNOSTICS },
      global: { plugins: [ElementPlus] },
    })

    const row = wrapper.get('[data-testid="job-diagnostic"]')
    expect(row.text()).toContain('张老师')
    expect(row.text()).toContain('2 处硬冲突')
    expect(row.text()).toContain('张老师 在 周一第1节 不排课')
    expect(row.text()).toContain('高一1班、高二1班')
    wrapper.unmount()
  })

  it('点击连带重排按钮发出 rerun 事件', async () => {
    const wrapper = mount(JobDiagnostics, {
      props: { diagnostics: DIAGNOSTICS },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('[data-testid="rerun-diagnosed-grades"]').trigger('click')

    expect(wrapper.emitted('rerun')).toHaveLength(1)
    wrapper.unmount()
  })
})
