import { describe, expect, it } from 'vitest'
import { useConstraintDraft } from './useConstraintDraft'
import type { ConstraintConfigDto, ConstraintTypeDef, ParamDef } from './types'

const TEACHER_SLOT_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const COURSE_PARAMS: ParamDef[] = [{ name: 'courseId', label: '课程', type: 'ID', required: true }]

const SAME_COURSE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程（留空表示全部）', type: 'ID', required: false },
]

const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12 },
]

function typeDef(overrides: Partial<ConstraintTypeDef> & { code: string }): ConstraintTypeDef {
  return {
    name: overrides.code,
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    description: '',
    defaultHard: true,
    defaultWeight: 100,
    params: [],
    ...overrides,
  }
}

const CATALOG: ConstraintTypeDef[] = [
  typeDef({ code: 'TEACHER_CONFLICT', name: '教师不冲突', category: 'CONFLICT', categoryLabel: '冲突类' }),
  typeDef({
    code: 'TEACHER_UNAVAILABLE',
    name: '教师禁排时段',
    category: 'PREFERENCE',
    categoryLabel: '教师偏好',
    params: TEACHER_SLOT_PARAMS,
  }),
  typeDef({
    code: 'TEACHER_PREFERRED_SLOT',
    name: '教师偏好时段',
    category: 'PREFERENCE',
    categoryLabel: '教师偏好',
    defaultHard: false,
    defaultWeight: 40,
    params: TEACHER_SLOT_PARAMS,
  }),
  typeDef({
    code: 'COURSE_NOT_IN_EVENING',
    name: '课程不排晚自习',
    defaultHard: true,
    defaultWeight: 60,
    params: COURSE_PARAMS,
  }),
  typeDef({
    code: 'SAME_COURSE_ONCE_PER_DAY',
    name: '同一课程一天最多一节',
    defaultHard: true,
    defaultWeight: 50,
    params: SAME_COURSE_PARAMS,
  }),
  typeDef({
    code: 'TEACHER_MAX_PER_DAY',
    name: '教师一天最多 N 节',
    defaultHard: false,
    defaultWeight: 60,
    params: THRESHOLD_PARAMS,
  }),
  typeDef({
    code: 'TEACHER_MEETING_BLOCKED',
    name: '教研活动禁排',
    category: 'PREFERENCE',
    categoryLabel: '教师偏好',
    params: [
      { name: 'subject', label: '学科（留空表示全部）', type: 'TEXT', required: false },
      { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
      { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
    ],
  }),
]

const CONFIGS: ConstraintConfigDto[] = [
  { type: 'TEACHER_CONFLICT', params: {}, weight: 100, hard: true, enabled: true },
  {
    type: 'TEACHER_UNAVAILABLE',
    params: { teacherId: 1, dayOfWeek: 3, periodNo: 2 },
    weight: 100,
    hard: true,
    enabled: true,
    entries: [
      { id: 11, params: { teacherId: 1, dayOfWeek: 3, periodNo: 2 } },
      { id: 12, params: { teacherId: 2, dayOfWeek: 4, periodNo: 5 } },
    ],
  },
]

function hydrated(configs: ConstraintConfigDto[] = CONFIGS) {
  const draft = useConstraintDraft()
  draft.hydrate(CATALOG, configs, { isJunior: false })
  return draft
}

describe('useConstraintDraft', () => {
  it('用目录构建全部类型，并把服务端条目还原成卡片', () => {
    const draft = hydrated()
    expect(draft.rows.value).toHaveLength(CATALOG.length)
    const unavailable = draft.rows.value.find((row) => row.code === 'TEACHER_UNAVAILABLE')!
    expect(unavailable.entries.map((entry) => entry.id)).toEqual([11, 12])
    expect(unavailable.entries[0].values).toEqual({ teacherId: 1, dayOfWeek: 3, periodNo: 2 })
    expect(draft.counts.value.entries).toBe(2)
    expect(draft.counts.value.enabledTypes).toBe(2)
  })

  it('偏好时段同一教师的多个条目还原成一条卡片、多个时段', () => {
    const draft = hydrated([
      {
        type: 'TEACHER_PREFERRED_SLOT',
        params: { teacherId: 1, dayOfWeek: 1, periodNo: 1 },
        weight: 40,
        hard: false,
        enabled: true,
        entries: [
          { id: 21, params: { teacherId: 1, dayOfWeek: 1, periodNo: 1 } },
          { id: 22, params: { teacherId: 1, dayOfWeek: 3, periodNo: 2 } },
          { id: 23, params: { teacherId: 2, dayOfWeek: 5, periodNo: 4 } },
        ],
      },
    ])
    const preferred = draft.rows.value.find((row) => row.code === 'TEACHER_PREFERRED_SLOT')!
    expect(preferred.entries).toHaveLength(2)
    expect(preferred.entries[0].values).toEqual({ teacherId: 1 })
    expect(preferred.entries[0].slots).toEqual([
      { key: expect.any(String), id: 21, dayOfWeek: 1, periodNo: 1 },
      { key: expect.any(String), id: 22, dayOfWeek: 3, periodNo: 2 },
    ])
    expect(preferred.entries[1].values).toEqual({ teacherId: 2 })
    expect(draft.counts.value.entries).toBe(3)
  })

  it('旧单条 params 不再给条目型合成条目：空列表就是未配置', () => {
    const draft = useConstraintDraft()
    draft.hydrate(
      CATALOG,
      [
        {
          type: 'COURSE_NOT_IN_EVENING',
          params: { courseId: 3 },
          weight: 60,
          hard: true,
          enabled: true,
          entries: [],
        },
      ],
      { isJunior: false },
    )
    const row = draft.rows.value.find((item) => item.code === 'COURSE_NOT_IN_EVENING')!
    expect(row.entries).toEqual([])
    expect(draft.counts.value.entries).toBe(0)
  })

  it('增删条目后计数跟着变，未保存状态也跟着变', () => {
    const draft = hydrated()
    expect(draft.dirty.value).toBe(false)

    draft.addEntry('TEACHER_UNAVAILABLE')
    const unavailable = () => draft.rows.value.find((row) => row.code === 'TEACHER_UNAVAILABLE')!
    expect(unavailable().entries).toHaveLength(3)
    // 刚加的空卡片不带条件，不落库也不计数。
    expect(draft.counts.value.entries).toBe(2)
    expect(draft.dirty.value).toBe(false)

    // 只填了教师还不算一条（服务端会因缺星期/节次拒绝），但已经算未保存改动。
    draft.updateEntryValue('TEACHER_UNAVAILABLE', unavailable().entries[2].key, 'teacherId', 3)
    expect(draft.counts.value.entries).toBe(2)
    expect(draft.dirty.value).toBe(true)

    draft.updateEntryValue('TEACHER_UNAVAILABLE', unavailable().entries[2].key, 'dayOfWeek', 2)
    draft.updateEntryValue('TEACHER_UNAVAILABLE', unavailable().entries[2].key, 'periodNo', 3)
    expect(draft.counts.value.entries).toBe(3)

    draft.removeEntry('TEACHER_UNAVAILABLE', unavailable().entries[2].key)
    expect(draft.counts.value.entries).toBe(2)
    expect(draft.dirty.value).toBe(false)
  })

  it('可空对象的空卡片直接是一条「全部课程」条目，算一条且待保存', () => {
    const draft = hydrated()
    draft.addEntry('SAME_COURSE_ONCE_PER_DAY')
    const row = () => draft.rows.value.find((item) => item.code === 'SAME_COURSE_ONCE_PER_DAY')!
    expect(row().entries).toHaveLength(1)
    expect(draft.counts.value.entries).toBe(3)
    expect(draft.dirty.value).toBe(true)
    expect(draft.payload().find((config) => config.type === 'SAME_COURSE_ONCE_PER_DAY')).toEqual({
      type: 'SAME_COURSE_ONCE_PER_DAY',
      entries: [{ id: null, params: {} }],
      weight: 50,
      hard: true,
      enabled: false,
    })
  })

  it('偏好时段新卡片自带一个空时段，加时段后按多条条目提交', () => {
    const draft = hydrated()
    draft.addEntry('TEACHER_PREFERRED_SLOT')
    const card = () => draft.rows.value.find((row) => row.code === 'TEACHER_PREFERRED_SLOT')!.entries[0]
    expect(card().slots).toHaveLength(1)

    draft.updateEntryValue('TEACHER_PREFERRED_SLOT', card().key, 'teacherId', 5)
    draft.updateSlot('TEACHER_PREFERRED_SLOT', card().key, card().slots![0].key, 'dayOfWeek', 1)
    draft.updateSlot('TEACHER_PREFERRED_SLOT', card().key, card().slots![0].key, 'periodNo', 2)
    draft.addSlot('TEACHER_PREFERRED_SLOT', card().key)
    draft.updateSlot('TEACHER_PREFERRED_SLOT', card().key, card().slots![1].key, 'dayOfWeek', 3)
    draft.updateSlot('TEACHER_PREFERRED_SLOT', card().key, card().slots![1].key, 'periodNo', 4)

    expect(draft.counts.value.entries).toBe(4)
    const config = draft.payload().find((item) => item.type === 'TEACHER_PREFERRED_SLOT')!
    expect(config.entries).toEqual([
      { id: null, params: { teacherId: 5, dayOfWeek: 1, periodNo: 2 } },
      { id: null, params: { teacherId: 5, dayOfWeek: 3, periodNo: 4 } },
    ])
  })

  it('改条目内容后摘要与新值同步，保存后回到未改动状态', () => {
    const draft = hydrated()
    const key = draft.rows.value.find((row) => row.code === 'TEACHER_UNAVAILABLE')!.entries[0].key
    draft.updateEntryValue('TEACHER_UNAVAILABLE', key, 'periodNo', 6)
    const entry = draft.rows.value.find((row) => row.code === 'TEACHER_UNAVAILABLE')!.entries[0]
    expect(entry.values.periodNo).toBe(6)
    expect(draft.dirty.value).toBe(true)

    draft.markSaved()
    expect(draft.dirty.value).toBe(false)
  })

  it('阈值类型仍用单值表单，启用状态变化影响启用类型数，不影响条目数', () => {
    const draft = hydrated()
    draft.setEnabled('TEACHER_MAX_PER_DAY', true)
    expect(draft.counts.value.enabledTypes).toBe(3)
    expect(draft.counts.value.entries).toBe(2)
  })

  it('互斥条件在保存前就能拿到，输入不被改写', () => {
    const draft = hydrated([
      {
        type: 'COURSE_NOT_IN_EVENING',
        params: { courseId: 9 },
        weight: 60,
        hard: true,
        enabled: true,
        entries: [{ id: 1, params: { courseId: 9 } }],
      },
    ])
    expect(draft.conflicts()).toEqual([])
  })

  it('初中模式隐藏教研活动禁排，但它仍在提交范围内', () => {
    const draft = useConstraintDraft()
    draft.hydrate(
      CATALOG,
      [
        {
          type: 'TEACHER_MEETING_BLOCKED',
          params: { dayOfWeek: 3, periodNo: 2 },
          weight: 100,
          hard: true,
          enabled: true,
          entries: [{ id: 5, params: { dayOfWeek: 3, periodNo: 2 } }],
        },
      ],
      { isJunior: true },
    )
    expect(draft.visibleRows.value.map((row) => row.code)).not.toContain('TEACHER_MEETING_BLOCKED')
    expect(draft.groups.value.flatMap((group) => group.rows.map((row) => row.code))).not.toContain(
      'TEACHER_MEETING_BLOCKED',
    )
    const payload = draft.payload()
    expect(payload.find((config) => config.type === 'TEACHER_MEETING_BLOCKED')).toEqual({
      type: 'TEACHER_MEETING_BLOCKED',
      entries: [{ id: 5, params: { dayOfWeek: 3, periodNo: 2 } }],
      weight: 100,
      hard: true,
      enabled: true,
    })
    // 顶部口径仍按全部类型算，被隐藏的那一类不能被排除在外。
    expect(draft.counts.value).toEqual({
      enabledTypes: 1,
      totalTypes: CATALOG.length,
      hardTypes: 1,
      entries: 1,
    })
  })

  it('默认选中第一个条目型，且选择可切换', () => {
    const draft = hydrated()
    expect(draft.selectedCode.value).toBe('TEACHER_UNAVAILABLE')
    draft.select('TEACHER_CONFLICT')
    expect(draft.selected.value?.code).toBe('TEACHER_CONFLICT')
  })
})
