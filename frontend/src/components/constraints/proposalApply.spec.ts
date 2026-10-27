import { describe, expect, it } from 'vitest'
import {
  controlsOf,
  findSameConditions,
  proposalThreshold,
  scalarConflict,
  sameControls,
  thresholdRelaxation,
} from './proposalApply'
import type { ConstraintProposal } from './useConstraintAi'
import type { EntryDraft, ParamDef, TypeDraft } from './types'

const SLOT_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const ROOM_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'roomType', label: '教室类型', type: 'ROOM_TYPE', required: true },
]

const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师（留空表示全局默认）', type: 'ID', required: false },
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12 },
]

function row(overrides: Partial<TypeDraft> & { code: string }): TypeDraft {
  return {
    name: overrides.code,
    description: '',
    category: 'PEDAGOGY',
    categoryLabel: '教学规律',
    params: [],
    entries: [],
    enabled: true,
    hard: true,
    weight: 100,
    ...overrides,
  }
}

function entry(values: Record<string, unknown>, slots?: EntryDraft['slots']): EntryDraft {
  return { key: `e-${Math.random()}`, id: null, values, slots, groupKey: null, enabled: true, hard: null, weight: null }
}

function proposal(overrides: Partial<ConstraintProposal>): ConstraintProposal {
  return {
    proposalId: 'p1',
    sourceText: '原话',
    type: 'TEACHER_UNAVAILABLE',
    typeName: '教师禁排时段',
    strength: 'HARD',
    effectiveHard: true,
    effectiveWeight: null,
    status: 'READY',
    explanation: '',
    reason: '',
    params: {},
    entries: [],
    candidates: [],
    ...overrides,
  }
}

describe('建议加入的比较规则', () => {
  it('强度映射为必须/尽量/继承，且不编造权重', () => {
    expect(controlsOf(proposal({ strength: 'HARD' }))).toEqual({ hard: true, weight: null, enabled: true })
    expect(controlsOf(proposal({ strength: 'SOFT' }))).toEqual({ hard: false, weight: null, enabled: true })
    expect(controlsOf(proposal({ strength: 'INHERIT' }))).toEqual({ hard: null, weight: null, enabled: true })
  })

  it('相同条件可定位到已有条目，强度不同不算完全相同', () => {
    const draftRow = row({
      code: 'TEACHER_UNAVAILABLE',
      params: SLOT_PARAMS,
      hard: true,
      entries: [
        entry({ teacherId: 5 }, [{ key: 's1', id: null, dayOfWeek: 3, periodNo: 1 }]),
      ],
    })
    const same = proposal({
      strength: 'SOFT',
      entries: [{ params: { teacherId: 5, dayOfWeek: 3, periodNo: 1 } }],
    })
    const match = findSameConditions(draftRow, same)
    expect(match).not.toBeNull()
    expect(sameControls(match!.card, draftRow, same)).toBe(false)
    const hard = { ...same, strength: 'HARD' as const, effectiveHard: true }
    expect(sameControls(match!.card, draftRow, hard)).toBe(true)
  })

  it('同一课程不同教室类型给出新旧差异', () => {
    const draftRow = row({
      code: 'COURSE_REQUIRED_ROOM_TYPE',
      params: ROOM_PARAMS,
      entries: [entry({ courseId: 7, roomType: 'LAB' })],
    })
    const conflict = scalarConflict(draftRow, proposal({
      type: 'COURSE_REQUIRED_ROOM_TYPE',
      entries: [{ params: { courseId: 7, roomType: 'PLAYGROUND' } }],
    }))
    expect(conflict?.message).toContain('LAB')
    expect(conflict?.message).toContain('PLAYGROUND')
  })

  it('阈值例外比全局更宽松时给出放宽说明', () => {
    const draftRow = row({
      code: 'TEACHER_MAX_PER_DAY',
      params: THRESHOLD_PARAMS,
      thresholdObjectField: 'teacherId',
      thresholdValueField: 'maxPerDay',
      hard: true,
      threshold: {
        globalId: null,
        globalValue: 4,
        globalEnabled: true,
        globalHard: true,
        globalWeight: null,
        exceptions: [],
      },
    })
    const relaxed = proposal({
      type: 'TEACHER_MAX_PER_DAY',
      strength: 'SOFT',
      entries: [{ params: { teacherId: 5, maxPerDay: 6 } }],
    })
    expect(proposalThreshold(draftRow, relaxed)).toMatchObject({
      objectField: 'teacherId',
      objectId: 5,
      value: 6,
    })
    expect(thresholdRelaxation(draftRow, relaxed, '王明')).toContain('放宽为最多 6 节')
    expect(thresholdRelaxation(draftRow, relaxed, '王明')).toContain('全局默认最多 4 节')
  })
})
