import { describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RuleForm from './RuleForm.vue'
import { cardFromInputs, type PeriodOption, type RuleCard } from '@/rules/compiler'

const periods: PeriodOption[] = [1, 2].flatMap((day) => [
  { dayOfWeek: day, periodNo: 1, section: 'MORNING' },
  { dayOfWeek: day, periodNo: 2, section: 'AFTERNOON' },
  { dayOfWeek: day, periodNo: 3, section: 'AFTERNOON' },
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

describe('RuleForm: searchable slots, live sentence and exceptions', () => {
  it('starts with every slot empty and asks for the requirement first', () => {
    const wrapper = mountForm()
    expect(select(wrapper, 'rule-type').props('filterable')).toBe(true)
    expect(select(wrapper, 'rule-type').props('placeholder')).toBe('选择要求')
    expect(wrapper.find('[data-testid="rule-day"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="rule-missing"]').text()).toContain('选择要求')
    expect(wrapper.find('[data-testid="rule-submit"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('makes 班级 and 哪些课 searchable once the sentence needs them', async () => {
    const initial = cardFromInputs([{
      type: 'COURSE_MAX_PER_DAY',
      classId: null,
      courseId: 9,
      amount: 2,
      strength: 'HARD',
    }])
    const wrapper = mountForm(initial)
    expect(select(wrapper, 'rule-course').props('filterable')).toBe(true)

    // 班级 target appears when the scope switches from 全校 to 班级.
    await setSelect(wrapper, 'rule-type', 'CLASS_MAX_PER_DAY')
    const group = wrapper
      .findAllComponents({ name: 'ElRadioGroup' })
      .find((component) => component.attributes('data-testid') === 'rule-scope')
    group?.vm.$emit('change', 'CLASS')
    await flushPromises()
    expect(select(wrapper, 'rule-class').props('filterable')).toBe(true)
    wrapper.unmount()
  })

  it('updates the sentence while the slots are being filled', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-type', 'CLASS_UNAVAILABLE')
    expect(select(wrapper, 'rule-day').props('filterable')).toBe(true)
    await setSelect(wrapper, 'rule-day', 1)
    await setPeriods(wrapper, [3])
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('全校 的 课 在 周一第 3 节 不排')
    expect(wrapper.find('[data-testid="rule-submit"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('offers 除了班级 for a school-scope 不排 sentence and previews the exception', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-type', 'CLASS_UNAVAILABLE')
    await setSelect(wrapper, 'rule-day', 1)
    await setPeriods(wrapper, [3])
    expect(wrapper.find('[data-testid="rule-exceptions"]').exists()).toBe(true)
    expect(select(wrapper, 'rule-exceptions').props('multiple')).toBe(true)

    await setSelect(wrapper, 'rule-exceptions', [2])
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('全校 的 课 在 周一第 3 节 不排，除了 九（2）班')
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
