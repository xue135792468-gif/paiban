import { describe, expect, it } from 'vitest'
import {
  emptyThreshold,
  thresholdConflicts,
  thresholdEntries,
  thresholdEntryCount,
  thresholdShape,
  thresholdRelaxations,
  thresholdWarnings,
  thresholdRowValues,
} from './thresholdModel'
import type { ParamDef, TypeDraft } from './types'

const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师（留空表示全局默认）', type: 'ID', required: false },
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12, defaultValue: 4 },
]

function row(
  threshold: TypeDraft['threshold'],
  overrides: Partial<TypeDraft> = {},
): TypeDraft {
  return {
    code: 'TEACHER_MAX_PER_DAY',
    name: '教师一天最多 N 节',
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: THRESHOLD_PARAMS,
    thresholdObjectField: 'teacherId',
    thresholdValueField: 'maxPerDay',
    entries: [],
    threshold,
    enabled: true,
    hard: false,
    weight: 60,
    ...overrides,
  }
}

function exception(objectId: number | null, value: number | null, id: number | null = null) {
  return { key: `x-${objectId}-${value}`, id, objectId, value }
}

describe('放宽提示', () => {
  const names = {
    teacherId: new Map([[3, '王明'], [5, '李华']]),
    classId: new Map(),
    courseId: new Map(),
  }

  it('数值更大或从必须变成尽量时逐条说明实际采用值', () => {
    const relaxed = row(
      {
        globalId: null,
        globalValue: 4,
        globalEnabled: true,
        globalHard: true,
        globalWeight: null,
        exceptions: [
          { key: 'a', id: null, objectId: 3, value: 6, enabled: true, hard: false, weight: 30 },
          { key: 'b', id: null, objectId: 5, value: 3, enabled: true, hard: null, weight: null },
        ],
      },
      { hard: true },
    )
    expect(thresholdRelaxations(relaxed, names)).toEqual([
      '「教师一天最多 N 节」：王明 放宽为最多 6 节（尽量），不再按全局默认最多 4 节（必须）执行。',
    ])
  })

  it('没有全局默认时例外只是在增加限制，不算放宽', () => {
    const onlyExceptions = row({
      globalId: null,
      globalValue: null,
      globalEnabled: true,
      globalHard: null,
      globalWeight: null,
      exceptions: [
        { key: 'a', id: null, objectId: 3, value: 6, enabled: true, hard: false, weight: 30 },
      ],
    })
    expect(thresholdRelaxations(onlyExceptions, names)).toEqual([])
  })
})

describe('阈值作用域模型', () => {
  it('作用域来自目录声明，不猜参数个数', () => {
    const shape = thresholdShape(row(emptyThreshold()))
    expect(shape).not.toBeNull()
    expect(shape?.objectField).toBe('teacherId')
    expect(shape?.valueField).toBe('maxPerDay')
    // 没有声明作用域的行不是阈值。
    expect(
      thresholdShape({ params: THRESHOLD_PARAMS, thresholdObjectField: null, thresholdValueField: null }),
    ).toBeNull()
  })

  it('全局默认在前，对象例外在后；位置按整次请求连续编号', () => {
    const entries = thresholdEntries(
      row({
        globalId: 11,
        globalValue: 6,
        exceptions: [exception(3, 3, 12), exception(5, 2, 13)],
      }),
    )
    expect(entries.map((entry) => entry.position)).toEqual([1, 2, 3])
    expect(entries.map((entry) => entry.cardIndex)).toEqual([0, 1, 2])
    expect(entries.map((entry) => entry.id)).toEqual([11, 12, 13])
    expect(entries[0].params).toEqual({ maxPerDay: 6 })
    expect(entries[1].params).toEqual({ teacherId: 3, maxPerDay: 3 })
  })

  it('只有例外没有全局：照样只有例外那几条', () => {
    const entries = thresholdEntries(
      row({ globalId: null, globalValue: null, exceptions: [exception(4, 2)] }),
    )
    expect(entries).toHaveLength(1)
    expect(entries[0].params).toEqual({ teacherId: 4, maxPerDay: 2 })
  })

  it('缺对象或缺口径的例外不落库，避免悄悄变成第二条全局默认', () => {
    expect(thresholdEntries(row({ globalId: null, globalValue: null, exceptions: [exception(null, 3)] }))).toEqual([])
    expect(thresholdEntries(row({ globalId: null, globalValue: null, exceptions: [exception(3, null)] }))).toEqual([])
    expect(thresholdEntries(row({ globalId: null, globalValue: null, exceptions: [] }))).toEqual([])
  })

  it('每条条目计一条：全局与每个完整例外各一', () => {
    expect(thresholdEntryCount(row(emptyThreshold()))).toBe(0)
    expect(
      thresholdEntryCount(row({ globalId: null, globalValue: 6, exceptions: [exception(1, 3)] })),
    ).toBe(2)
    expect(
      thresholdEntryCount(row({ globalId: null, globalValue: null, exceptions: [exception(null, 3)] })),
    ).toBe(0)
  })

  it('缺对象的例外给出提示；完全空白的阈值不提示', () => {
    expect(thresholdWarnings(row(emptyThreshold()))).toEqual([])
    expect(
      thresholdWarnings(row({ globalId: null, globalValue: null, exceptions: [exception(null, 3)] })),
    ).toEqual(['「教师一天最多 N 节」有例外没有选择教师'])
    expect(
      thresholdWarnings(row({ globalId: null, globalValue: 6, exceptions: [exception(3, null)] })),
    ).toEqual(['「教师一天最多 N 节」有例外没填「每天最多节数」'])
  })

  it('同一对象两条例外、两条全局都被指出来', () => {
    const duplicateGlobal = thresholdConflicts(
      row({ globalId: null, globalValue: 6, exceptions: [] }),
    )
    expect(duplicateGlobal).toEqual([])

    const conflicts = thresholdConflicts(
      row({
        globalId: null,
        globalValue: 6,
        exceptions: [exception(3, 3), exception(3, 5)],
      }),
      { teacherId: new Map([[3, '张伟']]), classId: new Map(), courseId: new Map() },
    )
    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].message).toContain('张伟')
    expect(conflicts[0].message).toContain('同一作用域最多一条阈值')
    expect(conflicts[0].cardIndexes).toEqual([1, 2])
  })

  it('例外标签只按该类型自己的对象字段取名，同一 id 的课程不会冒充教师', () => {
    // Teacher 3 and course 3 both exist; a teacher limit must label its exception with
    // the teacher, never with the course that happens to share the id.
    const conflicts = thresholdConflicts(
      row({
        globalId: null,
        globalValue: 6,
        exceptions: [exception(3, 3), exception(3, 5)],
      }),
      {
        teacherId: new Map([[3, '张伟']]),
        classId: new Map([[3, '高一1班']]),
        courseId: new Map([[3, '数学']]),
      },
    )
    expect(conflicts).toHaveLength(1)
    expect(conflicts[0].message).toContain('张伟')
    expect(conflicts[0].message).not.toContain('数学')
    expect(conflicts[0].message).not.toContain('高一1班')
  })

  it('把一条条目的参数还原成对象与数值', () => {
    const target = row(emptyThreshold())
    expect(thresholdRowValues(target, { maxPerDay: 6 })).toEqual({ objectId: null, value: 6 })
    expect(thresholdRowValues(target, { teacherId: 3, maxPerDay: 2 })).toEqual({
      objectId: 3,
      value: 2,
    })
    expect(thresholdRowValues(target, {})).toEqual({ objectId: null, value: null })
  })
})
