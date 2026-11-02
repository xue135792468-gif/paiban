import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import OptimizationLaunchDialog from './OptimizationLaunchDialog.vue'

async function mountDialog(overrides: Record<string, unknown> = {}) {
  const wrapper = mount(OptimizationLaunchDialog, {
    props: {
      modelValue: true,
      sourceDraftName: '原稿 A',
      sourceDraftId: 42,
      lockedCount: 2,
      lessonCount: 7,
      ...overrides,
    },
    global: { plugins: [ElementPlus] },
  })
  await flushPromises()
  return wrapper
}

describe('OptimizationLaunchDialog', async () => {
  it('默认名称带来源草稿，标明继承的锁定节数', async () => {
    const wrapper = await mountDialog()

    const name = wrapper.get('[data-testid=optimization-draft-name]')
    expect((name.element as HTMLInputElement).value).toBe('基于「原稿 A」优化')
    expect(wrapper.text()).toContain('锁定 2 节会继承')
    expect(wrapper.get('[data-testid=optimization-launch-dialog]').text()).toContain('原稿保留不变')
  })

  it('确认后提交名称与求解秒数', async () => {
    const wrapper = await mountDialog()

    await wrapper.get('[data-testid=optimization-launch-confirm]').trigger('click')

    expect(wrapper.emitted('submit')).toEqual([
      [{ draftName: '基于「原稿 A」优化', maxSeconds: 60 }],
    ])
  })

  it('全部锁定时禁用发起并说明原因', async () => {
    const wrapper = await mountDialog({ lockedCount: 7, lessonCount: 7 })

    expect(wrapper.get('[data-testid=optimization-launch-confirm]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid=optimization-all-locked]').text()).toContain(
      '全部课节已锁定，没有可优化的课程',
    )
  })

  it('提交中按钮 loading 且不能重复提交', async () => {
    const wrapper = await mountDialog({ starting: true })

    const confirm = wrapper.get('[data-testid=optimization-launch-confirm]')
    expect(confirm.attributes('disabled')).toBeDefined()
    await confirm.trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('开始失败时展示服务端原因', async () => {
    const wrapper = await mountDialog({ submitError: '全部课节已锁定，没有可优化的课程' })

    expect(wrapper.get('[data-testid=optimization-submit-error]').text()).toContain(
      '全部课节已锁定，没有可优化的课程',
    )
  })
})
