import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import RuleCopyButton from './RuleCopyButton.vue'
import type { CopyRuleIssue, CopyRulesResult } from '@/api/rules'

const semesters = [
  { id: 1, name: '2025 秋', current: true },
  { id: 2, name: '2025 春' },
  { id: 3, name: '2024 秋' },
]

function mountButton(copy = vi.fn(), targetSemesterId: number | null = 2): VueWrapper {
  return mount(RuleCopyButton, {
    props: { semesters, targetSemesterId, baseVersion: 4, copy },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

async function open(wrapper: VueWrapper) {
  await wrapper.get('[data-testid="rule-copy-open"]').trigger('click')
  await flushPromises()
}

function select(wrapper: VueWrapper, testId: string) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === testId)
  if (!found) throw new Error(`select not found: ${testId}`)
  return found
}

async function pickSource(wrapper: VueWrapper, value: number) {
  const component = select(wrapper, 'rule-copy-source')
  component.vm.$emit('update:modelValue', value)
  component.vm.$emit('change', value)
  await flushPromises()
}

function issue(index: number): CopyRuleIssue {
  return {
    type: 'TEACHER_UNAVAILABLE',
    typeName: '教师禁排时段',
    groupKey: `copy-${index}`,
    reason: `教师已删除：#${index}`,
  }
}

describe('RuleCopyButton: copy a past semester with confirmation', () => {
  it('offers every other semester and excludes the target', async () => {
    const wrapper = mountButton()
    await open(wrapper)

    const labels = select(wrapper, 'rule-copy-source')
      .findAllComponents({ name: 'ElOption' })
      .map((component) => String(component.props('label')))

    expect(labels).toEqual(['2025 秋（当前）', '2024 秋'])
    wrapper.unmount()
  })

  it('warns before replacing and keeps confirm disabled until a source is picked', async () => {
    const wrapper = mountButton()
    await open(wrapper)

    expect(wrapper.get('[data-testid="rule-copy-warning"]').text())
      .toContain('当前学期的非系统规则会被删除')
    expect(wrapper.get('[data-testid="rule-copy-confirm"]').attributes('disabled')).toBeDefined()

    await pickSource(wrapper, 1)
    expect(wrapper.get('[data-testid="rule-copy-confirm"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('calls copy and shows the copied count plus every issue reason', async () => {
    const result: CopyRulesResult = {
      copiedCards: 2,
      disabledCards: 1,
      issues: [issue(1)],
    }
    const copy = vi.fn().mockResolvedValue(result)
    const wrapper = mountButton(copy)
    await open(wrapper)
    await pickSource(wrapper, 1)

    await wrapper.get('[data-testid="rule-copy-confirm"]').trigger('click')
    await flushPromises()

    expect(copy).toHaveBeenCalledWith(1)
    expect(wrapper.get('[data-testid="rule-copy-result"]').text())
      .toContain('已复制 2 张规则卡，其中 1 张本学期用不上。')
    expect(wrapper.get('[data-testid="rule-copy-issue"]').text())
      .toContain('教师禁排时段：教师已删除：#1')
    expect(wrapper.emitted('done')?.[0]).toEqual([result])
    wrapper.unmount()
  })

  it('caps the issue list at eight entries and counts the rest', async () => {
    const copy = vi.fn().mockResolvedValue({
      copiedCards: 9,
      disabledCards: 9,
      issues: Array.from({ length: 9 }, (_, index) => issue(index)),
    })
    const wrapper = mountButton(copy)
    await open(wrapper)
    await pickSource(wrapper, 1)

    await wrapper.get('[data-testid="rule-copy-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[data-testid="rule-copy-issue"]')).toHaveLength(8)
    expect(wrapper.get('[data-testid="rule-copy-issue-more"]').text()).toContain('等 1 条')
    wrapper.unmount()
  })

  it('shows the server message and keeps the dialog when the copy fails', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    const copy = vi.fn().mockRejectedValue(new Error('network down'))
    const wrapper = mountButton(copy)
    await open(wrapper)
    await pickSource(wrapper, 1)

    await wrapper.get('[data-testid="rule-copy-confirm"]').trigger('click')
    await flushPromises()

    expect(error).toHaveBeenCalledWith('复制规则失败')
    expect(wrapper.find('[data-testid="rule-copy-result"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="rule-copy-dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('disables the entry without a target semester', () => {
    const wrapper = mountButton(vi.fn(), null)
    expect(wrapper.get('[data-testid="rule-copy-open"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
