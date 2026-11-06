import { describe, expect, it } from 'vitest'
import {
  cardFromInputs,
  toggleCard,
  type PeriodOption,
  type RuleInput,
  type RuleNames,
} from './compiler'
import {
  buildConflictContext,
  findRuleConflicts,
  formatConflictMessage,
  type ConflictContext,
} from './conflicts'

const teacherNames = new Map([[1, '王老师'], [2, '李老师']])
const courseNames = new Map([[9, '数学'], [10, '语文']])

const names: RuleNames = {
  teacher: (id) => teacherNames.get(id) ?? `教师${id}`,
  course: (id) => courseNames.get(id) ?? `课程${id}`,
  klass: (id) => `班级${id}`,
  teacherGroup: (id) => `教师组${id}`,
  courseGroup: (id) => `课程组${id}`,
  timeGroup: (id) => `时间组${id}`,
}

/** Weekdays 1–5, periods 1–7: enough for mornings, afternoons and last periods. */
const periods: PeriodOption[] = [1, 2, 3, 4, 5].flatMap((dayOfWeek) =>
  [1, 2, 3, 4, 5, 6, 7].map((periodNo) => ({ dayOfWeek, periodNo })))

function context(overrides: Partial<ConflictContext> = {}): ConflictContext {
  return {
    periods,
    names,
    teacherGroupMembers: new Map([[11, [1, 2]]]),
    courseGroupMembers: new Map([[21, [9]]]),
    timeGroupSlots: new Map([
      [31, [{ dayOfWeek: 5, periodNo: 5 }, { dayOfWeek: 5, periodNo: 6 }]],
      [32, [{ dayOfWeek: 5, periodNo: 2 }, { dayOfWeek: 5, periodNo: 3 }]],
    ]),
    allClassIds: [1, 2],
    ...overrides,
  }
}

function card(...inputs: RuleInput[]) {
  return cardFromInputs(inputs)
}

describe('findRuleConflicts', () => {
  it('finds a teacher group blocked by its own time group', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'TEACHER_UNAVAILABLE', teacherGroupId: 11, timeGroupId: 31 }),
      card({ type: 'TEACHER_PREFERRED_SLOT', teacherGroupId: 11, timeGroupId: 31 }),
    ], context())

    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].first.type).toBe('TEACHER_UNAVAILABLE')
    expect(conflicts[0].second.type).toBe('TEACHER_PREFERRED_SLOT')
    // The group is listed member by member, not by group name.
    expect(conflicts[0].members.map((member) => member.name)).toEqual(['王老师', '李老师'])
    expect(conflicts[0].slots).toEqual(['周五第5节', '周五第6节'])
  })

  it('finds a course group blocked by 排在上午', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'COURSE_UNAVAILABLE', courseGroupId: 21, timeGroupId: 32 }),
      card({ type: 'MAIN_SUBJECT_MORNING', courseGroupId: 21, amount: 4 }),
    ], context())

    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].first.type).toBe('COURSE_UNAVAILABLE')
    expect(conflicts[0].second.type).toBe('MAIN_SUBJECT_MORNING')
    expect(conflicts[0].members.map((member) => member.name)).toEqual(['数学'])
    expect(conflicts[0].slots).toEqual(['周五第2节', '周五第3节'])
  })

  it('stays quiet when either side is a soft (尽量) rule', () => {
    const softNegative = card({
      type: 'TEACHER_UNAVAILABLE', teacherGroupId: 11, timeGroupId: 31, strength: 'SOFT',
    })
    const hardPositive = card({
      type: 'TEACHER_PREFERRED_SLOT', teacherGroupId: 11, timeGroupId: 31,
    })
    expect(findRuleConflicts([softNegative, hardPositive], context())).toHaveLength(0)

    const softPositive = card({
      type: 'TEACHER_PREFERRED_SLOT', teacherGroupId: 11, timeGroupId: 31, strength: 'SOFT',
    })
    expect(findRuleConflicts([softNegative, softPositive], context())).toHaveLength(0)
  })

  it('ignores rules that never share a slot', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'TEACHER_UNAVAILABLE', teacherGroupId: 11, days: [5], periodNos: [5, 6] }),
      card({ type: 'TEACHER_PREFERRED_SLOT', teacherGroupId: 11, days: [5], periodNos: [3, 4] }),
    ], context())
    expect(conflicts).toHaveLength(0)
  })

  it('expands a fixed day off to every period of that weekday', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'TEACHER_DAY_OFF', teacherId: 1, days: [5] }),
      card({ type: 'TEACHER_PREFERRED_SLOT', teacherId: 1, days: [5], periodNos: [3] }),
    ], context())

    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].members.map((member) => member.name)).toEqual(['王老师'])
    expect(conflicts[0].slots).toEqual(['周五第3节'])
  })

  it('expands 不排第一节 to the first period of every weekday', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'TEACHER_NO_FIRST_PERIOD', teacherId: 1 }),
      card({ type: 'TEACHER_PREFERRED_SLOT', teacherId: 1, days: [1, 2], periodNos: [1] }),
    ], context())

    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].slots).toEqual(['周一第1节', '周二第1节'])
  })

  it('expands 不在最后一节 to each day’s last period', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'PE_LAST_PERIOD_AVOID', courseId: 9 }),
      card({ type: 'MAIN_SUBJECT_MORNING', courseId: 9, amount: 7 }),
    ], context())

    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].members.map((member) => member.name)).toEqual(['数学'])
    expect(conflicts[0].slots).toEqual([
      '周一第7节', '周二第7节', '周三第7节', '周四第7节', '周五第7节',
    ])
  })

  it('does not invent conflicts for empty or unknown groups', () => {
    const cards = [
      card({ type: 'TEACHER_UNAVAILABLE', teacherGroupId: 11, timeGroupId: 31 }),
      card({ type: 'TEACHER_PREFERRED_SLOT', teacherGroupId: 11, timeGroupId: 31 }),
    ]
    expect(findRuleConflicts(cards, context({
      teacherGroupMembers: new Map(),
      timeGroupSlots: new Map(),
    }))).toHaveLength(0)
  })

  it('ignores disabled cards', () => {
    const blocked = card({ type: 'TEACHER_UNAVAILABLE', teacherGroupId: 11, timeGroupId: 31 })
    const preferred = card({ type: 'TEACHER_PREFERRED_SLOT', teacherGroupId: 11, timeGroupId: 31 })
    expect(findRuleConflicts([toggleCard(blocked, false), preferred], context())).toHaveLength(0)
  })
})

describe('formatConflictMessage', () => {
  it('names both sentences, the affected members and the slots', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'TEACHER_DAY_OFF', teacherId: 1, days: [5] }),
      card({ type: 'TEACHER_PREFERRED_SLOT', teacherId: 1, days: [5], periodNos: [3] }),
    ], context())

    const message = formatConflictMessage(conflicts)
    expect(message).toContain('「王老师 周五 不排课」')
    expect(message).toContain('「王老师 偏好 周五第 3 节」')
    expect(message).toContain('受影响：王老师')
    expect(message).toContain('时段：周五第3节')
  })

  it('folds periods of one day and counts the rest', () => {
    const conflicts = findRuleConflicts([
      card({ type: 'TEACHER_DAY_OFF', teacherId: 1, days: [5] }),
      card({ type: 'TEACHER_PREFERRED_SLOT', teacherId: 1, days: [5], periodNos: [3, 4] }),
    ], context())
    expect(formatConflictMessage(conflicts)).toContain('时段：周五第3、4节')

    const many = Array.from({ length: 7 }, (_, index) => ({
      ...conflicts[0],
      first: { ...conflicts[0].first, sentence: `规则${index}` },
    }))
    expect(formatConflictMessage(many)).toContain('等 2 条')
  })
})

describe('buildConflictContext', () => {
  it('splits group members by kind and drops unusable time coordinates', () => {
    const built = buildConflictContext({
      periods,
      names,
      allClassIds: [1, 2],
      teacherGroups: [{ id: 11, members: [
        { kind: 'TEACHER', objectId: 1, dayOfWeek: null, periodNo: null },
        { kind: 'COURSE', objectId: 9, dayOfWeek: null, periodNo: null },
      ] }],
      courseGroups: [{ id: 21, members: [
        { kind: 'COURSE', objectId: 9, dayOfWeek: null, periodNo: null },
      ] }],
      timeGroups: [{ id: 31, members: [
        { kind: 'TIME', objectId: 0, dayOfWeek: 5, periodNo: 5 },
        { kind: 'TIME', objectId: 0, dayOfWeek: null, periodNo: null },
      ] }],
    })

    expect(built.teacherGroupMembers.get(11)).toEqual([1])
    expect(built.courseGroupMembers.get(21)).toEqual([9])
    expect(built.timeGroupSlots.get(31)).toEqual([{ dayOfWeek: 5, periodNo: 5 }])
    expect(built.allClassIds).toEqual([1, 2])
  })
})
