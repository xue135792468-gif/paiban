import { describe, expect, it } from 'vitest'
import { compareDrafts, lessonKeyMap, summaryLines } from './compareDiff'
import type { DraftLesson } from './types'

function lesson(overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id: 1,
    assignmentId: 10,
    courseId: 100,
    courseName: '数学',
    teacherId: 5,
    teacherName: '张老师',
    classId: 1,
    className: '高一1班',
    studentCount: 45,
    periodId: 1001,
    dayOfWeek: 1,
    periodNo: 1,
    periodLabel: '周一第1节',
    roomId: 1,
    locked: false,
    ...overrides,
  }
}

describe('compareDrafts', () => {
  it('未锁课节换到另一天，教师与天数去重统计', () => {
    const a = [
      lesson({ id: 1, periodId: 1001, dayOfWeek: 1, periodLabel: '周一第1节' }),
      lesson({ id: 2, assignmentId: 11, teacherId: 6, courseName: '语文', periodId: 1002, dayOfWeek: 1 }),
      lesson({ id: 3, assignmentId: 12, teacherId: 7, courseName: '英语', periodId: 1003, dayOfWeek: 1 }),
    ]
    const b = [
      lesson({ id: 11, periodId: 1001, dayOfWeek: 1, periodLabel: '周一第1节' }),
      lesson({ id: 12, assignmentId: 11, teacherId: 6, courseName: '语文', periodId: 2002, dayOfWeek: 3 }),
      lesson({ id: 13, assignmentId: 12, teacherId: 7, courseName: '英语', periodId: 1003, dayOfWeek: 1 }),
    ]

    const diff = compareDrafts(a, b)

    expect(diff.changedLessons).toBe(1)
    expect(diff.unlockedChanges).toBe(1)
    expect(diff.lockedChanges).toBe(0)
    expect(diff.affectedTeachers).toEqual([6])
    expect(diff.affectedDays).toEqual([1, 3])
    expect(diff.unpaired).toEqual([])
    expect(diff.changes[0].toPeriodId).toBe(2002)
    expect(diff.changes[0].locked).toBe(false)
  })

  it('锁定课节发生变化单独计数并给出摘要', () => {
    const a = [lesson({ id: 1, locked: true, periodId: 1001, dayOfWeek: 1 })]
    const b = [lesson({ id: 9, locked: true, periodId: 1002, dayOfWeek: 2 })]

    const diff = compareDrafts(a, b)

    expect(diff.lockedChanges).toBe(1)
    expect(diff.unlockedChanges).toBe(0)
    expect(diff.lockedUnchanged).toBe(0)
    expect(summaryLines(diff)[0]).toContain('锁定课节 0 节保持不变')
  })

  it('锁定课节保持不变时计入 lockedUnchanged', () => {
    const a = [
      lesson({ id: 1, locked: true }),
      lesson({ id: 2, assignmentId: 11, teacherId: 6, periodId: 1002, dayOfWeek: 2 }),
    ]
    const b = [
      lesson({ id: 20, locked: true }),
      lesson({ id: 21, assignmentId: 11, teacherId: 6, periodId: 2002, dayOfWeek: 4 }),
    ]

    const diff = compareDrafts(a, b)

    expect(diff.lockedUnchanged).toBe(1)
    expect(diff.unlockedChanges).toBe(1)
    expect(summaryLines(diff)[0]).toBe('未锁课节变化 1 节；锁定课节 1 节保持不变。')
  })

  it('任一侧缺失的授课身份标记为无法配对，不当成相同', () => {
    const a = [lesson({ id: 1 }), lesson({ id: 2, assignmentId: 11, teacherId: 6 })]
    const b = [lesson({ id: 12 }), lesson({ id: 13, assignmentId: 12, teacherId: 7 })]

    const diff = compareDrafts(a, b)

    expect(diff.changedLessons).toBe(0)
    expect(diff.unpaired).toEqual(['11#0', '12#0'])
    expect(summaryLines(diff)[1]).toContain('2 节课无法配对')
  })

  it('完全相同则没有变化', () => {
    const a = [lesson({ id: 1, locked: true }), lesson({ id: 2, assignmentId: 11 })]
    const b = [lesson({ id: 8, locked: true }), lesson({ id: 9, assignmentId: 11 })]

    const diff = compareDrafts(a, b)

    expect(diff.changes).toEqual([])
    expect(diff.unpaired).toEqual([])
    expect(diff.changedLessons).toBe(0)
    expect(diff.affectedTeachers).toEqual([])
    expect(diff.affectedDays).toEqual([])
  })

  it('服务端给出 lessonIndex 时以它为授课身份', () => {
    const a = [lesson({ id: 1, lessonIndex: 2, periodId: 1001 })]
    const b = [lesson({ id: 9, lessonIndex: 2, periodId: 1002 })]

    expect([...lessonKeyMap(a).keys()]).toEqual(['10#2'])
    expect(compareDrafts(a, b).changedLessons).toBe(1)
  })
})
