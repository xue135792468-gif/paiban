import { describe, expect, it } from 'vitest'
import {
  MULTI_ENTRY_TYPES,
  buildSavePayload,
  countsOf,
  draftEntryCount,
  duplicateEntryIndexes,
  entrySummary,
  groupRows,
  isSwitchType,
  missingParamWarnings,
} from './draftRules'
import type { EntryDraft, ParamDef, TypeDraft } from './types'

const TEACHER_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const DAY_OFF_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
]

const COURSE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
]

function entry(key: string, values: Record<string, unknown>, id: number | null = null): EntryDraft {
  return { key, id, values }
}

function row(overrides: Partial<TypeDraft> & { code: string }): TypeDraft {
  return {
    name: overrides.code,
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: [],
    values: {},
    entries: [],
    enabled: false,
    hard: true,
    weight: 100,
    touched: false,
    ...overrides,
  }
}

function sampleRows(): TypeDraft[] {
  return [
    row({
      code: 'TEACHER_CONFLICT',
      category: 'CONFLICT',
      categoryLabel: '冲突类',
      enabled: true,
    }),
    row({ code: 'ROOM_CONFLICT', category: 'RESOURCE', categoryLabel: '资源类', enabled: true }),
    row({
      code: 'COURSE_NOT_IN_EVENING',
      category: 'PEDAGOGY',
      categoryLabel: '教学规律',
      params: COURSE_PARAMS,
      values: { courseId: 9 },
      enabled: true,
      hard: false,
      weight: 60,
    }),
    row({
      code: 'TEACHER_UNAVAILABLE',
      category: 'PREFERENCE',
      categoryLabel: '教师偏好',
      params: TEACHER_PARAMS,
      enabled: true,
      hard: false,
      weight: 80,
      entries: [
        entry('e1', { teacherId: 1, dayOfWeek: 3, periodNo: 2 }),
        entry('e2', { teacherId: 2, dayOfWeek: 4, periodNo: 5 }),
      ],
    }),
    row({
      code: 'TEACHER_DAY_OFF',
      category: 'PREFERENCE',
      categoryLabel: '教师偏好',
      params: DAY_OFF_PARAMS,
      values: { teacherId: 3, dayOfWeek: 2 },
      enabled: false,
      touched: true,
      hard: false,
      weight: 30,
    }),
  ]
}

describe('约束计数口径', () => {
  it('已启用类型、硬约束类型、具体条目分别计数，开关型不计入条目', () => {
    expect(countsOf(sampleRows())).toEqual({
      enabledTypes: 4,
      totalTypes: 5,
      // 只有两个开关型仍是硬约束：TEACHER_UNAVAILABLE 与 COURSE_NOT_IN_EVENING 是软约束。
      hardTypes: 2,
      // 2 条教师禁排 + 1 条课程条目 + 1 条关闭着的无课日（关闭只是不评估，条件仍在）；
      // 3 个开关型（其中两个启用）都不计入。
      entries: 4,
    })
  })

  it('关闭的类型仍保留其已配置条目，但不计入已启用类型', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        category: 'PREFERENCE',
        categoryLabel: '教师偏好',
        params: TEACHER_PARAMS,
        enabled: false,
        entries: [entry('e1', { teacherId: 1, dayOfWeek: 3, periodNo: 2 })],
      }),
    ]
    expect(countsOf(rows)).toEqual({
      enabledTypes: 0,
      totalTypes: 1,
      hardTypes: 0,
      entries: 1,
    })
  })

  it('开关型判定只看目录是否声明参数，不看是否启用', () => {
    expect(isSwitchType(row({ code: 'TEACHER_CONFLICT', enabled: true }))).toBe(true)
    expect(isSwitchType(row({ code: 'TEACHER_UNAVAILABLE', params: TEACHER_PARAMS }))).toBe(false)
  })

  it('多条目类型只计会落库的卡片，单参数类型有值就计一条（与启用状态无关）', () => {
    expect(draftEntryCount(row({ code: 'TEACHER_UNAVAILABLE', params: TEACHER_PARAMS }))).toBe(0)
    expect(
      draftEntryCount(
        row({
          code: 'TEACHER_UNAVAILABLE',
          params: TEACHER_PARAMS,
          entries: [entry('e1', { teacherId: 1 }), entry('e2', {})],
        }),
      ),
    ).toBe(1)
    expect(
      draftEntryCount(row({ code: 'X', params: COURSE_PARAMS, values: { courseId: 9 }, enabled: true })),
    ).toBe(1)
    expect(
      draftEntryCount(
        row({ code: 'X', params: COURSE_PARAMS, values: { courseId: 9 }, enabled: false, touched: true }),
      ),
    ).toBe(1)
    expect(draftEntryCount(row({ code: 'X', params: COURSE_PARAMS, values: {} }))).toBe(0)
    expect(draftEntryCount(row({ code: 'TEACHER_CONFLICT' }))).toBe(0)
  })

  it('只有教师禁排时段已开放多条目', () => {
    expect(MULTI_ENTRY_TYPES).toEqual(['TEACHER_UNAVAILABLE'])
  })
})

describe('分组', () => {
  it('四组按冲突类/资源类/教学规律/教师偏好排列，组内用同一计数口径', () => {
    const groups = groupRows(sampleRows())
    expect(groups.map((group) => group.categoryLabel)).toEqual([
      '冲突类',
      '资源类',
      '教学规律',
      '教师偏好',
    ])
    expect(groups[0].counts).toEqual({
      enabledTypes: 1,
      totalTypes: 1,
      hardTypes: 1,
      entries: 0,
    })
    expect(groups[3].counts).toEqual({
      enabledTypes: 1,
      totalTypes: 2,
      hardTypes: 0,
      entries: 3,
    })
  })
})

describe('条目摘要与重复提示', () => {
  const teacherNames = new Map([
    [1, '张伟'],
    [2, '李娜'],
  ])

  it('摘要按教师、星期、节次展示，缺项单独标出', () => {
    expect(entrySummary({ teacherId: 1, dayOfWeek: 3, periodNo: 2 }, teacherNames)).toBe(
      '张伟 · 周三 第2节',
    )
    expect(entrySummary({ teacherId: 2, dayOfWeek: 7 }, teacherNames)).toBe('李娜 · 周日 未选节次')
    expect(entrySummary({}, teacherNames)).toBe('未选教师 · 未选星期 未选节次')
  })

  it('完全相同的条目定位到先出现的那一条，空值与参数顺序不影响判定', () => {
    const entries = [
      entry('e1', { teacherId: 1, dayOfWeek: 3, periodNo: 2 }),
      entry('e2', { periodNo: 3, teacherId: 1, dayOfWeek: 3 }),
      entry('e3', { dayOfWeek: 3, periodNo: 2, teacherId: 1 }),
    ]
    expect(duplicateEntryIndexes(entries, TEACHER_PARAMS)).toEqual({ 2: 0 })
  })

  it('未填完的条目不算重复', () => {
    const entries = [entry('e1', { teacherId: 1, dayOfWeek: 3 }), entry('e2', { teacherId: 1, dayOfWeek: 3 })]
    expect(duplicateEntryIndexes(entries, TEACHER_PARAMS)).toEqual({})
  })
})

describe('保存前提示', () => {
  it('启用类型缺必填参数时逐条指出来，禁排条目带第几条', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        name: '教师禁排时段',
        params: TEACHER_PARAMS,
        enabled: true,
        entries: [
          entry('e1', { teacherId: 1, dayOfWeek: 3, periodNo: 2 }),
          entry('e2', { teacherId: 2, dayOfWeek: 4 }),
        ],
      }),
      row({
        code: 'COURSE_NOT_IN_EVENING',
        name: '课程不排晚自习',
        params: COURSE_PARAMS,
        enabled: true,
        values: {},
      }),
      row({ code: 'TEACHER_CONFLICT', name: '教师不冲突', enabled: true }),
    ]
    expect(missingParamWarnings(rows)).toEqual([
      '「教师禁排时段」第2条的「第几节」',
      '「课程不排晚自习」的「课程」',
    ])
  })

  it('空白条目/整行没填不提示，填了一半照样提示（关闭的类型也保存参数）', () => {
    const rows = [
      row({
        code: 'COURSE_NOT_IN_EVENING',
        name: '课程不排晚自习',
        params: COURSE_PARAMS,
        enabled: false,
        values: {},
      }),
      row({
        code: 'TEACHER_DAY_OFF',
        name: '教师无课日',
        params: DAY_OFF_PARAMS,
        enabled: false,
        values: { teacherId: 5 },
        touched: true,
      }),
      row({
        code: 'TEACHER_UNAVAILABLE',
        name: '教师禁排时段',
        params: TEACHER_PARAMS,
        enabled: true,
        entries: [entry('e1', {})],
      }),
    ]
    expect(missingParamWarnings(rows)).toEqual(['「教师无课日」的「星期」'])
  })
})

describe('保存 payload', () => {
  it('教师禁排发 entries，其余类型发 params，开关型发空 params', () => {
    const payload = buildSavePayload(sampleRows(), { isJunior: false })
    const byType = new Map(payload.map((config) => [config.type, config]))

    expect(byType.get('TEACHER_UNAVAILABLE')).toEqual({
      type: 'TEACHER_UNAVAILABLE',
      entries: [
        { id: null, params: { teacherId: 1, dayOfWeek: 3, periodNo: 2 } },
        { id: null, params: { teacherId: 2, dayOfWeek: 4, periodNo: 5 } },
      ],
      weight: 80,
      hard: false,
      enabled: true,
    })
    expect(byType.get('COURSE_NOT_IN_EVENING')).toEqual({
      type: 'COURSE_NOT_IN_EVENING',
      params: { courseId: 9 },
      weight: 60,
      hard: false,
      enabled: true,
    })
    expect(byType.get('TEACHER_CONFLICT')).toEqual({
      type: 'TEACHER_CONFLICT',
      params: {},
      weight: 100,
      hard: true,
      enabled: true,
    })
    // 多条目类型不能同时带 params：后端以 entries 为准，两个字段并存会让人以为有两条来源。
    expect(byType.get('TEACHER_UNAVAILABLE')).not.toHaveProperty('params')
    expect(byType.get('COURSE_NOT_IN_EVENING')).not.toHaveProperty('entries')
  })

  it('条目 id 原样回传，空白条目不提交', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        params: TEACHER_PARAMS,
        enabled: true,
        entries: [entry('e1', { teacherId: 7, dayOfWeek: 3, periodNo: 2 }, 42), entry('e2', {})],
      }),
    ]
    const [config] = buildSavePayload(rows, { isJunior: false })
    expect(config.entries).toEqual([{ id: 42, params: { teacherId: 7, dayOfWeek: 3, periodNo: 2 } }])
  })

  it('关闭的多条目类型保留条目，重新启用后照旧生效', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        params: TEACHER_PARAMS,
        enabled: false,
        entries: [entry('e1', { teacherId: 7, dayOfWeek: 3, periodNo: 2 })],
      }),
    ]
    const [config] = buildSavePayload(rows, { isJunior: false })
    expect(config.enabled).toBe(false)
    expect(config.entries).toHaveLength(1)
  })

  it('关闭且没人动过的单参数类型不因目录默认值凭空变成一条条目，也不提交', () => {
    const rows = [
      row({
        code: 'TEACHER_MAX_PER_DAY',
        name: '教师每日上限',
        params: [
          { name: 'maxPerDay', label: '上限', type: 'INT', required: true, defaultValue: 6 },
        ],
        values: { maxPerDay: 6 },
        enabled: false,
      }),
    ]
    expect(draftEntryCount(rows[0])).toBe(0)
    // 后端对「必填参数为空」的配置一律拒绝（不论启用与否），所以未配置的行干脆不发。
    expect(buildSavePayload(rows, { isJunior: false })).toEqual([])
  })

  it('关闭的单参数类型也保留参数：开关只决定是否评估，不删除条件', () => {
    const rows = [
      row({
        code: 'COURSE_NOT_IN_EVENING',
        params: COURSE_PARAMS,
        values: { courseId: 9 },
        enabled: false,
        touched: true,
      }),
    ]
    const [config] = buildSavePayload(rows, { isJunior: false })
    expect(config).toMatchObject({ params: { courseId: 9 }, enabled: false })
  })

  it('初中模式把必须/尽量换算成 100/50 权重', () => {
    const rows = [
      row({ code: 'TEACHER_UNAVAILABLE', params: TEACHER_PARAMS, enabled: true, hard: true, weight: 80 }),
      row({ code: 'TEACHER_DAY_OFF', params: DAY_OFF_PARAMS, enabled: true, hard: false, weight: 30 }),
    ]
    const payload = buildSavePayload(rows, { isJunior: true })
    expect(payload.map((config) => config.weight)).toEqual([100, 50])
  })

  it('隐藏的类型照样提交，避免在初中模式下抹掉高中配置', () => {
    const rows = [
      row({
        code: 'TEACHER_MEETING_BLOCKED',
        params: DAY_OFF_PARAMS,
        values: { teacherId: 5 },
        touched: true,
      }),
    ]
    expect(buildSavePayload(rows, { isJunior: true })).toEqual([
      {
        type: 'TEACHER_MEETING_BLOCKED',
        params: { teacherId: 5 },
        weight: 100,
        hard: true,
        enabled: false,
      },
    ])
  })
})
