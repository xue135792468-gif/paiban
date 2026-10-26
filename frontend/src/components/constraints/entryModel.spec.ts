import { describe, expect, it } from 'vitest'
import {
  allTargetLabel,
  draftEntryCount,
  isEntryType,
  isSlotSetType,
  isSwitchType,
  isThresholdType,
  optionalTargetParam,
  plannedEntries,
  typeShape,
} from './entryModel'
import type { EntryDraft, ParamDef, TypeDraft } from './types'

const MEETING_PARAMS: ParamDef[] = [
  { name: 'subject', label: '学科（留空表示全部）', type: 'TEXT', required: false },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const SAME_COURSE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程（留空表示全部）', type: 'ID', required: false },
]

const SPORT_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程（留空表示体育类）', type: 'ID', required: false },
]

const CONSECUTIVE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'minConsecutive', label: '连排节数', type: 'INT', required: true, min: 2, max: 4 },
]

const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12 },
]

const SLOT_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

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
    values: {},
    entries: [],
    enabled: true,
    hard: true,
    weight: 100,
    touched: false,
    ...overrides,
  }
}

describe('类型形态由目录参数推断', () => {
  it('无参数＝开关型，单个整数＝阈值，其余＝条目型', () => {
    expect(typeShape(row({ code: 'TEACHER_CONFLICT' }))).toBe('SWITCH')
    expect(typeShape(row({ code: 'TEACHER_MAX_PER_DAY', params: THRESHOLD_PARAMS }))).toBe('THRESHOLD')
    expect(typeShape(row({ code: 'COURSE_NOT_IN_EVENING', params: SAME_COURSE_PARAMS }))).toBe('ENTRY')
    expect(typeShape(row({ code: 'TEACHER_UNAVAILABLE', params: SLOT_PARAMS }))).toBe('ENTRY')
    expect(typeShape(row({ code: 'CONSECUTIVE_LESSONS', params: CONSECUTIVE_PARAMS }))).toBe('ENTRY')

    expect(isSwitchType(row({ code: 'TEACHER_CONFLICT' }))).toBe(true)
    expect(isThresholdType(row({ code: 'TEACHER_MAX_PER_DAY', params: THRESHOLD_PARAMS }))).toBe(true)
    expect(isEntryType(row({ code: 'CONSECUTIVE_LESSONS', params: CONSECUTIVE_PARAMS }))).toBe(true)
    expect(isEntryType(row({ code: 'TEACHER_MAX_PER_DAY', params: THRESHOLD_PARAMS }))).toBe(false)
  })

  it('十二条目标型全部按条目型渲染，阈值与开关不在此列', () => {
    const entryBacked = [
      'TEACHER_UNAVAILABLE',
      'TEACHER_MEETING_BLOCKED',
      'COURSE_REQUIRED_ROOM_TYPE',
      'COURSE_NOT_IN_EVENING',
      'SAME_COURSE_ONCE_PER_DAY',
      'SPORT_NOT_FIRST_AFTERNOON',
      'CONSECUTIVE_LESSONS',
      'COURSE_SPREAD',
      'MAIN_SUBJECT_MORNING',
      'TEACHER_PREFERRED_SLOT',
      'TEACHER_NO_FIRST_PERIOD',
      'TEACHER_DAY_OFF',
      'PE_LAST_PERIOD_AVOID',
    ]
    entryBacked.forEach((code) => {
      const params = code === 'TEACHER_UNAVAILABLE' || code === 'TEACHER_PREFERRED_SLOT'
        ? SLOT_PARAMS
        : CONSECUTIVE_PARAMS
      expect(isEntryType(row({ code, params }))).toBe(true)
    })
    expect(isEntryType(row({ code: 'EVENING_MAX', params: THRESHOLD_PARAMS }))).toBe(false)
    expect(isEntryType(row({ code: 'ROOM_CONFLICT' }))).toBe(false)
  })

  it('偏好时段是唯一把一条卡片展开成多个后端条目的类型', () => {
    expect(isSlotSetType(row({ code: 'TEACHER_PREFERRED_SLOT', params: SLOT_PARAMS }))).toBe(true)
    // 参数声明与教师禁排完全相同，但语义是「任一时段即满足」而不是「这些时段都禁排」。
    expect(isSlotSetType(row({ code: 'TEACHER_UNAVAILABLE', params: SLOT_PARAMS }))).toBe(false)
  })
})

describe('「全部」目标入口', () => {
  it('从目录标签读出可空对象的名字，课程两类还区分体育类', () => {
    expect(allTargetLabel(SAME_COURSE_PARAMS[0])).toBe('全部课程')
    expect(allTargetLabel(SPORT_PARAMS[0])).toBe('体育类')
    expect(allTargetLabel(MEETING_PARAMS[0])).toBe('全部学科')
  })

  it('必填对象没有「全部」入口', () => {
    expect(optionalTargetParam(SLOT_PARAMS)).toBeNull()
    expect(allTargetLabel(SLOT_PARAMS[0])).toBe('教师')
    expect(optionalTargetParam(SAME_COURSE_PARAMS)?.name).toBe('courseId')
  })
})

describe('卡片展开成后端条目', () => {
  it('可空对象留空的卡片是一条显式「全部」条目，不是空白', () => {
    const planned = plannedEntries(
      row({ code: 'SAME_COURSE_ONCE_PER_DAY', params: SAME_COURSE_PARAMS, entries: [entry('e1', {})] }),
    )
    expect(planned).toEqual([
      { position: 1, cardIndex: 0, id: null, params: {}, complete: true },
    ])
  })

  it('必填对象没填的卡片不落库，也不算一条', () => {
    const draftRow = row({
      code: 'COURSE_NOT_IN_EVENING',
      params: [{ name: 'courseId', label: '课程', type: 'ID', required: true }],
      entries: [entry('e1', {})],
    })
    expect(plannedEntries(draftRow)).toEqual([])
    expect(draftEntryCount(draftRow)).toBe(0)
  })

  it('每条卡片一条条目，位置按请求顺序编号（服务端错误定位用）', () => {
    const draftRow = row({
      code: 'COURSE_NOT_IN_EVENING',
      params: [{ name: 'courseId', label: '课程', type: 'ID', required: true }],
      entries: [entry('e1', { courseId: 9 }), entry('e2', { courseId: 4 })],
    })
    expect(plannedEntries(draftRow).map((item) => [item.position, item.params])).toEqual([
      [1, { courseId: 9 }],
      [2, { courseId: 4 }],
    ])
    expect(draftEntryCount(draftRow)).toBe(2)
  })

  it('偏好时段一条卡片里的每个时段都是一条后端条目，共享同一个教师', () => {
    const draftRow = row({
      code: 'TEACHER_PREFERRED_SLOT',
      params: SLOT_PARAMS,
      entries: [
        entry('e1', { teacherId: 5 }, [
          { key: 's1', id: 31, dayOfWeek: 1, periodNo: 1 },
          { key: 's2', id: null, dayOfWeek: 3, periodNo: 2 },
        ]),
      ],
    })
    expect(plannedEntries(draftRow)).toEqual([
      {
        position: 1,
        cardIndex: 0,
        slotIndex: 0,
        id: 31,
        params: { teacherId: 5, dayOfWeek: 1, periodNo: 1 },
        complete: true,
      },
      {
        position: 2,
        cardIndex: 0,
        slotIndex: 1,
        id: null,
        params: { teacherId: 5, dayOfWeek: 3, periodNo: 2 },
        complete: true,
      },
    ])
    expect(draftEntryCount(draftRow)).toBe(2)
  })

  it('偏好时段缺教师或缺节次的时段不完整：提示用，不计数', () => {
    const draftRow = row({
      code: 'TEACHER_PREFERRED_SLOT',
      params: SLOT_PARAMS,
      entries: [
        entry('e1', {}, [{ key: 's1', id: null, dayOfWeek: 1, periodNo: null }]),
        entry('e2', { teacherId: 5 }, []),
      ],
    })
    expect(plannedEntries(draftRow)).toEqual([
      {
        position: 1,
        cardIndex: 0,
        slotIndex: 0,
        id: null,
        params: { dayOfWeek: 1 },
        complete: false,
      },
    ])
    expect(draftEntryCount(draftRow)).toBe(0)
  })

  it('取消勾选「全部」却没选对象：不落库也不计数（避免被服务端存成全部条目）', () => {
    const draftRow = row({
      code: 'SAME_COURSE_ONCE_PER_DAY',
      params: SAME_COURSE_PARAMS,
      entries: [{ key: 'e1', id: null, values: {}, allTarget: [] }],
    })
    expect(plannedEntries(draftRow)).toEqual([])
    expect(draftEntryCount(draftRow)).toBe(0)
  })

  it('勾选「全部」会清掉残值：条目参数里不再带对象', () => {
    const draftRow = row({
      code: 'SAME_COURSE_ONCE_PER_DAY',
      params: SAME_COURSE_PARAMS,
      entries: [{ key: 'e1', id: null, values: { courseId: 9 }, allTarget: ['courseId'] }],
    })
    expect(plannedEntries(draftRow).map((item) => item.params)).toEqual([{}])
  })

  it('开关型永远没有条目', () => {
    expect(draftEntryCount(row({ code: 'TEACHER_CONFLICT', enabled: true }))).toBe(0)
  })
})
