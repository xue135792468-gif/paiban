import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import RuleForm from './RuleForm.vue'
import {
  cardFromInputs,
  type PeriodOption,
  type RuleCard,
} from '@/rules/compiler'

const periods: PeriodOption[] = [1, 3, 5].flatMap((day) => [
  ...[1, 2, 3, 4].map((periodNo) => ({ dayOfWeek: day, periodNo, startTime: '08:00:00' })),
  ...[5, 6, 7, 8].map((periodNo) => ({ dayOfWeek: day, periodNo, startTime: '14:00:00' })),
])

const teachers = [{ id: 7, name: '王老师' }]
const classes = [{ id: 1, name: '九（1）班' }]
const courses = [
  { id: 9, name: '数学' },
  { id: 10, name: '体育' },
]

const teacherGroups = [{ id: 12, name: '教导主任' }]
const courseGroups = [{ id: 21, name: '技能课' }]

function mountForm(
  initial: RuleCard | null = null,
  options: {
    createTeacherGroup?: (name: string) => Promise<{ id: number; name: string } | null>
    createCourseGroup?: (name: string) => Promise<{ id: number; name: string } | null>
  } = {},
) {
  return mount(RuleForm, {
    props: {
      initial,
      teachers,
      classes,
      courses,
      days: [1, 3, 5],
      periods,
      teacherGroups,
      createTeacherGroup: options.createTeacherGroup,
      courseGroups,
      createCourseGroup: options.createCourseGroup,
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
    const day = wrapper.get('[data-testid="rule-days"]').element
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
    expect(labels).toContain('教师组 · 教导主任')
    expect(labels).toContain('班级 · 九（1）班')
    expect(labels).toContain('教师 · 王老师')
    expect(labels).toContain('全部课程')
    expect(labels).toContain('课程 · 数学')
    expect(labels).toContain('课程组 · 技能课')
    wrapper.unmount()
  })

  it('offers only teacher requirements after a teacher group', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher-group:12')
    const labels = typeLabels(wrapper)
    expect(labels).toContain('不上课')
    expect(labels).toContain('每天最多 N 节')
    expect(labels).toContain('固定无课日')
    expect(labels).not.toContain('不排课')
    expect(labels).not.toContain('连堂 N 节')
    expect(labels).not.toContain('不空堂')

    await setSelect(wrapper, 'rule-type', 'TEACHER_UNAVAILABLE')
    await setSelect(wrapper, 'rule-days', [5])
    await setPeriods(wrapper, [5, 6])
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('教导主任 周五第 5、6 节 不上课')
    wrapper.unmount()
  })

  it('creates and selects a new teacher group from the dropdown', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '数学组' } as never)
    const createTeacherGroup = vi.fn().mockResolvedValue({ id: 21, name: '数学组' })
    const wrapper = mountForm(null, { createTeacherGroup })

    await setSelect(wrapper, 'rule-subject', '__new_teacher_group__')
    await flushPromises()

    expect(prompt).toHaveBeenCalledTimes(1)
    expect(createTeacherGroup).toHaveBeenCalledWith('数学组')
    expect(select(wrapper, 'rule-subject').props('modelValue')).toBe('teacher-group:21')
    wrapper.unmount()
  })

  it('keeps the old subject when the new-group prompt is cancelled', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockRejectedValue('cancel')
    const createTeacherGroup = vi.fn()
    const wrapper = mountForm(null, { createTeacherGroup })

    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-subject', '__new_teacher_group__')
    await flushPromises()

    expect(createTeacherGroup).not.toHaveBeenCalled()
    expect(select(wrapper, 'rule-subject').props('modelValue')).toBe('teacher:7')
    wrapper.unmount()
  })

  it('offers only course requirements after a course group', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'course-group:21')
    const labels = typeLabels(wrapper)
    expect(labels).toContain('不排课')
    expect(labels).toContain('每天最多 N 节')
    expect(labels).toContain('每天最多 1 节')
    expect(labels).toContain('连堂 N 节')
    expect(labels).toContain('分散到不同天')
    expect(labels).toContain('不在最后一节')
    expect(labels).toContain('排在上午')
    expect(labels).not.toContain('不上课')
    expect(labels).not.toContain('固定无课日')
    expect(labels).not.toContain('不空堂')
    wrapper.unmount()
  })

  it('previews a course-group sentence with the group name', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'course-group:21')
    await setSelect(wrapper, 'rule-type', 'COURSE_MAX_PER_DAY')
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('技能课 每天最多 2 节')
    wrapper.unmount()
  })

  it('creates and selects a new course group from the dropdown', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '理科组' } as never)
    const createCourseGroup = vi.fn().mockResolvedValue({ id: 22, name: '理科组' })
    const wrapper = mountForm(null, { createCourseGroup })

    await setSelect(wrapper, 'rule-subject', '__new_course_group__')
    await flushPromises()

    expect(prompt).toHaveBeenCalledTimes(1)
    expect(createCourseGroup).toHaveBeenCalledWith('理科组')
    expect(select(wrapper, 'rule-subject').props('modelValue')).toBe('course-group:22')
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
    expect(labels).toEqual(['每天最多 N 节', '连续不超过 N 节'])
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
    expect(labels).toContain('每天最多 N 节')
    expect(labels).toContain('每天最多 1 节')
    expect(labels).toContain('分散到不同天')
    expect(labels).toContain('不在最后一节')
    expect(labels).not.toContain('连堂 N 节')
    expect(labels).not.toContain('排在上午')
    expect(labels).not.toContain('不排课')
    wrapper.unmount()
  })

  it('keeps 不上课, 每天最多 N 节 and 不空堂 for 全校', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'school')
    expect(typeLabels(wrapper)).toEqual(['不上课', '每天最多 N 节', '不空堂'])
    wrapper.unmount()
  })

  it('keeps both class-scoped requirement types after 班级', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'class:1')
    expect(typeLabels(wrapper)).toEqual(['不上课', '每天最多 N 节'])
    wrapper.unmount()
  })

  it('previews 王老师 周五第 5、6 节 不上课 and folds only full slices', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-type', 'TEACHER_UNAVAILABLE')
    await setSelect(wrapper, 'rule-days', [5])
    await setPeriods(wrapper, [5, 6])
    const preview = () => wrapper.find('[data-testid="rule-preview"]').text()
    expect(preview()).toContain('王老师 周五第 5、6 节 不上课')

    await setPeriods(wrapper, [5, 6, 7, 8])
    expect(preview()).toContain('王老师 周五下午 不上课')
    wrapper.unmount()
  })

  it('previews several weekdays as one sentence and folds a shared slice', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-type', 'TEACHER_UNAVAILABLE')
    await setSelect(wrapper, 'rule-days', [3, 1])
    await setPeriods(wrapper, [5, 6, 7, 8])
    const preview = () => wrapper.find('[data-testid="rule-preview"]').text()
    expect(preview()).toContain('王老师 周一、周三下午 不上课')

    await setPeriods(wrapper, [5, 6])
    expect(preview()).toContain('王老师 周一、周三第 5、6 节 不上课')
    wrapper.unmount()
  })

  it('keeps 固定无课日 to weekdays only and joins several days', async () => {
    const wrapper = mountForm()
    await setSelect(wrapper, 'rule-subject', 'teacher:7')
    await setSelect(wrapper, 'rule-type', 'TEACHER_DAY_OFF')
    await setSelect(wrapper, 'rule-days', [3, 1])
    expect(wrapper.find('[data-testid="rule-period-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="rule-preview"]').text())
      .toContain('王老师 周一、周三 不排课')
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
      days: [5],
      periodNos: [5, 6],
    }])
    const wrapper = mountForm(initial)
    expect(select(wrapper, 'rule-subject').props('modelValue')).toBe('teacher:7')
    expect(select(wrapper, 'rule-type').props('modelValue')).toBe('TEACHER_UNAVAILABLE')
    wrapper.unmount()
  })
})
