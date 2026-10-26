import { describe, expect, it } from 'vitest'
import {
  duplicateMessages,
  entryErrorsOf,
  missingParamWarnings,
  scalarConflicts,
} from './entryChecks'
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

const ROOM_TYPE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'roomType', label: '教室类型', type: 'ROOM_TYPE', required: true },
]

const CONSECUTIVE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'minConsecutive', label: '连排节数', type: 'INT', required: true, min: 2, max: 4 },
]

const THRESHOLD_PARAMS: ParamDef[] = [
  { name: 'maxPerDay', label: '每天最多节数', type: 'INT', required: true, min: 1, max: 12 },
]

const ctx = {
  teacherNames: new Map([[1, '张伟']]),
  courseNames: new Map([
    [9, '数学'],
    [4, '语文'],
  ]),
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
    enabled: true,
    hard: true,
    weight: 100,
    ...overrides,
  }
}

describe('重复条目提示', () => {
  it('两条卡片完全相同：后一条指向先出现的那条', () => {
    const draftRow = row({
      code: 'COURSE_NOT_IN_EVENING',
      params: COURSE_PARAMS,
      entries: [entry('e1', { courseId: 9 }), entry('e2', { courseId: 9 })],
    })
    expect(duplicateMessages(draftRow)).toEqual({ 1: ['与第 1 条完全相同，保存会被拒绝。'] })
  })

  it('偏好时段同一卡片内重复的时段也指出来', () => {
    const draftRow = row({
      code: 'TEACHER_PREFERRED_SLOT',
      params: TEACHER_PARAMS,
      entries: [
        entry('e1', { teacherId: 1 }, [
          { key: 's1', id: null, dayOfWeek: 1, periodNo: 1 },
          { key: 's2', id: null, dayOfWeek: 1, periodNo: 1 },
        ]),
      ],
    })
    expect(duplicateMessages(draftRow)).toEqual({
      0: ['本条第2个时段与第1个时段相同，保存会被拒绝。'],
    })
  })

  it('显式「全部」条目重复同样拒绝（服务端把空条目也当一条）', () => {
    const draftRow = row({
      code: 'SAME_COURSE_ONCE_PER_DAY',
      params: SAME_COURSE_PARAMS,
      entries: [entry('e1', {}), entry('e2', {})],
    })
    expect(duplicateMessages(draftRow)).toEqual({ 1: ['与第 1 条完全相同，保存会被拒绝。'] })
  })

  it('没填完的条目不算重复', () => {
    const draftRow = row({
      code: 'TEACHER_DAY_OFF',
      params: TEACHER_PARAMS,
      entries: [entry('e1', { teacherId: 1 }), entry('e2', { teacherId: 1 })],
    })
    expect(duplicateMessages(draftRow)).toEqual({})
  })
})

describe('保存前必填提示', () => {
  it('逐条指出缺什么，条目带第几条', () => {
    const rows = [
      row({
        code: 'TEACHER_UNAVAILABLE',
        name: '教师禁排时段',
        params: TEACHER_PARAMS,
        entries: [
          entry('e1', { teacherId: 1, dayOfWeek: 3, periodNo: 2 }),
          entry('e2', { teacherId: 1, dayOfWeek: 4 }),
        ],
      }),
      row({ code: 'COURSE_NOT_IN_EVENING', name: '课程不排晚自习', params: COURSE_PARAMS, entries: [] }),
    ]
    expect(missingParamWarnings(rows)).toEqual(['「教师禁排时段」第2条的「第几节」'])
  })

  it('偏好时段提示缺教师、缺时段的卡片位置，没有时段的卡片单独提示', () => {
    const rows = [
      row({
        code: 'TEACHER_PREFERRED_SLOT',
        name: '教师偏好时段',
        params: TEACHER_PARAMS,
        entries: [
          entry('e1', {}, [{ key: 's1', id: null, dayOfWeek: 1, periodNo: 1 }]),
          entry('e2', { teacherId: 1 }, []),
          entry('e3', { teacherId: 1 }, [
            { key: 's1', id: null, dayOfWeek: 1, periodNo: 1 },
            { key: 's2', id: null, dayOfWeek: 3, periodNo: null },
          ]),
        ],
      }),
    ]
    expect(missingParamWarnings(rows)).toEqual([
      '「教师偏好时段」第1条的「教师」',
      '「教师偏好时段」第2条还没有时段',
      '「教师偏好时段」第3条第2个时段的「第几节」',
    ])
  })

  it('取消勾选「全部」却没选对象：提示补选，不能悄悄当成全部条目', () => {
    const rows = [
      row({
        code: 'SAME_COURSE_ONCE_PER_DAY',
        name: '同一课程一天最多一节',
        params: SAME_COURSE_PARAMS,
        entries: [{ key: 'e1', id: null, values: {}, allTarget: [] }],
      }),
    ]
    expect(missingParamWarnings(rows)).toEqual([
      '「同一课程一天最多一节」第1条：请选择目标，或勾选「全部课程」',
    ])
  })

  it('阈值不在这里提示：它的全局默认与例外由 thresholdModel 自己校验', () => {
    const rows = [
      row({
        code: 'TEACHER_MAX_PER_DAY',
        name: '教师一天最多 N 节',
        params: THRESHOLD_PARAMS,
        thresholdObjectField: 'teacherId',
        thresholdValueField: 'maxPerDay',
        enabled: true,
      }),
      row({
        code: 'SAME_COURSE_ONCE_PER_DAY',
        name: '同一课程一天最多一节',
        params: SAME_COURSE_PARAMS,
        // 可空对象的空卡片就是一条显式「全部」条目，不是错误。
        entries: [entry('e1', {})],
      }),
    ]
    expect(missingParamWarnings(rows)).toEqual([])
  })
})

describe('互斥条件预检', () => {
  it('同一课程两个教室类型：指出课程、条号与两个值', () => {
    const rows = [
      row({
        code: 'COURSE_REQUIRED_ROOM_TYPE',
        name: '课程指定教室类型',
        params: ROOM_TYPE_PARAMS,
        entries: [
          entry('e1', { courseId: 9, roomType: 'NORMAL' }),
          entry('e2', { courseId: 4, roomType: 'NORMAL' }),
          entry('e3', { courseId: 9, roomType: 'LAB' }),
        ],
      }),
    ]
    expect(scalarConflicts(rows, ctx)).toEqual([
      '「课程指定教室类型」：课程「数学」在第 1、3 条给了不同的「教室类型」（普通教室 / 实验室），同一目标不能并存，保存会被拒绝。',
    ])
  })

  it('同一课程两个连排节数、两个上午截止，同样预检', () => {
    const rows = [
      row({
        code: 'CONSECUTIVE_LESSONS',
        name: '连堂课连排',
        params: CONSECUTIVE_PARAMS,
        entries: [entry('e1', { courseId: 9, minConsecutive: 2 }), entry('e2', { courseId: 9, minConsecutive: 4 })],
      }),
      row({
        code: 'MAIN_SUBJECT_MORNING',
        name: '主科尽量排上午',
        params: [
          { name: 'courseId', label: '课程', type: 'ID', required: true },
          { name: 'beforePeriodNo', label: '上午截止节次', type: 'INT', required: true, min: 2, max: 8 },
        ],
        entries: [entry('e1', { courseId: 4, beforePeriodNo: 2 }), entry('e2', { courseId: 4, beforePeriodNo: 6 })],
      }),
    ]
    const conflicts = scalarConflicts(rows, ctx)
    expect(conflicts).toHaveLength(2)
    expect(conflicts[0]).toContain('课程「数学」在第 1、2 条给了不同的「连排节数」（2 / 4）')
    expect(conflicts[1]).toContain('课程「语文」在第 1、2 条给了不同的「上午截止节次」（2 / 6）')
  })

  it('不同课程、相同取值、留空对象都不算冲突', () => {
    const rows = [
      row({
        code: 'COURSE_REQUIRED_ROOM_TYPE',
        name: '课程指定教室类型',
        params: ROOM_TYPE_PARAMS,
        entries: [
          entry('e1', { courseId: 9, roomType: 'NORMAL' }),
          entry('e2', { courseId: 4, roomType: 'LAB' }),
        ],
      }),
    ]
    expect(scalarConflicts(rows, ctx)).toEqual([])
  })
})

describe('服务端错误落回卡片', () => {
  it('偏好时段第 3 条条目的错误回到第 2 条卡片（一条卡片对应多个条目位置）', () => {
    const draftRow = row({
      code: 'TEACHER_PREFERRED_SLOT',
      params: TEACHER_PARAMS,
      entries: [
        entry('e1', { teacherId: 1 }, [{ key: 's1', id: null, dayOfWeek: 1, periodNo: 1 }]),
        entry('e2', { teacherId: 1 }, [
          { key: 's1', id: null, dayOfWeek: 3, periodNo: 2 },
          { key: 's2', id: null, dayOfWeek: 5, periodNo: 9 },
        ]),
      ],
    })
    expect(
      entryErrorsOf(draftRow, 1, { 3: ['星期5第9节不在本学期作息范围内'] }),
    ).toEqual(['星期5第9节不在本学期作息范围内'])
    expect(entryErrorsOf(draftRow, 0, { 3: ['星期5第9节不在本学期作息范围内'] })).toEqual([])
  })
})
