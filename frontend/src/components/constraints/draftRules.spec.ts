import { describe, expect, it } from 'vitest'
import { buildSavePayload, countsOf, groupRows } from './draftRules'
import type { EntryDraft, ParamDef, TypeDraft } from './types'

const TEACHER_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const COURSE_PARAMS: ParamDef[] = [{ name: 'courseId', label: '课程', type: 'ID', required: true }]

const SAME_COURSE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程（留空表示全部）', type: 'ID', required: false },
]

// Ticket 10/04: a threshold declares the object field plus the number field.
const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师（留空表示全局默认）', type: 'ID', required: false },
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12 },
]

const THRESHOLD_SCOPE = {
  thresholdObjectField: 'teacherId',
  thresholdValueField: 'maxPerDay',
}

function thresholdDraft(
  globalValue: number | null,
  exceptions: Array<{ objectId: number | null; value: number | null }> = [],
): TypeDraft['threshold'] {
  return {
    globalId: null,
    globalValue,
    exceptions: exceptions.map((item, index) => ({
      key: `x${index}`,
      id: null,
      objectId: item.objectId,
      value: item.value,
    })),
  }
}

function entry(
  key: string,
  values: Record<string, unknown>,
  slots?: EntryDraft['slots'],
): EntryDraft {
  return { key, id: null, values, slots }
}

function row(overrides: Partial<TypeDraft> & { code: string }): TypeDraft {
  return {
    name: overrides.code,
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: [],
    entries: [],
    enabled: false,
    hard: true,
    weight: 100,
    ...overrides,
  }
}

function sampleRows(): TypeDraft[] {
  return [
    row({ code: 'TEACHER_CONFLICT', category: 'CONFLICT', categoryLabel: '冲突类', enabled: true }),
    row({ code: 'ROOM_CONFLICT', category: 'RESOURCE', categoryLabel: '资源类', enabled: true }),
    row({
      code: 'COURSE_NOT_IN_EVENING',
      category: 'PEDAGOGY',
      categoryLabel: '教学规律',
      params: COURSE_PARAMS,
      enabled: true,
      hard: false,
      weight: 60,
      entries: [entry('e1', { courseId: 9 })],
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
      code: 'TEACHER_MAX_PER_DAY',
      category: 'PEDAGOGY',
      categoryLabel: '教学规律',
      params: THRESHOLD_PARAMS,
      ...THRESHOLD_SCOPE,
      threshold: thresholdDraft(6, [{ objectId: 3, value: 3 }]),
      enabled: false,
      hard: false,
      weight: 60,
    }),
    row({
      code: 'SAME_COURSE_ONCE_PER_DAY',
      category: 'PEDAGOGY',
      categoryLabel: '教学规律',
      params: SAME_COURSE_PARAMS,
      enabled: true,
      hard: false,
      weight: 50,
      // 可空对象的空卡片是一条显式「全部课程」条目。
      entries: [entry('e1', {}), entry('e2', { courseId: 4 })],
    }),
  ]
}

describe('约束计数口径', () => {
  it('已启用类型、硬约束类型、具体条目分别计数，开关型不计入条目', () => {
    expect(countsOf(sampleRows())).toEqual({
      enabledTypes: 5,
      totalTypes: 6,
      // 两个开关型是硬约束；课程不排晚自习、教师禁排、同课一次、阈值都是软约束。
      hardTypes: 2,
      // 1 条课程 + 2 条教师禁排 + 2 条阈值（全局默认 + 一条例外，关闭但仍保留）+ 2 条同课一次。
      entries: 7,
    })
  })

  it('关闭的条目型仍保留其已配置条目，但不计入已启用类型', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        params: TEACHER_PARAMS,
        enabled: false,
        entries: [entry('e1', { teacherId: 1, dayOfWeek: 3, periodNo: 2 })],
      }),
    ]
    expect(countsOf(rows)).toEqual({ enabledTypes: 0, totalTypes: 1, hardTypes: 0, entries: 1 })
  })

  it('偏好时段一条卡片两个时段计两条：后端一条条目只放一个时段', () => {
    const rows = [
      row({
        code: 'TEACHER_PREFERRED_SLOT',
        params: TEACHER_PARAMS,
        enabled: true,
        hard: false,
        entries: [
          entry('e1', { teacherId: 1 }, [
            { key: 's1', id: null, dayOfWeek: 1, periodNo: 1 },
            { key: 's2', id: null, dayOfWeek: 3, periodNo: 2 },
          ]),
        ],
      }),
    ]
    expect(countsOf(rows).entries).toBe(2)
  })

  it('十二类目标型都按条目计数，不再被当成单值类型', () => {
    const rows = [
      row({
        code: 'COURSE_REQUIRED_ROOM_TYPE',
        params: [
          { name: 'courseId', label: '课程', type: 'ID', required: true },
          { name: 'roomType', label: '教室类型', type: 'ROOM_TYPE', required: true },
        ],
        enabled: true,
        entries: [
          entry('e1', { courseId: 1, roomType: 'NORMAL' }),
          entry('e2', { courseId: 2, roomType: 'LAB' }),
        ],
      }),
      row({
        code: 'PE_LAST_PERIOD_AVOID',
        params: [{ name: 'courseId', label: '课程（留空表示体育类）', type: 'ID', required: false }],
        enabled: true,
        entries: [entry('e1', {})],
      }),
    ]
    expect(countsOf(rows).entries).toBe(3)
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
    expect(groups[0].counts).toEqual({ enabledTypes: 1, totalTypes: 1, hardTypes: 1, entries: 0 })
    // 教学规律组：1 条课程 + 2 条阈值（全局默认 + 一条例外）+ 2 条同课一次。
    expect(groups[2].counts).toEqual({ enabledTypes: 2, totalTypes: 3, hardTypes: 0, entries: 5 })
    expect(groups[3].counts).toEqual({ enabledTypes: 1, totalTypes: 1, hardTypes: 0, entries: 2 })
  })
})

describe('保存 payload', () => {
  it('条目型与阈值一律发 entries，开关发空 params', () => {
    const payload = buildSavePayload(sampleRows(), { isJunior: false })
    const byType = new Map(payload.map((config) => [config.type, config]))

    expect(byType.get('COURSE_NOT_IN_EVENING')).toEqual({
      type: 'COURSE_NOT_IN_EVENING',
      entries: [{ id: null, params: { courseId: 9 } }],
      weight: 60,
      hard: false,
      enabled: true,
    })
    expect(byType.get('SAME_COURSE_ONCE_PER_DAY')).toEqual({
      type: 'SAME_COURSE_ONCE_PER_DAY',
      // 第一条是显式「全部课程」：空参数照发（后端把它存成一条空条目）。
      entries: [{ id: null, params: {} }, { id: null, params: { courseId: 4 } }],
      weight: 50,
      hard: false,
      enabled: true,
    })
    // 一条阈值 = 一条条目：全局默认（无对象字段）在前，对象例外在后。
    expect(byType.get('TEACHER_MAX_PER_DAY')).toEqual({
      type: 'TEACHER_MAX_PER_DAY',
      entries: [
        { id: null, params: { maxPerDay: 6 } },
        { id: null, params: { teacherId: 3, maxPerDay: 3 } },
      ],
      weight: 60,
      hard: false,
      enabled: false,
    })
    expect(byType.get('TEACHER_CONFLICT')).toEqual({
      type: 'TEACHER_CONFLICT',
      params: {},
      weight: 100,
      hard: true,
      enabled: true,
    })
    // 条目型与阈值都不能同时带 params：后端以 entries 为准，两个字段并存会让人以为有两条来源。
    expect(byType.get('COURSE_NOT_IN_EVENING')).not.toHaveProperty('params')
    expect(byType.get('TEACHER_MAX_PER_DAY')).not.toHaveProperty('params')
  })

  it('偏好时段一条卡片展开成多条条目，各自带稳定 id', () => {
    const rows = [
      row({
        code: 'TEACHER_PREFERRED_SLOT',
        params: TEACHER_PARAMS,
        enabled: true,
        entries: [
          entry('e1', { teacherId: 7 }, [
            { key: 's1', id: 31, dayOfWeek: 1, periodNo: 1 },
            { key: 's2', id: null, dayOfWeek: 3, periodNo: 2 },
          ]),
        ],
      }),
    ]
    const [config] = buildSavePayload(rows, { isJunior: false })
    expect(config.entries).toEqual([
      { id: 31, params: { teacherId: 7, dayOfWeek: 1, periodNo: 1 } },
      { id: null, params: { teacherId: 7, dayOfWeek: 3, periodNo: 2 } },
    ])
  })

  it('没填完/空白的卡片不提交，已填的部分照发让服务端定位错误', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        params: TEACHER_PARAMS,
        enabled: true,
        entries: [
          entry('e1', { teacherId: 7, dayOfWeek: 3, periodNo: 2 }),
          entry('e2', {}),
          entry('e3', { teacherId: 8 }),
        ],
      }),
    ]
    const [config] = buildSavePayload(rows, { isJunior: false })
    expect(config.entries).toEqual([
      { id: null, params: { teacherId: 7, dayOfWeek: 3, periodNo: 2 } },
      { id: null, params: { teacherId: 8 } },
    ])
  })

  it('关闭的条目型保留条目，重新启用后照旧生效', () => {
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

  it('目录默认值不算配置：没人动过的阈值不提交', () => {
    const rows = [
      row({
        code: 'TEACHER_MAX_PER_DAY',
        name: '教师每日上限',
        params: THRESHOLD_PARAMS,
        ...THRESHOLD_SCOPE,
        threshold: thresholdDraft(null),
        enabled: false,
      }),
    ]
    expect(buildSavePayload(rows, { isJunior: false })).toEqual([])
  })

  it('关闭的阈值也保留条目：开关只决定是否评估，不删除条件', () => {
    const rows = [
      row({
        code: 'TEACHER_MAX_PER_DAY',
        params: THRESHOLD_PARAMS,
        ...THRESHOLD_SCOPE,
        threshold: thresholdDraft(3),
        enabled: false,
      }),
    ]
    const [config] = buildSavePayload(rows, { isJunior: false })
    expect(config).toMatchObject({
      entries: [{ id: null, params: { maxPerDay: 3 } }],
      enabled: false,
    })
  })

  it('只有例外没有全局：只发例外，未列出的对象不设上限', () => {
    const rows = [
      row({
        code: 'TEACHER_MAX_PER_DAY',
        params: THRESHOLD_PARAMS,
        ...THRESHOLD_SCOPE,
        threshold: thresholdDraft(null, [{ objectId: 5, value: 2 }]),
        enabled: true,
      }),
    ]
    const [config] = buildSavePayload(rows, { isJunior: false })
    expect(config.entries).toEqual([{ id: null, params: { teacherId: 5, maxPerDay: 2 } }])
  })

  it('初中模式把必须/尽量换算成 100/50 权重', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        params: TEACHER_PARAMS,
        enabled: true,
        hard: true,
        weight: 80,
        entries: [entry('e1', { teacherId: 1, dayOfWeek: 3, periodNo: 2 })],
      }),
      row({
        code: 'TEACHER_DAY_OFF',
        params: TEACHER_PARAMS.slice(0, 2),
        enabled: true,
        hard: false,
        weight: 30,
        entries: [entry('e1', { teacherId: 1, dayOfWeek: 3 })],
      }),
    ]
    const payload = buildSavePayload(rows, { isJunior: true })
    expect(payload.map((config) => config.weight)).toEqual([100, 50])
  })

  it('隐藏的类型照样提交（含其条目），避免在初中模式下抹掉高中配置', () => {
    const rows = [
      row({
        code: 'TEACHER_MEETING_BLOCKED',
        params: [
          { name: 'subject', label: '学科（留空表示全部）', type: 'TEXT', required: false },
          { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
          { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
        ],
        enabled: true,
        entries: [entry('e1', { dayOfWeek: 3, periodNo: 2 })],
      }),
    ]
    expect(buildSavePayload(rows, { isJunior: true })).toEqual([
      {
        type: 'TEACHER_MEETING_BLOCKED',
        entries: [{ id: null, params: { dayOfWeek: 3, periodNo: 2 } }],
        weight: 100,
        hard: true,
        enabled: true,
      },
    ])
  })
})
