import { describe, expect, it } from 'vitest'
import type {
  ConstraintConfigDto,
  ConstraintConfigRequest,
  ConstraintEntryDto,
} from '@/components/constraints/types'
import {
  CLASS_GAP_MINIMIZED,
  CLASS_MAX_PER_DAY,
  CLASS_UNAVAILABLE,
  CONSECUTIVE_LESSONS,
  COURSE_MAX_PER_DAY,
  COURSE_SPREAD,
  COURSE_UNAVAILABLE,
  MAIN_SUBJECT_MORNING,
  PE_LAST_PERIOD_AVOID,
  RULE_GROUPS,
  RULE_SPECS,
  RULE_TYPES,
  SAME_COURSE_ONCE_PER_DAY,
  TEACHER_DAY_OFF,
  TEACHER_MAX_CONSECUTIVE,
  TEACHER_MAX_PER_DAY,
  TEACHER_NO_FIRST_PERIOD,
  TEACHER_PREFERRED_SLOT,
  TEACHER_UNAVAILABLE,
  applyCardInputs,
  cardFromInputs,
  cardMatches,
  cardTarget,
  cardsFromState,
  compileRule,
  configsFromCards,
  describeCard,
  describeRule,
  duplicateCard,
  missingParts,
  otherRuleCount,
  sentenceProblem,
  timeLabel,
  toggleCard,
  unrepresentedGroupCount,
  type CardSentenceInput,
  type PeriodOption,
  type RuleCard,
  type RuleNames,
} from './compiler'

function entry(
  id: number | null,
  params: Record<string, unknown>,
  overrides: Partial<ConstraintEntryDto> = {},
): ConstraintEntryDto {
  return {
    id,
    params,
    enabled: true,
    hard: null,
    weight: null,
    groupKey: null,
    ...overrides,
  }
}

function config(
  type: string,
  entries: ConstraintEntryDto[],
  overrides: Partial<ConstraintConfigDto> = {},
): ConstraintConfigDto {
  return {
    type,
    params: entries.length > 0 ? entries[0].params : {},
    weight: 100,
    hard: true,
    enabled: true,
    entries,
    ...overrides,
  }
}

function entriesOf(request: ConstraintConfigRequest): ConstraintEntryDto[] {
  return (request.entries ?? []).map((row) => ({
    id: row.id ?? null,
    params: row.params,
    enabled: row.enabled ?? true,
    hard: row.hard ?? null,
    weight: row.weight ?? null,
    groupKey: row.groupKey ?? null,
  }))
}

function inputsOf(card: RuleCard): CardSentenceInput[] {
  return card.sentences.map((sentence) => ({
    key: sentence.key,
    input: {
      type: sentence.type,
      classId: sentence.classId,
      teacherId: sentence.teacherId,
      courseId: sentence.courseId,
      days: [...sentence.days],
      periodNos: sentence.periodNos,
      amount: sentence.amount,
      strength: sentence.strength,
      exceptionClassIds: sentence.exceptionClassIds,
    },
  }))
}


const names: RuleNames = {
  teacher: (id) => (id === 7 ? '王老师' : `教师${id}`),
  course: (id) => (id === 9 ? '数学' : `课程${id}`),
  klass: (id) => (id === 3 ? '八年级1班' : `班级${id}`),
  teacherGroup: (id) => (id === 12 ? '教导主任' : `教师组${id}`),
  courseGroup: (id) => (id === 21 ? '技能课' : `课程组${id}`),
  timeGroup: (id) => (id === 3 ? '上午' : `时间组${id}`),
}

describe('rule compiler: 16 createable types', () => {
  it('offers exactly the 16 types, grouped by sentence shape', () => {
    expect(RULE_TYPES).toHaveLength(16)
    expect(RULE_GROUPS.flatMap((group) => group.types)).toEqual(RULE_TYPES)
    expect(new Set(RULE_TYPES).size).toBe(16)
    expect(RULE_SPECS[CLASS_GAP_MINIMIZED].hardOnly).toBe(false)
    expect(RULE_SPECS[CLASS_UNAVAILABLE].hardOnly).toBe(true)
  })

  it('compiles 全校 周五第 7 节 不排 to one CLASS_UNAVAILABLE row', () => {
    const request = compileRule({
      type: CLASS_UNAVAILABLE,
      days: [5],
      periodNos: [7],
      strength: 'HARD',
    })

    expect(request.type).toBe('CLASS_UNAVAILABLE')
    expect(request.hard).toBe(true)
    const rows = entriesOf(request)
    expect(rows).toHaveLength(1)
    expect(rows[0].params).toEqual({ dayOfWeek: 5, periodNo: 7 })
    expect(rows[0].hard).toBe(true)
    expect(rows[0].groupKey).toMatch(/^pt-/)
  })

  it('compiles 班级 不排 with the class scope in every slot', () => {
    const request = compileRule({
      type: CLASS_UNAVAILABLE,
      classId: 3,
      days: [5],
      periodNos: [6, 7],
      strength: 'HARD',
    })
    expect(entriesOf(request).map((row) => row.params)).toEqual([
      { classId: 3, dayOfWeek: 5, periodNo: 6 },
      { classId: 3, dayOfWeek: 5, periodNo: 7 },
    ])
  })

  it('compiles 教师 周五下午 不排 to one row per afternoon period', () => {
    const request = compileRule({
      type: TEACHER_UNAVAILABLE,
      teacherId: 7,
      days: [5],
      periodNos: [8, 5, 7, 6],
      strength: 'SOFT',
    })
    const rows = entriesOf(request)
    expect(rows.map((row) => row.params.periodNo)).toEqual([5, 6, 7, 8])
    expect(new Set(rows.map((row) => row.groupKey))).toEqual(new Set([rows[0].groupKey]))
    expect(rows.every((row) => row.params.teacherId === 7)).toBe(true)
    expect(rows.every((row) => row.hard === false)).toBe(true)
  })

  it('compiles 课程 不排', () => {
    const request = compileRule({
      type: COURSE_UNAVAILABLE,
      courseId: 9,
      days: [2],
      periodNos: [1],
      strength: 'HARD',
    })
    expect(entriesOf(request)[0].params).toEqual({ courseId: 9, dayOfWeek: 2, periodNo: 1 })
  })

  it('compiles 数学 每天最多 1 节', () => {
    const request = compileRule({
      type: COURSE_MAX_PER_DAY,
      courseId: 9,
      amount: 1,
      strength: 'HARD',
    })
    expect(request.type).toBe('COURSE_MAX_PER_DAY')
    expect(entriesOf(request)[0].params).toEqual({ courseId: 9, maxPerDay: 1 })
  })

  it('compiles 教师 每天最多 4 节', () => {
    const request = compileRule({
      type: TEACHER_MAX_PER_DAY, teacherId: 7, amount: 4, strength: 'SOFT',
    })
    expect(entriesOf(request)[0].params).toEqual({ teacherId: 7, maxPerDay: 4 })
    expect(entriesOf(request)[0].hard).toBe(false)
  })

  it('compiles 班级 每天最多 8 节', () => {
    const request = compileRule({
      type: CLASS_MAX_PER_DAY, classId: 3, amount: 8, strength: 'SOFT',
    })
    expect(entriesOf(request)[0].params).toEqual({ classId: 3, maxPerDay: 8 })
  })

  it('compiles 同一课程 一天最多一节 with and without a course', () => {
    const specific = compileRule({
      type: SAME_COURSE_ONCE_PER_DAY, courseId: 9, strength: 'HARD',
    })
    expect(entriesOf(specific)[0].params).toEqual({ courseId: 9 })

    const all = compileRule({ type: SAME_COURSE_ONCE_PER_DAY, strength: 'HARD' })
    expect(entriesOf(all)[0].params).toEqual({})
  })

  it('compiles 数学 连堂 2 节', () => {
    const request = compileRule({
      type: CONSECUTIVE_LESSONS, courseId: 9, amount: 2, strength: 'SOFT',
    })
    expect(entriesOf(request)[0].params).toEqual({ courseId: 9, minConsecutive: 2 })
  })

  it('compiles 数学 分散到不同天 with and without a course', () => {
    expect(entriesOf(compileRule({ type: COURSE_SPREAD, courseId: 9, strength: 'SOFT' }))[0].params)
      .toEqual({ courseId: 9 })
    expect(entriesOf(compileRule({ type: COURSE_SPREAD, strength: 'SOFT' }))[0].params)
      .toEqual({})
  })

  it('compiles 班级 不空堂 as a switch config without entries', () => {
    const request = compileRule({ type: CLASS_GAP_MINIMIZED, strength: 'SOFT' })
    expect(request).toMatchObject({
      type: 'CLASS_GAP_MINIMIZED',
      params: {},
      hard: false,
      weight: 30,
    })
    expect(request.entries).toBeUndefined()
  })

  it('compiles 教师 连续不超过 3 节, also for all teachers', () => {
    expect(entriesOf(compileRule({
      type: TEACHER_MAX_CONSECUTIVE, teacherId: 7, amount: 3, strength: 'SOFT',
    }))[0].params).toEqual({ teacherId: 7, maxConsecutive: 3 })
    expect(entriesOf(compileRule({
      type: TEACHER_MAX_CONSECUTIVE, amount: 3, strength: 'SOFT',
    }))[0].params).toEqual({ maxConsecutive: 3 })
  })


  it('compiles 主科上午 with the cut-off period', () => {
    expect(entriesOf(compileRule({
      type: MAIN_SUBJECT_MORNING, courseId: 9, amount: 4, strength: 'SOFT',
    }))[0].params).toEqual({ courseId: 9, beforePeriodNo: 4 })
  })

  it('compiles 教师不排第一节, 固定无课日 and 偏好时段', () => {
    expect(entriesOf(compileRule({
      type: TEACHER_NO_FIRST_PERIOD, teacherId: 7, strength: 'SOFT',
    }))[0].params).toEqual({ teacherId: 7 })
    expect(entriesOf(compileRule({
      type: TEACHER_DAY_OFF, teacherId: 7, days: [3], strength: 'SOFT',
    }))[0].params).toEqual({ teacherId: 7, dayOfWeek: 3 })
    const preferred = compileRule({
      type: TEACHER_PREFERRED_SLOT,
      teacherId: 7,
      days: [3],
      periodNos: [5, 6],
      strength: 'SOFT',
    })
    const rows = entriesOf(preferred)
    expect(rows.map((row) => row.params)).toEqual([
      { teacherId: 7, dayOfWeek: 3, periodNo: 5 },
      { teacherId: 7, dayOfWeek: 3, periodNo: 6 },
    ])
    expect(new Set(rows.map((row) => row.groupKey)).size).toBe(1)
  })

  it('compiles 教师 周一、周三第 5、6 节 不上课 to the full day × period grid', () => {
    const rows = entriesOf(compileRule({
      type: TEACHER_UNAVAILABLE,
      teacherId: 7,
      days: [3, 1],
      periodNos: [6, 5],
      strength: 'HARD',
    }))
    expect(rows.map((row) => row.params)).toEqual([
      { teacherId: 7, dayOfWeek: 1, periodNo: 5 },
      { teacherId: 7, dayOfWeek: 1, periodNo: 6 },
      { teacherId: 7, dayOfWeek: 3, periodNo: 5 },
      { teacherId: 7, dayOfWeek: 3, periodNo: 6 },
    ])
    expect(new Set(rows.map((row) => row.groupKey)).size).toBe(1)
  })

  it('compiles 王老师 周一、周三 不排课 to one row per day', () => {
    const rows = entriesOf(compileRule({
      type: TEACHER_DAY_OFF, teacherId: 7, days: [3, 1], strength: 'SOFT',
    }))
    expect(rows.map((row) => row.params)).toEqual([
      { teacherId: 7, dayOfWeek: 1 },
      { teacherId: 7, dayOfWeek: 3 },
    ])
    expect(new Set(rows.map((row) => row.groupKey)).size).toBe(1)
  })

  it('compiles the global default scopes without null object keys', () => {
    expect(entriesOf(compileRule({
      type: TEACHER_MAX_PER_DAY, amount: 4, strength: 'SOFT',
    }))[0].params).toEqual({ maxPerDay: 4 })
    expect(entriesOf(compileRule({
      type: CLASS_MAX_PER_DAY, amount: 8, strength: 'SOFT',
    }))[0].params).toEqual({ maxPerDay: 8 })
    expect(entriesOf(compileRule({
      type: COURSE_MAX_PER_DAY, amount: 1, strength: 'SOFT',
    }))[0].params).toEqual({ maxPerDay: 1 })
  })

  it('refuses missing slots and non-selectable types', () => {
    expect(() =>
      compileRule({ type: null, days: [5], periodNos: [1], strength: 'HARD' }),
    ).toThrow('选择要求')
    expect(() =>
      compileRule({ type: CLASS_UNAVAILABLE, days: [5], periodNos: [], strength: 'HARD' }),
    ).toThrow('至少要选择一个节次')
    expect(() =>
      compileRule({ type: CONSECUTIVE_LESSONS, amount: 2, strength: 'HARD' }),
    ).toThrow('选择课程')
    expect(() =>
      compileRule({ type: TEACHER_DAY_OFF, teacherId: 7, strength: 'SOFT' }),
    ).toThrow('选择星期')
  })

  it('reports missing slots for the form', () => {
    expect(missingParts({
      type: null, classId: null, teacherId: null, courseId: null,
      days: [], periodNos: [], amount: null,
    })).toEqual(['选择要求'])
    expect(missingParts({
      type: TEACHER_DAY_OFF, classId: null, teacherId: 7, courseId: null,
      days: [], periodNos: [], amount: null,
    })).toEqual(['选择星期'])
  })
})

describe('teacher groups in sentences (ticket 04)', () => {
  it('compiles a group sentence to group params and never a concrete teacher', () => {
    const rows = entriesOf(compileRule({
      type: TEACHER_UNAVAILABLE,
      teacherId: 7,
      teacherGroupId: 12,
      days: [5],
      periodNos: [5, 6],
      strength: 'HARD',
    }))
    expect(rows.map((row) => row.params)).toEqual([
      { teacherGroupId: 12, dayOfWeek: 5, periodNo: 5 },
      { teacherGroupId: 12, dayOfWeek: 5, periodNo: 6 },
    ])
    expect(rows.every((row) => !('teacherId' in row.params))).toBe(true)
    expect(new Set(rows.map((row) => row.groupKey)).size).toBe(1)
  })

  it('compiles a group threshold to one group parameter', () => {
    const rows = entriesOf(compileRule({
      type: TEACHER_MAX_PER_DAY,
      teacherGroupId: 12,
      amount: 4,
      strength: 'SOFT',
    }))
    expect(rows[0].params).toEqual({ teacherGroupId: 12, maxPerDay: 4 })
  })

  it('reads a stored group row back with the group name in the sentence', () => {
    const stored = [
      config('TEACHER_UNAVAILABLE', [
        entry(1, { teacherGroupId: 12, dayOfWeek: 5, periodNo: 5 }, { groupKey: 'pt-group' }),
        entry(2, { teacherGroupId: 12, dayOfWeek: 5, periodNo: 6 }, { groupKey: 'pt-group' }),
      ]),
    ]
    const cards = cardsFromState(stored)
    expect(cards).toHaveLength(1)
    const sentence = cards[0].sentences[0]
    expect(sentence.teacherGroupId).toBe(12)
    expect(sentence.teacherId).toBeNull()
    expect(describeCard(cards[0], names)).toBe('教导主任 周五第 5、6 节 不上课')

    // Saving the read-back card keeps the group reference instead of expanding it.
    const merged = configsFromCards(stored, cards)
    expect(merged[0].entries?.map((row) => row.params)).toEqual([
      { teacherGroupId: 12, dayOfWeek: 5, periodNo: 5 },
      { teacherGroupId: 12, dayOfWeek: 5, periodNo: 6 },
    ])
  })

  it('accepts a group where a teacher is required, and still asks otherwise', () => {
    const base = {
      type: TEACHER_UNAVAILABLE,
      classId: null,
      teacherId: null,
      courseId: null,
      days: [5],
      periodNos: [5],
      amount: null,
    }
    expect(missingParts(base)).toContain('选择教师')
    expect(missingParts({ ...base, teacherGroupId: 12 })).toEqual([])
  })

  it('does not turn a row with both a teacher and a group into a sentence', () => {
    const cards = cardsFromState([
      config('TEACHER_UNAVAILABLE', [
        entry(1, {
          teacherId: 7, teacherGroupId: 12, dayOfWeek: 5, periodNo: 5,
        }, { groupKey: 'pt-both' }),
      ]),
    ])
    expect(cards).toHaveLength(0)
  })
})

describe('course groups in sentences (ticket 05)', () => {
  it('compiles a course-group sentence to group params and never a concrete course', () => {
    const rows = entriesOf(compileRule({
      type: COURSE_UNAVAILABLE,
      courseId: 9,
      courseGroupId: 21,
      days: [2],
      periodNos: [1, 2],
      strength: 'HARD',
    }))
    expect(rows.map((row) => row.params)).toEqual([
      { courseGroupId: 21, dayOfWeek: 2, periodNo: 1 },
      { courseGroupId: 21, dayOfWeek: 2, periodNo: 2 },
    ])
    expect(rows.every((row) => !('courseId' in row.params))).toBe(true)
    expect(new Set(rows.map((row) => row.groupKey)).size).toBe(1)
  })

  it('compiles a course-group threshold to one group parameter', () => {
    const rows = entriesOf(compileRule({
      type: COURSE_MAX_PER_DAY,
      courseGroupId: 21,
      amount: 1,
      strength: 'SOFT',
    }))
    expect(rows[0].params).toEqual({ courseGroupId: 21, maxPerDay: 1 })
  })

  it('reads a stored course-group row back with the group name in the sentence', () => {
    const stored = [
      config('COURSE_MAX_PER_DAY', [
        entry(1, { courseGroupId: 21, maxPerDay: 1 }, { groupKey: 'pt-course-group' }),
      ]),
    ]
    const cards = cardsFromState(stored)
    expect(cards).toHaveLength(1)
    expect(cards[0].sentences[0].courseGroupId).toBe(21)
    expect(cards[0].sentences[0].courseId).toBeNull()
    expect(describeCard(cards[0], names)).toBe('技能课 每门每天最多 1 节')

    // Saving the read-back card keeps the group reference instead of expanding it.
    const merged = configsFromCards(stored, cards)
    expect(merged[0].entries?.map((row) => row.params))
      .toEqual([{ courseGroupId: 21, maxPerDay: 1 }])
  })

  it('accepts a course group where a course is required, and still asks otherwise', () => {
    const base = {
      type: COURSE_UNAVAILABLE,
      classId: null,
      teacherId: null,
      courseId: null,
      days: [2],
      periodNos: [1],
      amount: null,
    }
    expect(missingParts(base)).toContain('选择课程')
    expect(missingParts({ ...base, courseGroupId: 21 })).toEqual([])
  })

  it('does not turn a row with both a course and a course group into a sentence', () => {
    const cards = cardsFromState([
      config('COURSE_MAX_PER_DAY', [
        entry(1, {
          courseId: 9, courseGroupId: 21, maxPerDay: 1,
        }, { groupKey: 'pt-both' }),
      ]),
    ])
    expect(cards).toHaveLength(0)
  })
})

describe('time groups in sentences (ticket 06)', () => {
  const base = {
    classId: null,
    teacherId: null,
    courseId: null,
    days: [] as number[],
    periodNos: [] as number[],
    amount: null,
  }

  it('shows the time group name instead of weekdays', () => {
    expect(describeRule({
      ...base,
      type: COURSE_UNAVAILABLE,
      courseGroupId: 21,
      timeGroupId: 3,
    }, names)).toBe('技能课 不排在 上午')
    expect(describeRule({
      ...base,
      type: TEACHER_UNAVAILABLE,
      teacherId: 7,
      timeGroupId: 3,
    }, names)).toBe('王老师 上午 不上课')
  })

  it('grows the sentence with the group name before the requirement is picked', () => {
    expect(describeRule({
      ...base,
      type: null,
      courseGroupId: 21,
      subjectKind: 'COURSE_GROUP',
      timeGroupId: 3,
    }, names)).toBe('技能课 上午 （请选择要求）')
  })

  it('compiles a time group to one row, without day or period params', () => {
    const rows = entriesOf(compileRule({
      type: COURSE_UNAVAILABLE,
      courseId: 9,
      timeGroupId: 3,
      days: [1],
      periodNos: [1],
      strength: 'HARD',
    }))
    expect(rows).toHaveLength(1)
    expect(rows[0].params).toEqual({ courseId: 9, timeGroupId: 3 })
    expect(rows[0].id).toBeNull()
    expect(rows[0].groupKey).toMatch(/^pt-/)
    expect(rows[0].params).not.toHaveProperty('dayOfWeek')
    expect(rows[0].params).not.toHaveProperty('periodNo')
  })

  it('combines a course group and a time group in the same row', () => {
    const rows = entriesOf(compileRule({
      type: COURSE_UNAVAILABLE,
      courseGroupId: 21,
      timeGroupId: 3,
      strength: 'HARD',
    }))
    expect(rows).toHaveLength(1)
    expect(rows[0].params).toEqual({ courseGroupId: 21, timeGroupId: 3 })
    expect(new Set(rows.map((row) => row.groupKey)).size).toBe(1)
  })

  it('reads a stored time-group row back and saves it unchanged', () => {
    const stored = [
      config('COURSE_UNAVAILABLE', [
        entry(7, { courseGroupId: 21, timeGroupId: 3 }, { groupKey: 'pt-time' }),
      ]),
    ]
    const cards = cardsFromState(stored)
    expect(cards).toHaveLength(1)
    const sentence = cards[0].sentences[0]
    expect(sentence.timeGroupId).toBe(3)
    expect(sentence.days).toEqual([])
    expect(sentence.periodNos).toEqual([])
    expect(sentence.entryIds).toEqual({ single: 7 })
    expect(describeCard(cards[0], names)).toBe('技能课 不排在 上午')

    const merged = configsFromCards(stored, cards)
    expect(merged[0].entries?.map((row) => row.params))
      .toEqual([{ courseGroupId: 21, timeGroupId: 3 }])
  })

  it('accepts a time group only where a period grid is required', () => {
    expect(missingParts({
      ...base,
      type: COURSE_UNAVAILABLE,
      courseId: 9,
      timeGroupId: 3,
    })).toEqual([])
    expect(missingParts({
      ...base,
      type: TEACHER_DAY_OFF,
      teacherId: 7,
      timeGroupId: 3,
    })).toContain('选择星期')
  })

  it('drops a time group from a fixed day off', () => {
    const rows = entriesOf(compileRule({
      type: TEACHER_DAY_OFF,
      teacherId: 7,
      days: [3],
      timeGroupId: 3,
      strength: 'SOFT',
    }))
    expect(rows.map((row) => row.params)).toEqual([{ teacherId: 7, dayOfWeek: 3 }])
  })

  it('rejects a day-off row that carries a time group', () => {
    const cards = cardsFromState([
      config('TEACHER_DAY_OFF', [
        entry(1, { teacherId: 7, dayOfWeek: 3, timeGroupId: 3 }, { groupKey: 'pt-off' }),
      ]),
    ])
    expect(cards).toHaveLength(0)
  })
})

describe('sentence wording: subject + time + requirement', () => {
  type Shape = Parameters<typeof describeRule>[0]

  function sentence(overrides: Partial<Shape> & Pick<Shape, 'type'>): string {
    return describeRule({
      classId: null,
      teacherId: null,
      courseId: null,
      days: [],
      periodNos: [],
      amount: null,
      ...overrides,
    }, names)
  }

  const week: PeriodOption[] = [1, 3, 5].flatMap((day) => [
    ...[1, 2, 3, 4].map((periodNo) => ({ dayOfWeek: day, periodNo, startTime: '08:00:00' })),
    ...[5, 6, 7, 8].map((periodNo) => ({ dayOfWeek: day, periodNo, startTime: '14:00' })),
  ])

  it('writes all 16 requirements as one plain sentence', () => {
    expect(sentence({ type: CLASS_UNAVAILABLE, days: [5], periodNos: [7] }))
      .toBe('全校 周五第 7 节 不上课')
    expect(sentence({
      type: CLASS_UNAVAILABLE, classId: 3, days: [5], periodNos: [7],
    })).toBe('八年级1班 周五第 7 节 不上课')
    expect(sentence({
      type: CLASS_UNAVAILABLE, days: [5], periodNos: [7], exceptionClassIds: [3],
    })).toBe('全校 周五第 7 节 不上课，除了 八年级1班')
    expect(sentence({
      type: TEACHER_UNAVAILABLE, teacherId: 7, days: [5], periodNos: [5, 6],
    })).toBe('王老师 周五第 5、6 节 不上课')
    expect(sentence({
      type: COURSE_UNAVAILABLE, courseId: 9, days: [5], periodNos: [5, 6],
    })).toBe('数学 不排在 周五第 5、6 节')
    expect(sentence({ type: COURSE_MAX_PER_DAY, courseId: 9, amount: 1 }))
      .toBe('数学 每天最多 1 节')
    expect(sentence({ type: COURSE_MAX_PER_DAY, amount: 1 }))
      .toBe('全部课程 每天最多 1 节')
    expect(sentence({ type: TEACHER_MAX_PER_DAY, teacherId: 7, amount: 4 }))
      .toBe('王老师 每天最多 4 节')
    expect(sentence({ type: TEACHER_MAX_PER_DAY, amount: 4 }))
      .toBe('所有教师 每天最多 4 节')
    expect(sentence({ type: CLASS_MAX_PER_DAY, classId: 3, amount: 8 }))
      .toBe('八年级1班 每天最多 8 节')
    expect(sentence({ type: CLASS_MAX_PER_DAY, amount: 8 }))
      .toBe('全校 每天最多 8 节')
    expect(sentence({ type: SAME_COURSE_ONCE_PER_DAY, courseId: 9 }))
      .toBe('数学 每天最多 1 节')
    expect(sentence({ type: SAME_COURSE_ONCE_PER_DAY }))
      .toBe('同一门课程 每天最多 1 节')
    expect(sentence({ type: CONSECUTIVE_LESSONS, courseId: 9, amount: 2 }))
      .toBe('数学 连堂 2 节')
    expect(sentence({ type: COURSE_SPREAD, courseId: 9 })).toBe('数学 分散到不同天')
    expect(sentence({ type: COURSE_SPREAD })).toBe('全部课程 分散到不同天')
    expect(sentence({ type: CLASS_GAP_MINIMIZED })).toBe('班级 不空堂')
    expect(sentence({ type: TEACHER_MAX_CONSECUTIVE, teacherId: 7, amount: 3 }))
      .toBe('王老师 连续不超过 3 节')
    expect(sentence({ type: TEACHER_MAX_CONSECUTIVE, amount: 3 }))
      .toBe('所有教师 连续不超过 3 节')
    expect(sentence({ type: PE_LAST_PERIOD_AVOID, courseId: 9 }))
      .toBe('数学 不在最后一节')
    expect(sentence({ type: PE_LAST_PERIOD_AVOID })).toBe('体育 不在最后一节')
    expect(sentence({ type: MAIN_SUBJECT_MORNING, courseId: 9, amount: 4 }))
      .toBe('数学 排在上午（前 4 节）')
    expect(sentence({ type: TEACHER_NO_FIRST_PERIOD, teacherId: 7 }))
      .toBe('王老师 不排第一节')
    expect(sentence({ type: TEACHER_DAY_OFF, teacherId: 7, days: [3] }))
      .toBe('王老师 周三 不排课')
    expect(sentence({ type: TEACHER_PREFERRED_SLOT, teacherId: 7, days: [3], periodNos: [5, 6] }))
      .toBe('王老师 偏好 周三第 5、6 节')
  })

  it('writes one sentence for several weekdays', () => {
    expect(sentence({
      type: TEACHER_UNAVAILABLE, teacherId: 7, days: [3, 1], periodNos: [5, 6],
    })).toBe('王老师 周一、周三第 5、6 节 不上课')
    expect(describeRule({
      type: TEACHER_UNAVAILABLE, classId: null, teacherId: 7, courseId: null,
      days: [3, 1], periodNos: [5, 6, 7, 8], amount: null,
    }, names, week)).toBe('王老师 周一、周三下午 不上课')
    expect(sentence({ type: TEACHER_DAY_OFF, teacherId: 7, days: [3, 1] }))
      .toBe('王老师 周一、周三 不排课')
  })

  it('never says 的 课 在', () => {
    const sentences = [
      sentence({ type: CLASS_UNAVAILABLE, days: [5], periodNos: [7] }),
      sentence({ type: TEACHER_UNAVAILABLE, teacherId: 7, days: [5], periodNos: [5] }),
      sentence({ type: COURSE_UNAVAILABLE, courseId: 9, days: [5], periodNos: [5] }),
      sentence({ type: COURSE_MAX_PER_DAY, courseId: 9, amount: 1 }),
      sentence({ type: TEACHER_MAX_PER_DAY, teacherId: 7, amount: 4 }),
      sentence({ type: CLASS_MAX_PER_DAY, classId: 3, amount: 8 }),
      sentence({ type: SAME_COURSE_ONCE_PER_DAY }),
      sentence({ type: CONSECUTIVE_LESSONS, courseId: 9, amount: 2 }),
      sentence({ type: COURSE_SPREAD }),
      sentence({ type: CLASS_GAP_MINIMIZED }),
      sentence({ type: TEACHER_MAX_CONSECUTIVE, amount: 3 }),
      sentence({ type: PE_LAST_PERIOD_AVOID }),
      sentence({ type: MAIN_SUBJECT_MORNING, courseId: 9, amount: 4 }),
      sentence({ type: TEACHER_NO_FIRST_PERIOD, teacherId: 7 }),
      sentence({ type: TEACHER_DAY_OFF, teacherId: 7, days: [3] }),
      sentence({ type: TEACHER_PREFERRED_SLOT, teacherId: 7, days: [3], periodNos: [5] }),
    ]
    sentences.forEach((text) => expect(text).not.toContain('的 课 在'))
  })

  it('folds a full morning, afternoon or whole day into 上午/下午/全天', () => {
    expect(timeLabel([5], [5, 6, 7, 8], week)).toBe('周五下午')
    expect(timeLabel([5], [1, 2, 3, 4], week)).toBe('周五上午')
    expect(timeLabel([5], [1, 2, 3, 4, 5, 6, 7, 8], week)).toBe('周五全天')
    expect(timeLabel([5], [5, 6], week)).toBe('周五第 5、6 节')

    expect(describeRule({
      type: TEACHER_UNAVAILABLE, classId: null, teacherId: 7, courseId: null,
      days: [5], periodNos: [5, 6, 7, 8], amount: null,
    }, names, week)).toBe('王老师 周五下午 不上课')
    expect(describeRule({
      type: TEACHER_UNAVAILABLE, classId: null, teacherId: 7, courseId: null,
      days: [5], periodNos: [1, 2, 3, 4], amount: null,
    }, names, week)).toBe('王老师 周五上午 不上课')
    expect(describeRule({
      type: TEACHER_UNAVAILABLE, classId: null, teacherId: 7, courseId: null,
      days: [5], periodNos: [1, 2, 3, 4, 5, 6, 7, 8], amount: null,
    }, names, week)).toBe('王老师 周五全天 不上课')
  })

  it('falls back to 第 x、y 节 without start times or for a partial slice', () => {
    const noTimes: PeriodOption[] = week.map(({ dayOfWeek, periodNo }) => ({ dayOfWeek, periodNo }))
    expect(timeLabel([5], [5, 6], noTimes)).toBe('周五第 5、6 节')
    expect(timeLabel([4], [1], week)).toBe('周四第 1 节')
    expect(timeLabel([5], [1, 2, 3], week)).toBe('周五第 1、2、3 节')
  })

  it('grows the subject and time before the requirement is chosen', () => {
    expect(describeRule({
      type: null, classId: null, teacherId: 7, courseId: null,
      days: [5], periodNos: [5], amount: null,
    }, names)).toBe('王老师 周五第 5 节 （请选择要求）')
    expect(describeRule({
      type: null, classId: null, teacherId: 7, courseId: null,
      days: [5], periodNos: [5, 6, 7, 8], amount: null,
    }, names, week)).toBe('王老师 周五下午 （请选择要求）')
    expect(describeRule({
      type: null, classId: null, teacherId: null, courseId: null,
      days: [], periodNos: [], amount: null, subjectKind: 'SCHOOL',
    }, names)).toBe('全校 （请选择要求）')
    expect(describeRule({
      type: null, classId: null, teacherId: null, courseId: null,
      days: [], periodNos: [], amount: null,
    }, names)).toBe('（请先选谁 / 什么课）')
  })

  it('names the missing subject instead of inventing one', () => {
    expect(sentence({ type: TEACHER_DAY_OFF, days: [3] }))
      .toBe('（请选择教师） 周三 不排课')
    expect(sentence({ type: TEACHER_DAY_OFF, teacherId: 7 }))
      .toBe('王老师 （请选择星期） 不排课')
    expect(sentence({ type: TEACHER_UNAVAILABLE, days: [3], periodNos: [5] }))
      .toBe('（请选择教师） 周三第 5 节 不上课')
    expect(sentence({ type: CONSECUTIVE_LESSONS, amount: 2 })).toBe('（请选择课程） 连堂 2 节')
  })
})


describe('state to cards', () => {
  it('renders time-slice cards with the agreed wording', () => {
    const cards = cardsFromState([
      config('TEACHER_UNAVAILABLE', [5, 6, 7, 8].map((periodNo) =>
        entry(100 + periodNo, { teacherId: 7, dayOfWeek: 5, periodNo }, { groupKey: 'pt-wang' }),
      )),
      config('CLASS_UNAVAILABLE', [
        entry(200, { dayOfWeek: 5, periodNo: 7 }, { groupKey: 'pt-school', hard: true }),
      ]),
    ])

    expect(cards).toHaveLength(2)
    expect(describeCard(cards[0], names)).toBe('王老师 周五第 5、6、7、8 节 不上课')
    expect(describeCard(cards[1], names)).toBe('全校 周五第 7 节 不上课')
    expect(cards[0].sentences[0].entryIds)
      .toEqual({ '5-5': 105, '5-6': 106, '5-7': 107, '5-8': 108 })
  })

  it('renders quantity, shape and preset cards with human wording', () => {
    const cards = cardsFromState([
      config('COURSE_MAX_PER_DAY', [entry(1, { courseId: 9, maxPerDay: 1 }, { groupKey: 'pt-math' })]),
      config('CONSECUTIVE_LESSONS', [entry(2, { courseId: 9, minConsecutive: 2 }, { groupKey: 'pt-chinese' })]),
      config('PE_LAST_PERIOD_AVOID', [entry(3, { courseId: 9 }, { groupKey: 'pt-pe' })]),
      config('CLASS_GAP_MINIMIZED', [], { hard: true, weight: 30 }),
    ])

    expect(describeCard(cards[0], names)).toBe('数学 每天最多 1 节')
    expect(describeCard(cards[1], names)).toBe('数学 连堂 2 节')
    expect(describeCard(cards[2], names)).toBe('数学 不在最后一节')
    expect(describeCard(cards[3], names)).toBe('班级 不空堂')
    expect(cards[3].sentences[0].type).toBe('CLASS_GAP_MINIMIZED')
  })

  it('renders multi-day sentence cards and joins their rows into one sentence', () => {
    const rows = [5, 6].flatMap((periodNo) => [1, 3].map((day) =>
      entry(day * 10 + periodNo, { teacherId: 7, dayOfWeek: day, periodNo },
        { groupKey: 'pt-multi' }),
    ))
    const cards = cardsFromState([config('TEACHER_UNAVAILABLE', rows)])

    expect(cards).toHaveLength(1)
    const sentence = cards[0].sentences[0]
    expect(sentence.days).toEqual([1, 3])
    expect(sentence.periodNos).toEqual([5, 6])
    expect(describeCard(cards[0], names)).toBe('王老师 周一、周三第 5、6 节 不上课')
    expect(sentence.entryIds).toEqual({
      '1-5': 15, '1-6': 16, '3-5': 35, '3-6': 36,
    })
  })

  it('renders a multi-day fixed days off group as one sentence', () => {
    const cards = cardsFromState([
      config('TEACHER_DAY_OFF', [
        entry(1, { teacherId: 7, dayOfWeek: 1 }, { groupKey: 'pt-off' }),
        entry(2, { teacherId: 7, dayOfWeek: 3 }, { groupKey: 'pt-off' }),
      ]),
    ])
    expect(cards).toHaveLength(1)
    expect(describeCard(cards[0], names)).toBe('王老师 周一、周三 不排课')
    expect(cards[0].sentences[0].days).toEqual([1, 3])
    expect(cards[0].sentences[0].entryIds).toEqual({ 'day-1': 1, 'day-3': 2 })
  })

  it('renders the global default thresholds as one sentence each', () => {
    const cards = cardsFromState([
      config('TEACHER_MAX_PER_DAY', [entry(1, { maxPerDay: 4 }, { groupKey: 'pt-t' })]),
      config('CLASS_MAX_PER_DAY', [entry(2, { maxPerDay: 8 }, { groupKey: 'pt-c' })]),
      config('COURSE_MAX_PER_DAY', [entry(3, { maxPerDay: 1 }, { groupKey: 'pt-k' })]),
    ])
    expect(cards.map((card) => describeCard(card, names))).toEqual([
      '所有教师 每天最多 4 节', '全校 每天最多 8 节', '全部课程 每天最多 1 节',
    ])
  })

  it('keeps only genuinely unrepresentable groups out of the sentence model', () => {
    const existing = [
      config('CLASS_UNAVAILABLE', [
        entry(300, { classId: 3, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-class' }),
      ]),
      config('TEACHER_UNAVAILABLE', [
        entry(301, { teacherId: 7, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-multi' }),
        entry(302, { teacherId: 7, dayOfWeek: 3, periodNo: 1 }, { groupKey: 'pt-multi' }),
      ]),
      config('COURSE_MAX_PER_DAY', [entry(303, { maxPerDay: 2 }, { groupKey: 'pt-global' })]),
      // A day × period grid with a missing combination cannot be one sentence.
      config('TEACHER_UNAVAILABLE', [
        entry(304, { teacherId: 8, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-hole' }),
        entry(305, { teacherId: 8, dayOfWeek: 3, periodNo: 2 }, { groupKey: 'pt-hole' }),
      ]),
    ]
    expect(cardsFromState(existing)).toHaveLength(3)
    expect(unrepresentedGroupCount(existing)).toBe(1)
  })
})

describe('merging into the whole semester configuration', () => {
  const entryBackedTypes = new Set([
    'LEGACY_COURSE_RULE',
    'TEACHER_UNAVAILABLE',
    'CLASS_UNAVAILABLE',
    'COURSE_MAX_PER_DAY',
    'CONSECUTIVE_LESSONS',
  ])

  const existing = [
    config('LEGACY_COURSE_RULE', [entry(1, { courseId: 9 })], { weight: 80, hard: true }),
    config('TEACHER_UNAVAILABLE', [
      entry(10, { teacherId: 7, dayOfWeek: 5, periodNo: 5 }, { groupKey: 'pt-wang' }),
      entry(11, { teacherId: 7, dayOfWeek: 5, periodNo: 6 }, { groupKey: 'pt-wang' }),
      entry(12, { teacherId: 8, dayOfWeek: 2, periodNo: 3 }, { groupKey: 'pt-li' }),
    ]),
    config('CLASS_UNAVAILABLE', [
      entry(20, { dayOfWeek: 5, periodNo: 7 }, { groupKey: 'pt-school', hard: true }),
      entry(21, { classId: 3, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-class', hard: true }),
    ]),
  ]

  it('keeps every other type, every other group and their row ids', () => {
    const cards = cardsFromState(existing)
    expect(cards).toHaveLength(4)

    const merged = configsFromCards(existing, cards, { entryBackedTypes })

    const course = merged.find((item) => item.type === 'LEGACY_COURSE_RULE')
    expect(course?.entries).toHaveLength(1)
    expect(course?.entries?.[0].id).toBe(1)
    expect(course?.weight).toBe(80)

    const teacher = merged.find((item) => item.type === 'TEACHER_UNAVAILABLE')
    expect(teacher?.entries?.map((row) => row.id)).toEqual([10, 11, 12])

    const school = merged.find((item) => item.type === 'CLASS_UNAVAILABLE')
    expect(school?.entries?.map((row) => row.id)).toEqual([20, 21])
    expect(school?.entries?.find((row) => row.id === 21)?.params).toMatchObject({ classId: 3 })
  })

  it('deletes only the dropped group and pauses only the toggled one', () => {
    const cards = cardsFromState(existing).filter((card) => card.cardId !== 'pt-li')
    const merged = configsFromCards(existing, cards, { entryBackedTypes })
    const teacher = merged.find((item) => item.type === 'TEACHER_UNAVAILABLE')
    expect(teacher?.entries?.map((row) => row.id)).toEqual([10, 11])

    const paused = cardsFromState(existing).map((card) =>
      card.cardId === 'pt-wang' ? toggleCard(card, false) : card,
    )
    const pausedMerge = configsFromCards(existing, paused, { entryBackedTypes })
    expect(pausedMerge.find((item) => item.type === 'TEACHER_UNAVAILABLE')
      ?.entries?.filter((row) => row.groupKey === 'pt-wang').every((row) => row.enabled === false))
      .toBe(true)
  })

  it('adds a new sentence without disturbing the stored configuration', () => {
    const cards: RuleCard[] = [
      ...cardsFromState(existing),
      cardFromInputs([{
        type: COURSE_MAX_PER_DAY,
        courseId: 9,
        amount: 1,
        strength: 'HARD',
      }]),
    ]
    const merged = configsFromCards(existing, cards, { entryBackedTypes })
    const courseMax = merged.find((item) => item.type === 'COURSE_MAX_PER_DAY')
    expect(courseMax?.entries).toHaveLength(1)
    expect(courseMax?.entries?.[0].params).toEqual({ courseId: 9, maxPerDay: 1 })
    expect(merged.find((item) => item.type === 'CLASS_UNAVAILABLE')?.entries).toHaveLength(2)
  })

  it('passes a switch type through without inventing an entry list', () => {
    const switchConfig = config('CLASS_GAP_MINIMIZED', [], { enabled: false })
    const merged = configsFromCards(
      [config('TEACHER_CONFLICT', []), switchConfig],
      cardsFromState([switchConfig]),
    )
    expect(merged).toEqual([
      {
        type: 'TEACHER_CONFLICT',
        params: {},
        weight: 100,
        hard: true,
        enabled: true,
      },
      {
        type: 'CLASS_GAP_MINIMIZED',
        params: {},
        weight: 30,
        hard: true,
        enabled: false,
      },
    ])
  })

  it('keeps a legacy single-value managed config instead of deleting it', () => {
    const legacy = config('COURSE_MAX_PER_DAY', [], {
      params: { maxPerDay: 3 },
      entries: [],
    })
    const merged = configsFromCards([legacy], [])
    expect(merged).toHaveLength(1)
    expect(merged[0].entries?.[0].params).toEqual({ maxPerDay: 3 })
    expect(unrepresentedGroupCount([legacy])).toBe(1)
  })

  it('counts other rules only for types the catalogue still offers', () => {
    const configs = [
      config('TEACHER_CONFLICT', []),
      config('LEGACY_COURSE_RULE', [entry(1, { courseId: 9 })]),
    ]
    const active = new Set(['TEACHER_CONFLICT', 'CLASS_CONFLICT'])
    expect(otherRuleCount(configs)).toBe(2)
    expect(otherRuleCount(configs, active)).toBe(1)
  })
})

describe('cards: multiple sentences and exceptions', () => {
  const classIds = [1, 2, 3]
  const options = { classIds }

  function twoSentenceCard(): RuleCard {
    return cardFromInputs([
      { type: CLASS_UNAVAILABLE, days: [1], periodNos: [3] },
      {
        type: CLASS_UNAVAILABLE,
        days: [2],
        periodNos: [3],
        exceptionClassIds: [2],
      },
    ])
  }

  it('expands 除了某班 into one row per remaining class with one group key', () => {
    const request = compileRule({
      type: CLASS_UNAVAILABLE,
      days: [1],
      periodNos: [3],
      exceptionClassIds: [2],
    }, options)
    const rows = entriesOf(request)
    expect(rows).toHaveLength(2)
    expect(rows.map((row) => row.params)).toEqual([
      { classId: 1, dayOfWeek: 1, periodNo: 3 },
      { classId: 3, dayOfWeek: 1, periodNo: 3 },
    ])
    expect(new Set(rows.map((row) => row.groupKey)).size).toBe(1)
    expect(rows[0].groupKey).toMatch(/^pt-/)
  })

  it('reads an exception group back as 全校 … 除了 X', () => {
    const stored = [
      config('CLASS_UNAVAILABLE', [
        entry(1, { classId: 1, dayOfWeek: 1, periodNo: 3 }, { groupKey: 'pt-card:0' }),
        entry(2, { classId: 3, dayOfWeek: 1, periodNo: 3 }, { groupKey: 'pt-card:0' }),
      ]),
    ]
    const cards = cardsFromState(stored, { allClassIds: classIds })
    expect(cards).toHaveLength(1)
    const sentence = cards[0].sentences[0]
    expect(sentence.classId).toBeNull()
    expect(sentence.exceptionClassIds).toEqual([2])
    expect(describeCard(cards[0], names))
      .toBe('全校 周一第 3 节 不上课，除了 班级2')
    expect(sentence.entryIds).toEqual({ '1:1-3': 1, '3:1-3': 2 })
  })

  it('keeps two sentences of one card in one config with ordered group keys', () => {
    const card = twoSentenceCard()
    expect(card.sentences.map((sentence) => sentence.groupKey)).toEqual([
      `${card.cardId}:0`,
      `${card.cardId}:1`,
    ])
    const merged = configsFromCards([], [card], options)
    const unavailable = merged.find((item) => item.type === 'CLASS_UNAVAILABLE')
    expect(unavailable?.entries).toHaveLength(3)
    expect(unavailable?.entries?.map((row) => row.groupKey)).toEqual([
      `${card.cardId}:0`,
      `${card.cardId}:1`,
      `${card.cardId}:1`,
    ])
    const second = unavailable?.entries?.filter((row) => row.groupKey === `${card.cardId}:1`)
    expect(second?.map((row) => row.params.classId)).toEqual([1, 3])
  })

  it('edits one sentence and leaves the other group alone', () => {
    const card = twoSentenceCard()
    const first = card.sentences[0]
    const second = card.sentences[1]
    const edited = applyCardInputs(card, [
      { key: first.key, input: { ...inputsOf(card)[0].input, periodNos: [4] } },
      { key: second.key, input: inputsOf(card)[1].input },
    ])
    expect(edited.sentences[0].groupKey).toBe(first.groupKey)
    expect(edited.sentences[0].periodNos).toEqual([4])
    expect(edited.sentences[1].groupKey).toBe(second.groupKey)
    expect(edited.sentences[1].exceptionClassIds).toEqual([2])
    expect(edited.cardId).toBe(card.cardId)
  })

  it('deletes one sentence or the whole card without touching another card', () => {
    const keep = cardFromInputs([{ type: COURSE_MAX_PER_DAY, courseId: 9, amount: 1 }])
    const stored = [
      config('CLASS_UNAVAILABLE', [
        entry(1, { classId: 1, dayOfWeek: 1, periodNo: 3 }, { groupKey: 'pt-card:0' }),
        entry(2, { dayOfWeek: 2, periodNo: 3 }, { groupKey: 'pt-card:1' }),
      ]),
      config('COURSE_MAX_PER_DAY', [
        entry(3, { courseId: 9, maxPerDay: 1 }, { groupKey: `${keep.cardId}:0` }),
      ]),
    ]
    const card = cardsFromState(stored, { allClassIds: classIds })[0]
    const oneSentence = applyCardInputs(card, [inputsOf(card)[0]])
    const merged = configsFromCards(stored, [oneSentence, keep], options)
    const unavailable = merged.find((item) => item.type === 'CLASS_UNAVAILABLE')
    expect(unavailable?.entries?.map((row) => row.id)).toEqual([1])
    expect(unavailable?.entries?.[0].groupKey).toBe('pt-card:0')

    const noCards = configsFromCards(stored, [keep], options)
    expect(noCards.some((item) => item.type === 'CLASS_UNAVAILABLE')).toBe(false)
    expect(noCards.find((item) => item.type === 'COURSE_MAX_PER_DAY')?.entries).toHaveLength(1)
  })

  it('duplicates a card with a new id and no stored row ids', () => {
    const card = twoSentenceCard()
    const copy = duplicateCard(card)
    expect(copy.cardId).not.toBe(card.cardId)
    expect(copy.sentences.map((sentence) => sentence.groupKey)).toEqual([
      `${copy.cardId}:0`,
      `${copy.cardId}:1`,
    ])
    expect(copy.sentences.every((sentence) => Object.keys(sentence.entryIds).length === 0)).toBe(true)
    expect(copy.sentences[1].exceptionClassIds).toEqual([2])
  })

  it('refuses an exception that covers every class', () => {
    expect(sentenceProblem({
      type: CLASS_UNAVAILABLE,
      classId: null,
      teacherId: null,
      courseId: null,
      days: [1],
      periodNos: [3],
      amount: null,
      exceptionClassIds: [1, 2, 3],
    }, classIds)).toBe('「除了」不能覆盖全部班级')
  })

  it('upgrades a legacy single-sentence card to <cardId>:0 on edit', () => {
    const legacy = cardsFromState([
      config('CLASS_UNAVAILABLE', [
        entry(9, { dayOfWeek: 5, periodNo: 7 }, { groupKey: 'pt-legacy' }),
      ]),
    ])[0]
    const edited = applyCardInputs(legacy, [
      { key: legacy.sentences[0].key, input: { type: CLASS_UNAVAILABLE, days: [5], periodNos: [6] } },
    ])
    expect(edited.cardId).toBe('pt-legacy')
    expect(edited.sentences[0].groupKey).toBe('pt-legacy:0')
    expect(edited.sentences[0].entryIds).toEqual({ '5-7': 9 })

    const merged = configsFromCards(
      [config('CLASS_UNAVAILABLE', [
        entry(9, { dayOfWeek: 5, periodNo: 7 }, { groupKey: 'pt-legacy' }),
      ])],
      [edited],
      options,
    )
    // The slot changed, so the old row is replaced instead of being reused.
    expect(merged[0].entries?.map((row) => row.id)).toEqual([null])
    expect(merged[0].entries?.[0].groupKey).toBe('pt-legacy:0')
  })
})

describe('grouping and search', () => {
  const classIds = [1, 2, 3]
  const school = cardFromInputs([{ type: CLASS_UNAVAILABLE, days: [1], periodNos: [3] }])
  const klass = cardFromInputs([{ type: CLASS_UNAVAILABLE, classId: 3, days: [2], periodNos: [1] }])
  const teacher = cardFromInputs([{ type: TEACHER_UNAVAILABLE, teacherId: 7, days: [3], periodNos: [1] }])
  const course = cardFromInputs([{ type: COURSE_MAX_PER_DAY, courseId: 9, amount: 1 }])

  it('buckets a card by 对谁', () => {
    expect(cardTarget(school)).toBe('SCHOOL')
    expect(cardTarget(klass)).toBe('CLASS')
    expect(cardTarget(teacher)).toBe('TEACHER')
    expect(cardTarget(course)).toBe('COURSE')
    expect(klass.sentences[0].exceptionClassIds).toEqual([])
    expect(classIds).toHaveLength(3)
  })

  it('filters by object name, sentence wording and bucket label', () => {
    expect(cardMatches(teacher, '王老师', names)).toBe(true)
    expect(cardMatches(teacher, '周五', names)).toBe(false)
    expect(cardMatches(course, '数学', names)).toBe(true)
    expect(cardMatches(school, '全校', names)).toBe(true)
    expect(cardMatches(school, '九', names)).toBe(false)
  })
})
