import { describe, expect, it } from 'vitest'
import { buildSwapPreview, teacherPaneLessons } from './swapPreview'
import type { DraftLesson, DraftPeriod, SwapCandidate } from './types'

function period(id: number, dayOfWeek: number, periodNo: number): DraftPeriod {
  return {
    id,
    dayOfWeek,
    periodNo,
    label: `第${periodNo}节`,
    section: 'MORNING',
    startTime: '08:00:00',
    endTime: '08:45:00',
  }
}

const PERIODS = [period(500, 1, 1), period(501, 1, 2), period(502, 2, 3), period(601, 3, 4)]

function lesson(overrides: Partial<DraftLesson> & { id: number }): DraftLesson {
  return {
    assignmentId: overrides.id,
    courseId: 10,
    courseName: '语文',
    teacherId: 100,
    teacherName: '张老师',
    classId: 1000,
    className: '高一1班',
    studentCount: 40,
    periodId: 500,
    dayOfWeek: 1,
    periodNo: 1,
    periodLabel: '周一第1节',
    roomId: 20,
    roomName: '101',
    ...overrides,
  }
}

const SOURCE = lesson({ id: 1, courseId: 10, courseName: '语文', periodId: 500, roomId: 20 })

function candidate(overrides: Partial<SwapCandidate> = {}): SwapCandidate {
  return {
    lessonId: 11,
    courseId: 11,
    courseName: '数学',
    subject: '数学',
    teacherId: 101,
    teacherName: '李老师',
    classId: 1000,
    className: '高一1班',
    gradeId: 1,
    gradeName: '高一',
    periodId: 501,
    dayOfWeek: 1,
    periodNo: 2,
    periodLabel: '周一第2节',
    roomId: 21,
    roomName: '102',
    available: true,
    conflicts: [],
    warnings: [],
    ...overrides,
  }
}

const OTHER = lesson({ id: 3, courseId: 12, courseName: '英语', periodId: 502, dayOfWeek: 2, periodNo: 3 })

function candidateLesson(overrides: Partial<DraftLesson> = {}) {
  return lesson({
    id: 11,
    teacherId: 101,
    teacherName: '李老师',
    periodId: 501,
    dayOfWeek: 1,
    periodNo: 2,
    roomId: 21,
    roomName: '102',
    ...overrides,
  })
}

describe('buildSwapPreview', () => {
  it('exchanges periods for an intra-class swap and relabels both cells', () => {
    const preview = buildSwapPreview([SOURCE, candidateLesson(), OTHER], SOURCE, candidate(), PERIODS, false)

    expect(preview.type).toBe('INTRA_CLASS')
    const movedSource = preview.after.find((item) => item.id === 1)!
    const movedCandidate = preview.after.find((item) => item.id === 11)!

    expect(movedSource.periodId).toBe(501)
    expect(movedSource.dayOfWeek).toBe(1)
    expect(movedSource.periodNo).toBe(2)
    expect(movedSource.periodLabel).toBe('周一第2节')
    expect(movedCandidate.periodId).toBe(500)
    expect(movedCandidate.dayOfWeek).toBe(1)
    expect(movedCandidate.periodNo).toBe(1)
    expect(movedSource.teacherId).toBe(100)
    expect(movedCandidate.teacherId).toBe(101)
  })

  it('keeps rooms with their lessons unless swapRooms is requested', () => {
    const withoutRooms = buildSwapPreview([SOURCE, candidateLesson()], SOURCE, candidate(), PERIODS, false)
    expect(withoutRooms.after.find((item) => item.id === 1)?.roomId).toBe(20)
    expect(withoutRooms.after.find((item) => item.id === 11)?.roomId).toBe(21)

    const withRooms = buildSwapPreview([SOURCE, candidateLesson()], SOURCE, candidate(), PERIODS, true)
    expect(withRooms.after.find((item) => item.id === 1)?.roomId).toBe(21)
    expect(withRooms.after.find((item) => item.id === 11)?.roomId).toBe(20)
  })

  it('exchanges teachers for a cross-class swap without moving periods or rooms', () => {
    const target = candidate({ classId: 2000, className: '高一2班', periodId: 601, dayOfWeek: 3, periodNo: 4 })
    const preview = buildSwapPreview(
      [SOURCE, candidateLesson({ periodId: 601, dayOfWeek: 3, periodNo: 4 })],
      SOURCE,
      target,
      PERIODS,
      false,
    )

    expect(preview.type).toBe('CROSS_CLASS')
    const movedSource = preview.after.find((item) => item.id === 1)!
    const movedCandidate = preview.after.find((item) => item.id === 11)!

    expect(movedSource.teacherId).toBe(101)
    expect(movedSource.teacherName).toBe('李老师')
    expect(movedSource.periodId).toBe(500)
    expect(movedCandidate.teacherId).toBe(100)
    expect(movedCandidate.teacherName).toBe('张老师')
    expect(movedCandidate.periodId).toBe(601)
    expect(movedSource.roomId).toBe(20)
    expect(movedCandidate.roomId).toBe(21)
  })

  it('leaves unrelated lessons and the before snapshot untouched', () => {
    const lessons = [SOURCE, candidateLesson(), OTHER]
    const preview = buildSwapPreview(lessons, SOURCE, candidate(), PERIODS, true)

    expect(preview.before).toEqual(lessons)
    expect(preview.after.find((item) => item.id === 3)).toEqual(OTHER)
    expect(lessons.find((item) => item.id === 1)?.periodId).toBe(500)
    expect(lessons.find((item) => item.id === 11)?.periodId).toBe(501)
  })

  it('ignores an unassigned source lesson that has no period to exchange', () => {
    const unassigned = lesson({ id: 1, periodId: null, dayOfWeek: null, periodNo: null })
    const preview = buildSwapPreview([unassigned], unassigned, candidate(), PERIODS, false)

    expect(preview.after.find((item) => item.id === 1)?.periodId).toBeNull()
  })
})

describe('teacherPaneLessons', () => {
  it('filters one teacher pane and follows the exchange after the swap', () => {
    const target = candidate({ classId: 2000, className: '高一2班', periodId: 601, dayOfWeek: 3, periodNo: 4 })
    const lessons = [
      SOURCE,
      lesson({ id: 11, teacherId: 101, periodId: 601, dayOfWeek: 3, periodNo: 4 }),
      OTHER,
    ]
    const preview = buildSwapPreview(lessons, SOURCE, target, PERIODS, false)

    expect(teacherPaneLessons(preview.before, 100).map((item) => item.id)).toEqual([1, 3])
    expect(teacherPaneLessons(preview.before, 101).map((item) => item.id)).toEqual([11])
    expect(teacherPaneLessons(preview.after, 100).map((item) => item.id)).toEqual([11, 3])
    expect(teacherPaneLessons(preview.after, 101).map((item) => item.id)).toEqual([1])
  })
})
