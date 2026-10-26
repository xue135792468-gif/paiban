import { describe, expect, it } from 'vitest'
import { formatEntrySummary, formatSlot, slotCardSummary } from './entrySummary'
import type { EntryDraft, ParamDef } from './types'

const ctx = {
  teacherNames: new Map([
    [1, '张伟'],
    [2, '李娜'],
  ]),
  courseNames: new Map([
    [9, '数学'],
    [4, '语文'],
  ]),
}

const MEETING_PARAMS: ParamDef[] = [
  { name: 'subject', label: '学科（留空表示全部）', type: 'TEXT', required: false },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const COURSE_OPTIONAL_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程（留空表示全部）', type: 'ID', required: false },
]

const SPORT_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程（留空表示体育类）', type: 'ID', required: false },
]

const COURSE_REQUIRED_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
]

const ROOM_TYPE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'roomType', label: '教室类型', type: 'ROOM_TYPE', required: true },
]

const CONSECUTIVE_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'minConsecutive', label: '连排节数', type: 'INT', required: true, min: 2, max: 4 },
]

const MORNING_PARAMS: ParamDef[] = [
  { name: 'courseId', label: '课程', type: 'ID', required: true },
  { name: 'beforePeriodNo', label: '上午截止节次', type: 'INT', required: true, min: 2, max: 8 },
]

const TEACHER_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

const NO_FIRST_PARAMS: ParamDef[] = [{ name: 'teacherId', label: '教师', type: 'ID', required: true }]

const DAY_OFF_PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
]

function entry(values: Record<string, unknown>, slots?: EntryDraft['slots']): EntryDraft {
  return { key: 'e1', id: null, values, slots }
}

describe('条目摘要：十二类各一句话', () => {
  it('教师禁排/教师无课日/教师不排第一节', () => {
    expect(formatEntrySummary(TEACHER_PARAMS, { teacherId: 1, dayOfWeek: 3, periodNo: 2 }, ctx)).toBe(
      '张伟 · 周三 第2节',
    )
    expect(formatEntrySummary(DAY_OFF_PARAMS, { teacherId: 2, dayOfWeek: 4 }, ctx)).toBe('李娜 · 周四')
    expect(formatEntrySummary(NO_FIRST_PARAMS, { teacherId: 1 }, ctx)).toBe('张伟')
  })

  it('教研禁排：学科可空＝全部学科', () => {
    expect(
      formatEntrySummary(MEETING_PARAMS, { subject: '数学', dayOfWeek: 3, periodNo: 2 }, ctx),
    ).toBe('数学 · 周三 第2节')
    expect(formatEntrySummary(MEETING_PARAMS, { dayOfWeek: 3, periodNo: 2 }, ctx)).toBe(
      '全部学科 · 周三 第2节',
    )
  })

  it('课程类：可空课程显示全部课程/体育类，指定课程显示课名', () => {
    expect(formatEntrySummary(COURSE_REQUIRED_PARAMS, { courseId: 9 }, ctx)).toBe('数学')
    expect(formatEntrySummary(COURSE_OPTIONAL_PARAMS, {}, ctx)).toBe('全部课程')
    expect(formatEntrySummary(SPORT_PARAMS, {}, ctx)).toBe('体育类')
    expect(formatEntrySummary(SPORT_PARAMS, { courseId: 4 }, ctx)).toBe('语文')
    expect(formatEntrySummary(ROOM_TYPE_PARAMS, { courseId: 9, roomType: 'LAB' }, ctx)).toBe(
      '数学 · 实验室',
    )
    expect(
      formatEntrySummary(CONSECUTIVE_PARAMS, { courseId: 9, minConsecutive: 2 }, ctx),
    ).toBe('数学 · 连排2节')
    expect(formatEntrySummary(MORNING_PARAMS, { courseId: 9, beforePeriodNo: 4 }, ctx)).toBe(
      '数学 · 上午截止第4节',
    )
  })

  it('缺必填项单独标出，未知对象带上 id 而不是空白', () => {
    expect(formatEntrySummary(TEACHER_PARAMS, { dayOfWeek: 3 }, ctx)).toBe('未选教师 · 周三 未选节次')
    expect(formatEntrySummary(COURSE_REQUIRED_PARAMS, { courseId: 99 }, ctx)).toBe('课程#99')
  })
})

describe('偏好时段：一条卡片多个时段按「或」', () => {
  it('时段写法紧凑，多时段摘要说明命中任一即满足', () => {
    expect(formatSlot(1, 1)).toBe('周一第1节')
    expect(formatSlot(3, 2)).toBe('周三第2节')

    const single = entry({ teacherId: 1 }, [{ key: 's1', id: null, dayOfWeek: 1, periodNo: 1 }])
    expect(slotCardSummary(single, ctx)).toBe('张伟 · 周一第1节')

    const multi = entry({ teacherId: 1 }, [
      { key: 's1', id: null, dayOfWeek: 1, periodNo: 1 },
      { key: 's2', id: null, dayOfWeek: 3, periodNo: 2 },
      { key: 's3', id: null, dayOfWeek: 5, periodNo: 4 },
    ])
    expect(slotCardSummary(multi, ctx)).toBe(
      '张伟 · 周一第1节 或 周三第2节 或 周五第4节（命中任一即满足）',
    )
  })

  it('缺教师或缺时段时如实说明，不留空白', () => {
    expect(
      slotCardSummary(entry({}, [{ key: 's1', id: null, dayOfWeek: 1, periodNo: 1 }]), ctx),
    ).toBe('未选教师 · 周一第1节')
    expect(slotCardSummary(entry({ teacherId: 1 }, []), ctx)).toBe('张伟 · 还没有时段')
    expect(
      slotCardSummary(entry({ teacherId: 1 }, [{ key: 's1', id: null, dayOfWeek: 1, periodNo: null }]), ctx),
    ).toBe('张伟 · 周一未选节次')
  })
})
