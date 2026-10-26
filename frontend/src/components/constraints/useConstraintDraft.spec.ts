import { describe, expect, it } from 'vitest'
import { useConstraintDraft } from './useConstraintDraft'
import type { ConstraintConfigDto, ConstraintTypeDef, ParamDef } from './types'

const TEACHER_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const COURSE_PARAMS: ParamDef[] = [{ name: 'courseId', label: '课程', type: 'ID', required: true }]

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
    defaultHard: true,
    params: TEACHER_PARAMS,
  }),
  typeDef({
    code: 'COURSE_NOT_IN_EVENING',
    name: '课程不排晚自习',
    defaultHard: true,
    defaultWeight: 60,
    params: COURSE_PARAMS,
  }),
  typeDef({
    code: 'TEACHER_MEETING_BLOCKED',
    name: '教研活动禁排',
    category: 'PREFERENCE',
    categoryLabel: '教师偏好',
    params: TEACHER_PARAMS.slice(0, 2),
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

function hydrated() {
  const draft = useConstraintDraft()
  draft.hydrate(CATALOG, CONFIGS, { isJunior: false })
  return draft
}

describe('useConstraintDraft', () => {
  it('用目录构建全部类型，并把服务端条目还原成卡片', () => {
    const draft = hydrated()
    expect(draft.rows.value).toHaveLength(4)
    const unavailable = draft.rows.value.find((row) => row.code === 'TEACHER_UNAVAILABLE')!
    expect(unavailable.entries.map((entry) => entry.id)).toEqual([11, 12])
    expect(unavailable.entries[0].values).toEqual({ teacherId: 1, dayOfWeek: 3, periodNo: 2 })
    expect(draft.counts.value.entries).toBe(2)
    expect(draft.counts.value.enabledTypes).toBe(2)
  })

  it('旧单条 params 兼容成一条条目', () => {
    const draft = useConstraintDraft()
    draft.hydrate(
      CATALOG,
      [
        {
          type: 'TEACHER_UNAVAILABLE',
          params: { teacherId: 3, dayOfWeek: 2, periodNo: 1 },
          weight: 100,
          hard: true,
          enabled: true,
          entries: [],
        },
      ],
      { isJunior: false },
    )
    const unavailable = draft.rows.value.find((row) => row.code === 'TEACHER_UNAVAILABLE')!
    expect(unavailable.entries).toHaveLength(1)
    expect(unavailable.entries[0].values).toEqual({ teacherId: 3, dayOfWeek: 2, periodNo: 1 })
  })

  it('增删条目后计数跟着变，未保存状态也跟着变', () => {
    const draft = hydrated()
    expect(draft.dirty.value).toBe(false)

    draft.addEntry('TEACHER_UNAVAILABLE')
    const unavailable = () =>
      draft.rows.value.find((row) => row.code === 'TEACHER_UNAVAILABLE')!
    expect(unavailable().entries).toHaveLength(3)
    // 刚加的空卡片不带条件，不落库也不计数。
    expect(draft.counts.value.entries).toBe(2)
    expect(draft.dirty.value).toBe(false)

    draft.updateEntryValue('TEACHER_UNAVAILABLE', unavailable().entries[2].key, 'teacherId', 3)
    expect(draft.counts.value.entries).toBe(3)
    expect(draft.dirty.value).toBe(true)

    draft.removeEntry('TEACHER_UNAVAILABLE', unavailable().entries[2].key)
    expect(draft.counts.value.entries).toBe(2)
    expect(draft.dirty.value).toBe(false)
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

  it('开关型启用状态变化影响启用类型数，不影响条目数', () => {
    const draft = hydrated()
    draft.setEnabled('COURSE_NOT_IN_EVENING', true)
    expect(draft.counts.value.enabledTypes).toBe(3)
    expect(draft.counts.value.entries).toBe(2)
  })

  it('初中模式隐藏教研活动禁排，但它仍在提交范围内', () => {
    const draft = useConstraintDraft()
    draft.hydrate(
      CATALOG,
      [
        {
          type: 'TEACHER_MEETING_BLOCKED',
          params: { teacherId: 9 },
          weight: 100,
          hard: true,
          enabled: true,
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
      params: { teacherId: 9 },
      weight: 100,
      hard: true,
      enabled: true,
    })
    // 顶部口径仍按 24 类算，被隐藏的那一类不能被排除在外。
    expect(draft.counts.value).toEqual({
      enabledTypes: 1,
      totalTypes: 4,
      hardTypes: 1,
      entries: 1,
    })
  })

  it('默认选中教师禁排时段，且选择可切换', () => {
    const draft = hydrated()
    expect(draft.selectedCode.value).toBe('TEACHER_UNAVAILABLE')
    draft.select('TEACHER_CONFLICT')
    expect(draft.selected.value?.code).toBe('TEACHER_CONFLICT')
  })
})
