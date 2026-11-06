import { describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RuleForm from './RuleForm.vue'
import {
  cardFromInputs,
  type PeriodOption,
  type RuleCard,
} from '@/rules/compiler'

const periods: PeriodOption[] = [
  ...[1, 2, 3, 4].map((periodNo) => ({ dayOfWeek: 5, periodNo, startTime: '08:00:00' })),
  ...[5, 6, 7, 8].map((periodNo) => ({ dayOfWeek: 5, periodNo, startTime: '14:00:00' })),
]

const teachers = [{ id: 7, name: '王老师' }]
const classes = [{ id: 1, name: '九（1）班' }]
const courses = [
  { id: 9, name: '数学' },
  { id: 10, name: '体育' },
]

function mountForm(initial: RuleCard | null = null) {
  return mount(RuleForm, {
    props: {
      initial,
      teachers,
      classes,
      courses,
      days: [5],
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

/** Labels rendered inside the requirement select (type options are UPPER_CASE codes). */
function typeLabels(wrapper: VueWrapper): string[] {
  return wrapper
    .findAllComponents({ name: 'ElOption' })
    .filter((component) => /^[A-Z_]+$/.test(String(component.props('value'))))
    .map((component) => String(component.props('label')))
}

function precedes(first: Element, second: Element): boolean {
  return Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING)
}

describe('RuleSentenceEditor: subject first, then time, then requirement', () => {
  it('renders 谁 / 什么课 before 什么时候 and 要求 last', () => {
    const wrapper = mountForm()
    const subject = wrapper.get('[data-testid="rule-subject"]').element
    const day = wrapper.get('[data-testid="rule-day"]').element
    const type = wrapper.get('[data-testid="rule-type"]').element
    expect(precedes(subject, day)).toBe(true)
    expect(precedes(day, type)).toBe(true)
    wrapper.unmount()
  })

  it('groups subject options by people/classes and courses', () => {
    const wrapper = mountForm()
    const subject = select(wrapper, 'rule-subject')
    const labels = subject
      .findAllComponents({ name: 'ElOption' })
      .map((component) => String(component.props('label')))
    expect(labels).toContain('全校')
    expect(labels).toContain('所有教师')
    expect(labels).toContain('班级 · 九（1）班')
    expect(labels).toContain('教师 · 王老师')
    expect(labels).toContain('全部课程')
    expect(labels).toContain('课程 · 数学')
    wrapper.unmount()
  })

  it('offers only teacher requirements after 教师', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    const labels = typeLabels(wrapper)
    expect(labels).toContain('不上课')
    expect(labels).toContain('每天最多 N 节')
    expect(labels).toContain('连续不超过 N 节')
    expect(labels).toContain('不排第一节')
    expect(labels).toContain('固定无课日')
    expect(labels).toContain('偏好时段')
    expect(labels).not.toContain('不排课')
    expect(labels).not.toContain('连堂 N 节')
    expect(labels).not.toContain('分散到不同天')
    expect(labels).not.toContain('不在最后一节')
    expect(labels).not.toContain('排在上午')
    expect(labels).not.toContain('不空堂')
    wrapper.unmount()
  })

  it('omits 不上课 for 所有教师, which cannot apply to one teacher', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'all-teachers')
    const labels = typeLabels(wrapper)
    expect(labels).toEqual(['连续不超过 N 节'])
    wrapper.unmount()
  })

  it('offers only course requirements after 课程', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'course:9')
    const labels = typeLabels(wrapper)
    expect(labels).toContain('不排课')
    expect(labels).toContain('每天最多 N 节')
    expect(labels).toContain('每天最多 1 节')
    expect(labels).toContain('连堂 N 节')
    expect(labels).toContain('分散到不同天')
    expect(labels).toContain('不在最后一节')
    expect(labels).toContain('排在上午')
    expect(labels).not.toContain('不空堂')
    expect(labels).not.toContain('固定无课日')
    wrapper.unmount()
  })

  it('hides requirements that need one course for 全部课程', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'all-courses')
    const labels = typeLabels(wrapper)
    expect(labels).toContain('每天最多 1 节')
    expect(labels).toContain('分散到不同天')
    expect(labels).toContain('不在最后一节')
    expect(labels).not.toContain('连堂 N 节')
    expect(labels).not.toContain('排在上午')
    expect(labels).not.toContain('不排课')
    wrapper.unmount()
  })

  it('keeps only 不上课 and 不空堂 for 全校', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'school')
    expect(typeLabels(wrapper)).toEqual(['不上课', '不空堂'])
    wrapper.unmount()
  })

  it('previews 王老师 周五第 5、6 节 不上课 and folds only full slices', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-type', 'TEACHER_UNAVAILABLE')
    await setSelect(wrapper, 'rule-day', 5)
    await setPeriods(wrapper, [5, 6])
    const preview = () => wrapper.find('[data-testid="rule-preview"]').text()
    expect(preview()).toContain('王老师 周五第 5、6 节 不上课')

    await setPeriods(wrapper, [5, 6, 7, 8])
    expect(preview()).toContain('王老师 周五下午 不上课')
    wrapper.unmount()
  })

  it('drops a requirement that no longer fits the new subject', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-type', 'TEACHER_UNAVAILABLE')
    await setSelect(wrapper, 'rule-subject', 'all-courses')
    expect(select(wrapper, 'rule-type').props('modelValue')).toBeNull()
    expect(wrapper.find('[data-testid="rule-amount"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('infers the subject and requirement of a stored card', () => {
    const initial = cardFromInputs([{
      type: 'TEACHER_UNAVAILABLE',
      teacherId: 7,
      dayOfWeek: 5,
      periodNos: [5, 6],
    }])
    const wrapper = mountForm(initial)
    expect(select(wrapper, 'rule-subject').props('modelValue')).toBe('teacher:7')
    expect(select(wrapper, 'rule-type').props('modelValue')).toBe('TEACHER_UNAVAILABLE')
    wrapper.unmount()
  })
})
