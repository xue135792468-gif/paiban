import { describe, expect, it } from 'vitest'
import { entryCountOf } from './draftRules'
import { useConstraintDraft } from './useConstraintDraft'
import type { ConstraintConfigDto, ConstraintTypeDef, ObjectNames, ParamDef } from './types'

function names(
  overrides: Partial<Record<keyof ObjectNames, Map<number, string>>> = {},
): ObjectNames {
  return {
    teacherId: new Map(),
    classId: new Map(),
    courseId: new Map(),
    ...overrides,
  }
}

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
  { name: 'teacherId', label: '教师（留空表示全局默认）', type: 'ID', required: false },
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
    thresholdObjectField: 'teacherId',
    thresholdValueField: 'maxPerDay',
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
    expect(draft.conflictsOf().messages).toEqual([])
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

  it('阈值：全局默认与对象例外分别还原，并各自带稳定 id', () => {
    const draft = hydrated([
      {
        type: 'TEACHER_MAX_PER_DAY',
        params: { maxPerDay: 6 },
        weight: 60,
        hard: false,
        enabled: true,
        entries: [
          { id: 31, params: { maxPerDay: 6 } },
          { id: 32, params: { teacherId: 3, maxPerDay: 3 } },
        ],
      },
    ])
    const row = draft.rows.value.find((item) => item.code === 'TEACHER_MAX_PER_DAY')!
    expect(row.threshold?.globalValue).toBe(6)
    expect(row.threshold?.globalId).toBe(31)
    expect(row.threshold?.exceptions).toEqual([
      { key: expect.any(String), id: 32, objectId: 3, value: 3 },
    ])
    expect(draft.counts.value.entries).toBe(2)
  })

  it('阈值：只有旧 params 的响应按全局默认读取，超集不丢东西', () => {
    const draft = hydrated([
      { type: 'TEACHER_MAX_PER_DAY', params: { maxPerDay: 5 }, weight: 60, hard: false, enabled: true },
    ])
    const row = draft.rows.value.find((item) => item.code === 'TEACHER_MAX_PER_DAY')!
    expect(row.threshold?.globalValue).toBe(5)
    expect(row.threshold?.exceptions).toEqual([])
  })

  it('阈值：改全局、加例外、删例外都反映在 payload 与计数里', () => {
    const draft = hydrated()
    const row = () => draft.rows.value.find((item) => item.code === 'TEACHER_MAX_PER_DAY')!

    expect(draft.payload().find((config) => config.type === 'TEACHER_MAX_PER_DAY')).toBeUndefined()

    draft.setEnabled('TEACHER_MAX_PER_DAY', true)
    draft.setThresholdGlobal('TEACHER_MAX_PER_DAY', 6)
    expect(draft.counts.value.entries).toBe(3)

    draft.addThresholdException('TEACHER_MAX_PER_DAY')
    const exception = row().threshold!.exceptions[0]
    // 例句外没有对象也没有数值：不落库、不计数。
    expect(draft.counts.value.entries).toBe(3)

    draft.setThresholdExceptionObject('TEACHER_MAX_PER_DAY', exception.key, 7)
    draft.setThresholdExceptionValue('TEACHER_MAX_PER_DAY', exception.key, 3)
    expect(draft.counts.value.entries).toBe(4)

    const config = draft.payload().find((item) => item.type === 'TEACHER_MAX_PER_DAY')!
    expect(config.entries).toEqual([
      { id: null, params: { maxPerDay: 6 } },
      { id: null, params: { teacherId: 7, maxPerDay: 3 } },
    ])

    draft.removeThresholdException('TEACHER_MAX_PER_DAY', exception.key)
    expect(draft.counts.value.entries).toBe(3)
    draft.setThresholdGlobal('TEACHER_MAX_PER_DAY', null)
    expect(draft.counts.value.entries).toBe(2)
  })

  it('阈值：同一对象两条例外在保存前就被指出', () => {
    const draft = hydrated()
    draft.setThresholdGlobal('TEACHER_MAX_PER_DAY', 6)
    draft.addThresholdException('TEACHER_MAX_PER_DAY')
    draft.addThresholdException('TEACHER_MAX_PER_DAY')
    const exceptions = draft.rows.value.find(
      (item) => item.code === 'TEACHER_MAX_PER_DAY',
    )!.threshold!.exceptions
    draft.setThresholdExceptionObject('TEACHER_MAX_PER_DAY', exceptions[0].key, 7)
    draft.setThresholdExceptionValue('TEACHER_MAX_PER_DAY', exceptions[0].key, 3)
    draft.setThresholdExceptionObject('TEACHER_MAX_PER_DAY', exceptions[1].key, 7)
    draft.setThresholdExceptionValue('TEACHER_MAX_PER_DAY', exceptions[1].key, 4)

    expect(draft.conflictsOf(names({ teacherId: new Map([[7, '张伟']]) })).messages).toEqual([
      expect.stringContaining('同一作用域最多一条阈值'),
    ])
    expect(draft.warnings()).toEqual([])
  })

  it('阈值：例外缺对象时给出提示，不静默存成第二条全局默认', () => {
    const draft = hydrated()
    draft.addThresholdException('TEACHER_MAX_PER_DAY')
    const exception = draft.rows.value.find(
      (item) => item.code === 'TEACHER_MAX_PER_DAY',
    )!.threshold!.exceptions[0]
    draft.setThresholdExceptionValue('TEACHER_MAX_PER_DAY', exception.key, 4)

    expect(draft.warnings()).toEqual(['「教师一天最多 N 节」有例外没有选择教师'])
    expect(
      draft.payload().find((item) => item.type === 'TEACHER_MAX_PER_DAY')?.entries ?? [],
    ).toEqual([])
  })

  it('默认选中第一个条目型，且选择可切换', () => {
    const draft = hydrated()
    expect(draft.selectedCode.value).toBe('TEACHER_UNAVAILABLE')
    draft.select('TEACHER_CONFLICT')
    expect(draft.selected.value?.code).toBe('TEACHER_CONFLICT')
  })

  it('顶部计数与左侧分组、每类条目数出自同一份状态', () => {
    const draft = hydrated()
    draft.addEntry('TEACHER_UNAVAILABLE')
    const added = draft.rows.value[1].entries[2]
    draft.updateEntryValue('TEACHER_UNAVAILABLE', added.key, 'teacherId', 3)
    draft.updateEntryValue('TEACHER_UNAVAILABLE', added.key, 'dayOfWeek', 5)
    draft.updateEntryValue('TEACHER_UNAVAILABLE', added.key, 'periodNo', 1)
    draft.setEnabled('COURSE_NOT_IN_EVENING', true)

    const groups = draft.groups.value
    expect(groups.reduce((sum, group) => sum + group.counts.enabledTypes, 0)).toBe(
      draft.counts.value.enabledTypes,
    )
    expect(groups.reduce((sum, group) => sum + group.counts.entries, 0)).toBe(
      draft.counts.value.entries,
    )
    // 每类自己的条数加起来也等于总数：左导航逐项标签用的就是同一个 entryCountOf，
    // 不是 row.entries.length（那会把阈值和开关型算错）。
    expect(
      draft.visibleRows.value.reduce((sum, row) => sum + entryCountOf(row), 0),
    ).toBe(draft.counts.value.entries)
    // 顶部＝各分组之和＝每类之和，三者同源。
    expect(draft.visibleRows.value.reduce((sum, row) => sum + entryCountOf(row), 0)).toBe(
      groups.reduce((sum, group) => sum + group.counts.entries, 0),
    )
  })

  it('一类 20 条以上仍逐条计数，不截断也不合并', () => {
    const draft = useConstraintDraft()
    draft.hydrate(CATALOG, [], { isJunior: false })
    draft.select('TEACHER_UNAVAILABLE')
    for (let index = 0; index < 22; index += 1) {
      draft.addEntry('TEACHER_UNAVAILABLE')
      const entry = draft.rows.value[1].entries[index]
      draft.updateEntryValue('TEACHER_UNAVAILABLE', entry.key, 'teacherId', 100 + index)
      draft.updateEntryValue('TEACHER_UNAVAILABLE', entry.key, 'dayOfWeek', (index % 5) + 1)
      draft.updateEntryValue('TEACHER_UNAVAILABLE', entry.key, 'periodNo', (index % 8) + 1)
    }
    const row = draft.rows.value.find((item) => item.code === 'TEACHER_UNAVAILABLE')!
    expect(row.entries).toHaveLength(22)
    expect(draft.counts.value.entries).toBe(22)
    expect(
      draft.payload().find((item) => item.type === 'TEACHER_UNAVAILABLE')?.entries,
    ).toHaveLength(22)
  })

  it('重复条目在保存前就给对应类型亮红点（不用等服务器拒绝）', () => {
    const draft = hydrated()
    expect(draft.conflictsOf().codes).toEqual([])

    draft.select('TEACHER_UNAVAILABLE')
    draft.addEntry('TEACHER_UNAVAILABLE')
    const duplicate = draft.rows.value[1].entries[2]
    draft.updateEntryValue('TEACHER_UNAVAILABLE', duplicate.key, 'teacherId', 1)
    draft.updateEntryValue('TEACHER_UNAVAILABLE', duplicate.key, 'dayOfWeek', 3)
    draft.updateEntryValue('TEACHER_UNAVAILABLE', duplicate.key, 'periodNo', 2)

    // 重复条目自己已经在卡片上标出，只让左侧亮红点，不重复进预检条。
    expect(draft.conflictsOf().codes).toEqual(['TEACHER_UNAVAILABLE'])
    expect(draft.conflictsOf().messages).toEqual([])
  })

  it('预检条与红点读同一份结果：阈值标签按对象字段取名，不会被同 id 的课程冒充', () => {
    const draft = hydrated()
    draft.setThresholdGlobal('TEACHER_MAX_PER_DAY', 6)
    draft.addThresholdException('TEACHER_MAX_PER_DAY')
    draft.addThresholdException('TEACHER_MAX_PER_DAY')
    const exceptions = draft.rows.value.find(
      (item) => item.code === 'TEACHER_MAX_PER_DAY',
    )!.threshold!.exceptions
    draft.setThresholdExceptionObject('TEACHER_MAX_PER_DAY', exceptions[0].key, 5)
    draft.setThresholdExceptionValue('TEACHER_MAX_PER_DAY', exceptions[0].key, 3)
    draft.setThresholdExceptionObject('TEACHER_MAX_PER_DAY', exceptions[1].key, 5)
    draft.setThresholdExceptionValue('TEACHER_MAX_PER_DAY', exceptions[1].key, 4)

    const report = draft.conflictsOf(
      names({ teacherId: new Map([[5, '张伟']]), courseId: new Map([[5, '数学']]) }),
    )
    expect(report.messages).toHaveLength(1)
    expect(report.messages[0]).toContain('张伟')
    expect(report.messages[0]).not.toContain('数学')
    expect(report.codes).toEqual(['TEACHER_MAX_PER_DAY'])
  })

  it('模板套用后重新加载：条目与计数完整恢复，且不残留未保存状态', () => {
    const draft = hydrated()
    for (let index = 0; index < 3; index += 1) {
      draft.addEntry('TEACHER_UNAVAILABLE')
      const entry = draft.rows.value[1].entries[index + 2]
      draft.updateEntryValue('TEACHER_UNAVAILABLE', entry.key, 'teacherId', 7 + index)
      draft.updateEntryValue('TEACHER_UNAVAILABLE', entry.key, 'dayOfWeek', 2)
      draft.updateEntryValue('TEACHER_UNAVAILABLE', entry.key, 'periodNo', index + 1)
    }
    const before = draft.counts.value

    // 套用模板＝服务端写入后页面重新拉取并 hydrate 一次。
    const applied: ConstraintConfigDto[] = [
      CONFIGS[0],
      {
        type: 'TEACHER_UNAVAILABLE',
        params: {},
        weight: 100,
        hard: true,
        enabled: true,
        entries: [
          { id: 101, params: { teacherId: 1, dayOfWeek: 3, periodNo: 2 } },
          { id: 102, params: { teacherId: 2, dayOfWeek: 4, periodNo: 5 } },
          { id: 103, params: { teacherId: 7, dayOfWeek: 2, periodNo: 1 } },
          { id: 104, params: { teacherId: 8, dayOfWeek: 2, periodNo: 2 } },
          { id: 105, params: { teacherId: 9, dayOfWeek: 2, periodNo: 3 } },
        ],
      },
    ]
    draft.hydrate(CATALOG, applied, { isJunior: false })

    expect(draft.counts.value).toEqual(before)
    expect(draft.dirty.value).toBe(false)
  })
})
