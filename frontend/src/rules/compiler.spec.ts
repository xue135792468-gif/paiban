import { describe, expect, it } from 'vitest'
import type {
  ConstraintConfigDto,
  ConstraintConfigRequest,
  ConstraintEntryDto,
} from '@/components/constraints/types'
import {
  CLASS_UNAVAILABLE,
  TEACHER_UNAVAILABLE,
  cardsFromState,
  compileRule,
  configsFromCards,
  describeCard,
  otherRuleCount,
  unrepresentedGroupCount,
  type PeriodOption,
  type RuleCard,
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

const periods: PeriodOption[] = [1, 2, 3, 4, 5].flatMap((day) => [
  { dayOfWeek: day, periodNo: 1, section: 'MORNING' },
  { dayOfWeek: day, periodNo: 2, section: 'MORNING' },
  { dayOfWeek: day, periodNo: 3, section: 'MORNING' },
  { dayOfWeek: day, periodNo: 4, section: 'MORNING' },
  { dayOfWeek: day, periodNo: 5, section: 'AFTERNOON' },
  { dayOfWeek: day, periodNo: 6, section: 'AFTERNOON' },
  { dayOfWeek: day, periodNo: 7, section: 'AFTERNOON' },
  { dayOfWeek: day, periodNo: 8, section: 'AFTERNOON' },
])

const teacherName = (id: number) => (id === 7 ? '王老师' : `教师${id}`)

describe('rule compiler', () => {
  it('compiles 全校 周五第 7 节 不排 to one CLASS_UNAVAILABLE row', () => {
    const request = compileRule({
      type: CLASS_UNAVAILABLE,
      teacherId: null,
      dayOfWeek: 5,
      periodNos: [7],
      strength: 'HARD',
    })

    expect(request.type).toBe('CLASS_UNAVAILABLE')
    // CLASS_UNAVAILABLE is hard-only: neither the config nor the row may become soft.
    expect(request.hard).toBe(true)
    expect(request.enabled).toBe(true)
    const rows = entriesOf(request)
    expect(rows).toHaveLength(1)
    expect(rows[0].params).toEqual({ dayOfWeek: 5, periodNo: 7 })
    expect(rows[0].params).not.toHaveProperty('classId')
    expect(rows[0].hard).toBe(true)
    expect(rows[0].groupKey).toMatch(/^pt-/)
  })

  it('expands 教师 周五下午 不排 to one row per afternoon period, sharing a group key', () => {
    const request = compileRule({
      type: TEACHER_UNAVAILABLE,
      teacherId: 7,
      dayOfWeek: 5,
      periodNos: [8, 5, 7, 6],
      strength: 'SOFT',
    })

    expect(request.type).toBe('TEACHER_UNAVAILABLE')
    const rows = entriesOf(request)
    expect(rows.map((row) => row.params.periodNo)).toEqual([5, 6, 7, 8])
    expect(new Set(rows.map((row) => row.groupKey))).toEqual(
      new Set([rows[0].groupKey]),
    )
    expect(rows.every((row) => row.params.teacherId === 7)).toBe(true)
    expect(rows.every((row) => row.hard === false)).toBe(true)
  })

  it('forces a school rule to 必须 even when the input says 尽量', () => {
    const request = compileRule({
      type: CLASS_UNAVAILABLE,
      teacherId: null,
      dayOfWeek: 5,
      periodNos: [7],
      strength: 'SOFT',
    })
    expect(request.hard).toBe(true)
    expect(entriesOf(request)[0].hard).toBe(true)
  })

  it('refuses a sentence with no period', () => {
    expect(() =>
      compileRule({
        type: CLASS_UNAVAILABLE,
        teacherId: null,
        dayOfWeek: 5,
        periodNos: [],
        strength: 'HARD',
      }),
    ).toThrow('至少要选择一个节次')
  })
})

describe('state to cards', () => {
  it('renders a teacher afternoon group and a school slot with the agreed wording', () => {
    const cards = cardsFromState([
      config('TEACHER_UNAVAILABLE', [
        ...[5, 6, 7, 8].map((periodNo) =>
          entry(100 + periodNo, { teacherId: 7, dayOfWeek: 5, periodNo }, { groupKey: 'pt-wang' }),
        ),
      ]),
      config('CLASS_UNAVAILABLE', [
        entry(200, { dayOfWeek: 5, periodNo: 7 }, { groupKey: 'pt-school', hard: true }),
      ]),
    ])

    expect(cards).toHaveLength(2)
    expect(describeCard(cards[0], teacherName, periods)).toBe('王老师 的 课 在 周五下午 不排')
    expect(describeCard(cards[1], teacherName, periods)).toBe('全校 的 课 在 周五第 7 节 不排')
    expect(cards[0].entryIds).toEqual({ '5-5': 105, '5-6': 106, '5-7': 107, '5-8': 108 })
  })

  it('keeps class-specific and multi-day groups out of the sentence model', () => {
    const cards = cardsFromState([
      config('CLASS_UNAVAILABLE', [
        entry(300, { classId: 3, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-class' }),
      ]),
      config('TEACHER_UNAVAILABLE', [
        entry(301, { teacherId: 7, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-multi' }),
        entry(302, { teacherId: 7, dayOfWeek: 3, periodNo: 1 }, { groupKey: 'pt-multi' }),
      ]),
    ])
    expect(cards).toHaveLength(0)
    expect(
      otherRuleCount([
        config('CLASS_UNAVAILABLE', [
          entry(300, { classId: 3, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-class' }),
        ]),
        config('TEACHER_UNAVAILABLE', [
          entry(301, { teacherId: 7, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-multi' }),
          entry(302, { teacherId: 7, dayOfWeek: 3, periodNo: 1 }, { groupKey: 'pt-multi' }),
        ]),
      ]),
    ).toBe(0)
    expect(
      unrepresentedGroupCount([
        config('CLASS_UNAVAILABLE', [
          entry(300, { classId: 3, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-class' }),
        ]),
        config('TEACHER_UNAVAILABLE', [
          entry(301, { teacherId: 7, dayOfWeek: 2, periodNo: 1 }, { groupKey: 'pt-multi' }),
          entry(302, { teacherId: 7, dayOfWeek: 3, periodNo: 1 }, { groupKey: 'pt-multi' }),
        ]),
        config('COURSE_NOT_IN_EVENING', [entry(303, { courseId: 9 })]),
      ]),
    ).toBe(2)
  })
})

describe('merging into the whole semester configuration', () => {
  const entryBackedTypes = new Set([
    'COURSE_NOT_IN_EVENING',
    'TEACHER_UNAVAILABLE',
    'CLASS_UNAVAILABLE',
  ])

  const existing = [
    config('COURSE_NOT_IN_EVENING', [entry(1, { courseId: 9 })], { weight: 80, hard: true }),
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
    expect(cards).toHaveLength(3)

    const merged = configsFromCards(existing, cards, { entryBackedTypes })

    const course = merged.find((item) => item.type === 'COURSE_NOT_IN_EVENING')
    expect(course?.entries).toHaveLength(1)
    expect(course?.entries?.[0].id).toBe(1)
    expect(course?.entries?.[0].params).toEqual({ courseId: 9 })
    expect(course?.weight).toBe(80)

    const teacher = merged.find((item) => item.type === 'TEACHER_UNAVAILABLE')
    expect(teacher?.entries?.map((row) => row.id)).toEqual([10, 11, 12])
    expect(teacher?.entries?.map((row) => row.groupKey)).toEqual([
      'pt-wang',
      'pt-wang',
      'pt-li',
    ])

    const school = merged.find((item) => item.type === 'CLASS_UNAVAILABLE')
    expect(school?.entries?.map((row) => row.id)).toEqual([20, 21])
    expect(school?.entries?.find((row) => row.id === 21)?.params).toMatchObject({ classId: 3 })
  })

  it('deletes only the dropped group', () => {
    const cards = cardsFromState(existing).filter((card) => card.groupKey !== 'pt-li')
    const merged = configsFromCards(existing, cards, { entryBackedTypes })

    const teacher = merged.find((item) => item.type === 'TEACHER_UNAVAILABLE')
    expect(teacher?.entries?.map((row) => row.id)).toEqual([10, 11])
    // The school group and the class-specific rule are untouched.
    expect(merged.find((item) => item.type === 'CLASS_UNAVAILABLE')?.entries).toHaveLength(2)
  })

  it('disables only the toggled group', () => {
    const cards = cardsFromState(existing).map((card) =>
      card.groupKey === 'pt-wang' ? { ...card, enabled: false } : card,
    )
    const merged = configsFromCards(existing, cards, { entryBackedTypes })

    const teacher = merged.find((item) => item.type === 'TEACHER_UNAVAILABLE')
    expect(teacher?.entries?.filter((row) => row.groupKey === 'pt-wang').every((row) => row.enabled === false)).toBe(true)
    expect(teacher?.entries?.find((row) => row.groupKey === 'pt-li')?.enabled).toBe(true)
    expect(merged.find((item) => item.type === 'CLASS_UNAVAILABLE')?.entries?.every((row) => row.enabled)).toBe(true)
  })

  it('drops a managed type when its last group is deleted', () => {
    const cards = cardsFromState(existing).filter((card) => card.type !== 'TEACHER_UNAVAILABLE')
    const merged = configsFromCards(existing, cards, { entryBackedTypes })
    expect(merged.find((item) => item.type === 'TEACHER_UNAVAILABLE')).toBeUndefined()
    expect(merged.find((item) => item.type === 'CLASS_UNAVAILABLE')).toBeDefined()
  })

  it('editing a card keeps the ids of slots that stay and inserts the new slot', () => {
    const cards = cardsFromState(existing)
    const teacherCard = cards.find((card) => card.groupKey === 'pt-wang')
    expect(teacherCard).toBeDefined()
    const edited = { ...teacherCard!, periodNos: [5, 7] }
    const merged = configsFromCards(
      existing,
      cards.map((card) => (card.key === edited.key ? edited : card)),
      { entryBackedTypes },
    )

    const rows = merged
      .find((item) => item.type === 'TEACHER_UNAVAILABLE')
      ?.entries?.filter((row) => row.groupKey === 'pt-wang')
    expect(rows?.map((row) => row.id)).toEqual([10, null])
    expect(rows?.map((row) => row.params.periodNo)).toEqual([5, 7])
  })

  it('adds a new sentence without disturbing the stored configuration', () => {
    const cards: RuleCard[] = [
      ...cardsFromState(existing),
      {
        key: 'new:1',
        groupKey: 'pt-new',
        type: TEACHER_UNAVAILABLE,
        teacherId: 8,
        dayOfWeek: 4,
        periodNos: [5, 6],
        strength: 'HARD',
        enabled: true,
        entryIds: {},
      },
    ]
    const merged = configsFromCards(existing, cards, { entryBackedTypes })

    const teacher = merged.find((item) => item.type === 'TEACHER_UNAVAILABLE')
    expect(teacher?.entries).toHaveLength(5)
    expect(teacher?.entries?.filter((row) => row.groupKey === 'pt-new')).toHaveLength(2)
    expect(teacher?.entries?.filter((row) => row.groupKey === 'pt-new').every((row) => row.id === null)).toBe(true)
  })

  it('passes a switch type through without inventing an entry list', () => {
    const merged = configsFromCards(
      [
        config('TEACHER_CONFLICT', []),
        config('CLASS_GAP_MINIMIZED', [], { enabled: false }),
      ],
      [],
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
        weight: 100,
        hard: true,
        enabled: false,
      },
    ])
  })
})
