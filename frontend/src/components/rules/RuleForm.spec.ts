import { describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RuleForm from './RuleForm.vue'
import { cardFromInputs, type PeriodOption, type RuleCard } from '@/rules/compiler'

const periods: PeriodOption[] = [1, 2].flatMap((day) => [
  { dayOfWeek: day, periodNo: 1, startTime: '08:00:00' },
  { dayOfWeek: day, periodNo: 2, startTime: '09:00:00' },
  { dayOfWeek: day, periodNo: 3, startTime: '14:00:00' },
  { dayOfWeek: day, periodNo: 4, startTime: '15:00:00' },
])

const teachers = [
  { id: 7, name: '王老师' },
  { id: 8, name: '李老师' },
]
const classes = [
  { id: 1, name: '九（1）班' },
  { id: 2, name: '九（2）班' },
]
const courses = [
  { id: 9, name: '数学' },
  { id: 10, name: '班会' },
]

function mountForm(initial: RuleCard | null = null) {
  return mount(RuleForm, {
    props: {
      initial,
      teachers,
      classes,
      courses,
      days: [1, 2],
      periods,
    },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

function select(wrapper: VueWrapper, testId: string) {
  const found = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((component) => component.attributes('data-testid') === testId)
  if (!found) throw new Error(`select not found: ${testId}`)
  return found
}

async function setSelect(wrapper: VueWrapper, testId: string, value: unknown) {
  const component = select(wrapper, testId)
  component.vm.$emit('update:modelValue', value)
  component.vm.$emit('change', value)
  await flushPromises()
}

async function setPeriods(wrapper: VueWrapper, values: number[]) {
  wrapper.findComponent({ name: 'ElCheckboxGroup' }).vm.$emit('update:modelValue', values)
  await flushPromises()
}

describe('RuleForm: subject first, live sentence and exceptions', () => {
  it('starts with 谁 / 什么课 first and the requirement disabled', () => {
    const wrapper = mountForm()
    expect(select(wrapper, 'rule-subject').props('filterable')).toBe(true)
    expect(select(wrapper, 'rule-type').props('disabled')).toBe(true)
    expect(select(wrapper, 'rule-type').props('placeholder')).toBe('先选谁 / 什么课')
    expect(wrapper.find('[data-testid="rule-day"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="rule-missing"]').text()).toContain('选择要求')
    expect(wrapper.find('[data-testid="rule-submit"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('grows the sentence while the slots are filled in speaking order', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-type', 'TEACHER_UNAVAILABLE')
    await setSelect(wrapper, 'rule-day', 1)
    await setPeriods(wrapper, [3])
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('王老师 周一第 3 节 不上课')
    expect(wrapper.find('[data-testid="rule-submit"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('folds a full afternoon into 下午 in the preview', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-type', 'TEACHER_UNAVAILABLE')
    await setSelect(wrapper, 'rule-day', 2)
    await setPeriods(wrapper, [3, 4])
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('王老师 周二下午 不上课')
    wrapper.unmount()
  })

  it('offers 除了班级 for a school-scope 不排 sentence and previews the exception', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'school')
    await setSelect(wrapper, 'rule-type', 'CLASS_UNAVAILABLE')
    await setSelect(wrapper, 'rule-day', 1)
    await setPeriods(wrapper, [3])
    expect(wrapper.find('[data-testid="rule-exceptions"]').exists()).toBe(true)
    expect(select(wrapper, 'rule-exceptions').props('multiple')).toBe(true)

    await setSelect(wrapper, 'rule-exceptions', [2])
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('全校 周一第 3 节 不上课，除了 九（2）班')
    wrapper.unmount()
  })

  it('lets a card hold up to three sentences and removes one', async () => {
    const wrapper = mountForm()
    expect(wrapper.findAll('[data-testid^="rule-sentence-block-"]')).toHaveLength(1)
    await wrapper.find('[data-testid="rule-add-sentence"]').trigger('click')
    await wrapper.find('[data-testid="rule-add-sentence"]').trigger('click')
    expect(wrapper.findAll('[data-testid^="rule-sentence-block-"]')).toHaveLength(3)
    expect(wrapper.find('[data-testid="rule-add-sentence"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-testid="rule-sentence-remove-1"]').trigger('click')
    expect(wrapper.findAll('[data-testid^="rule-sentence-block-"]')).toHaveLength(2)
    wrapper.unmount()
  })

  it('submits the sentence with its stored key', async () => {
    const initial = cardFromInputs([{
      type: 'CLASS_UNAVAILABLE',
      dayOfWeek: 1,
      periodNos: [3],
    }])
    const wrapper = mountForm(initial)
    await wrapper.find('[data-testid="rule-submit"]').trigger('click')
    const payload = wrapper.emitted('submit')?.[0]?.[0] as { key: string | null }[]
    expect(payload).toHaveLength(1)
    expect(payload[0].key).toBe(initial.sentences[0].key)
    wrapper.unmount()
  })
})
