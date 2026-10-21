import { describe, expect, it } from 'vitest'
import {
  applySlotValue,
  buildTimetableGrid,
  buildTimetableGridFor,
  canCommit,
  createRequiresGrade,
  fieldLabel,
  lookupType,
  mappingGroups,
  remainingIssueCount,
  timetableClasses,
  timetableTeachers,
  type PreviewIssue,
  type PreviewRow,
} from './timetablePreview'

function row(values: Record<string, string>): PreviewRow {
  return { values, confidence: {}, lowConfidence: [] }
}

function timetableRow(overrides: Record<string, string> = {}): PreviewRow {
  return row({
    className: '一年级1班',
    dayOfWeek: '周一',
    periodNo: '1',
    courseName: '语文',
    teacherName: '张三',
    roomName: '101',
    ...overrides,
  })
}

function issue(overrides: Partial<PreviewIssue> = {}): PreviewIssue {
  return {
    row: 1,
    field: 'className',
    value: '一年级(1)班',
    candidates: ['一年级1班'],
    required: true,
    ...overrides,
  }
}

describe('课表预览网格映射', () => {
  it('把预览行映射成网格课节，保留课程、教师、教室和合成 id', () => {
    const grid = buildTimetableGrid(
      [
        timetableRow({ dayOfWeek: '周一', periodNo: '1' }),
        timetableRow({ dayOfWeek: '周二', periodNo: '第2节', courseName: '数学' }),
      ],
      '一年级1班',
    )

    expect(grid.lessons).toHaveLength(2)
    expect(grid.lessons[0]).toMatchObject({
      id: -1,
      className: '一年级1班',
      courseName: '语文',
      teacherName: '张三',
      roomName: '101',
      dayOfWeek: 1,
      periodNo: 1,
      periodLabel: '第1节',
    })
    expect(grid.lessons[1]).toMatchObject({
      id: -2,
      courseName: '数学',
      dayOfWeek: 2,
      periodNo: 2,
    })
    expect(grid.lessons[0].courseId).not.toBe(grid.lessons[1].courseId)
  })

  it('只显示所选班级，并给每个班级提供去重后的班级列表', () => {
    const rows = [
      timetableRow({ className: '一年级1班' }),
      timetableRow({ className: '一年级2班', courseName: '数学' }),
      timetableRow({ className: ' 一年级1班 ', dayOfWeek: '周二' }),
    ]

    expect(timetableClasses(rows)).toEqual(['一年级1班', '一年级2班'])
    const grid = buildTimetableGrid(rows, '一年级1班')
    expect(grid.lessons.map((lesson) => lesson.id)).toEqual([-1, -3])
  })

  it('按预览行去重生成节次行，没有时间时不编造时间', () => {
    const grid = buildTimetableGrid(
      [
        timetableRow({ dayOfWeek: '周一', periodNo: '1' }),
        timetableRow({ dayOfWeek: '周一', periodNo: '第1节', courseName: '数学' }),
        timetableRow({ dayOfWeek: '周二', periodNo: '3' }),
      ],
      '一年级1班',
    )

    expect(grid.periods.map((period) => [period.dayOfWeek, period.periodNo])).toEqual([
      [1, 1],
      [2, 3],
    ])
    expect(grid.periods[0].startTime).toBe('')
    expect(grid.periods[0].endTime).toBe('')
    expect(grid.periods[0].label).toBe('第1节')
  })

  it('同一班级同一节次出现多行时全部标为冲突', () => {
    const grid = buildTimetableGrid(
      [
        timetableRow({ courseName: '语文' }),
        timetableRow({ courseName: '数学' }),
        timetableRow({ dayOfWeek: '周二' }),
      ],
      '一年级1班',
    )

    expect([...grid.conflictLessonIds].sort((left, right) => left - right)).toEqual([-2, -1])
  })

  it('未匹配名称所在的行在网格里可标记', () => {
    const grid = buildTimetableGrid(
      [timetableRow(), timetableRow({ dayOfWeek: '周二' })],
      '一年级1班',
      [issue({ row: 2, field: 'courseName', value: '道法法治' })],
    )

    expect(grid.issueLessonIds).toEqual([-2])
  })

  it('无法定位到网格的行单独计数', () => {
    const grid = buildTimetableGrid(
      [timetableRow({ dayOfWeek: '周八', periodNo: '1' }), timetableRow({ dayOfWeek: '周二' })],
      '一年级1班',
    )

    expect(grid.lessons).toHaveLength(2)
    expect(grid.periods).toHaveLength(1)
    expect(grid.unplacedCount).toBe(1)
  })

  it('星期和节次的常见写法都能解析', () => {
    const grid = buildTimetableGrid(
      [
        timetableRow({ dayOfWeek: '星期一', periodNo: '第一节' }),
        timetableRow({ dayOfWeek: '星期天', periodNo: '第十节', courseName: '数学' }),
      ],
      '一年级1班',
    )

    expect(grid.lessons[0]).toMatchObject({ dayOfWeek: 1, periodNo: 1 })
    expect(grid.lessons[1]).toMatchObject({ dayOfWeek: 7, periodNo: 10 })
  })

  it('教师视角只显示该教师的课，并保留每节课自己的班级', () => {
    const rows = [
      timetableRow({ teacherName: '张三' }),
      timetableRow({ className: '一年级2班', teacherName: '李四', courseName: '数学' }),
      timetableRow({ className: '一年级2班', dayOfWeek: '周二', teacherName: '张三' }),
    ]

    expect(timetableTeachers(rows)).toEqual(['张三', '李四'])
    const grid = buildTimetableGridFor(rows, { teacherName: '张三' })
    expect(grid.lessons.map((lesson) => lesson.id)).toEqual([-1, -3])
    expect(grid.lessons.map((lesson) => lesson.className)).toEqual(['一年级1班', '一年级2班'])
  })

  it('14 个班 × 40 节的大表能一次映射完', () => {
    const days = ['周一', '周二', '周三', '周四', '周五']
    const rows: PreviewRow[] = []
    for (let classNo = 1; classNo <= 14; classNo++) {
      for (let slot = 0; slot < 40; slot++) {
        rows.push(
          timetableRow({
            className: `一年级${classNo}班`,
            dayOfWeek: days[Math.floor(slot / 8)],
            periodNo: String((slot % 8) + 1),
            courseName: `课程${slot + 1}`,
          }),
        )
      }
    }

    const started = performance.now()
    const grid = buildTimetableGrid(rows, '一年级1班')
    const elapsed = performance.now() - started

    expect(timetableClasses(rows)).toHaveLength(14)
    expect(grid.lessons).toHaveLength(40)
    expect(grid.periods).toHaveLength(40)
    expect(elapsed).toBeLessThan(500)
  })
})

describe('映射步骤状态', () => {
  it('同一字段同一原值合并成一条，保留所有行号与候选', () => {
    const groups = mappingGroups([
      issue({ row: 1, field: 'courseName', value: '道法法治', candidates: ['道德与法治'] }),
      issue({ row: 3, field: 'courseName', value: '道法法治', candidates: ['道德与法治', '道法'] }),
      issue({ row: 2, field: 'className', value: '一年级(1)班' }),
    ])

    expect(groups).toHaveLength(2)
    const course = groups.find((group) => group.field === 'courseName')!
    expect(course.rows).toEqual([1, 3])
    expect(course.candidates).toEqual(['道德与法治', '道法'])
    expect(course.label).toBe('课程')
    expect(course.kind).toBe('entity')
  })

  it('节次问题不是实体映射，而是行编辑', () => {
    const groups = mappingGroups([issue({ field: 'periodNo', value: '第九节', candidates: ['第1节'] })])

    expect(groups[0].kind).toBe('slot')
    expect(groups[0].label).toBe('节次')
  })

  it('有未解决项时不允许入库，并给出剩余数量', () => {
    const issues = [issue(), issue({ row: 2, field: 'teacherName', value: '李四' })]

    expect(canCommit(issues)).toBe(false)
    expect(remainingIssueCount(issues)).toBe(2)
    expect(canCommit([])).toBe(true)
    expect(remainingIssueCount([])).toBe(0)
  })

  it('节次修正只改选中的行，其余行保持原值', () => {
    const rows = [
      timetableRow({ periodNo: '第九节' }),
      timetableRow({ periodNo: '第九节', courseName: '数学' }),
      timetableRow({ periodNo: '第2节', courseName: '英语' }),
    ]

    const updated = applySlotValue(rows, 'periodNo', '第3节', [1, 2])

    expect(updated.map((item) => item.values.periodNo)).toEqual(['第3节', '第3节', '第2节'])
    expect(rows[0].values.periodNo).toBe('第九节')
  })

  it('字段到查询类型的对应关系', () => {
    expect(lookupType('className')).toBe('class')
    expect(lookupType('courseName')).toBe('course')
    expect(lookupType('teacherName')).toBe('teacher')
    expect(lookupType('roomName')).toBe('room')
    expect(lookupType('periodNo')).toBeNull()
    expect(fieldLabel('dayOfWeek')).toBe('星期')
    expect(fieldLabel('unknown')).toBe('unknown')
  })

  it('创建班级必须选年级，其他实体不需要', () => {
    expect(createRequiresGrade('className')).toBe(true)
    expect(createRequiresGrade('courseName')).toBe(false)
    expect(createRequiresGrade('teacherName')).toBe(false)
    expect(createRequiresGrade('roomName')).toBe(false)
  })
})
