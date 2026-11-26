import { describe, expect, it } from 'vitest'
import { computeTargetPeriodStates } from './targetAvailability'
import type { DraftLesson, DraftPeriod } from './types'

function period(dayOfWeek: number, periodNo: number, overrides: Partial<DraftPeriod> = {}): DraftPeriod {
  return {
    id: dayOfWeek * 100 + periodNo,
    dayOfWeek,
    periodNo,
    label: `第${periodNo}节`,
    startTime: '',
    endTime: '',
    ...overrides,
  }
}

function lesson(id: number, overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id,
    assignmentId: id,
    courseId: id,
    courseName: `课程${id}`,
    teacherId: id * 10,
    teacherName: `教师${id}`,
    classId: 1,
    className: '高一(1)班',
    studentCount: 40,
    periodId: 101,
    dayOfWeek: 1,
    periodNo: 1,
    locked: false,
    ...overrides,
  }
}

describe('computeTargetPeriodStates', () => {
  const periods = [period(1, 1), period(1, 2), period(1, 3), period(1, 4), period(1, 5)]

  it('不把其他班占用的时段判成不可用（12班场景保持可放置）', () => {
    const selected = lesson(1, { classId: 1, teacherId: 10, periodId: 101 })
    const otherClassLessons = [2, 3, 4, 5].map((classId, idx) =>
      lesson(10 + idx, {
        classId,
        className: `高一(${classId})班`,
        teacherId: 100 + idx,
        teacherName: `其他老师${idx}`,
        periodId: 102 + (idx % 3),
      }),
    )

    const states = computeTargetPeriodStates({
      periods,
      lessons: [selected, ...otherClassLessons],
      selectedLesson: selected,
    })

    expect(states[101]).toMatchObject({ status: 'current', disabled: true, label: '当前位置' })
    expect(states[102]).toMatchObject({ status: 'available', disabled: false })
    expect(states[103]).toMatchObject({ status: 'available', disabled: false })
    expect(states[104]).toMatchObject({ status: 'available', disabled: false })
    expect(states[105]).toMatchObject({ status: 'available', disabled: false })
  })

  it('区分本班已占用（可点击对调）、教师有课、禁排时段与已锁定', () => {
    const selected = lesson(1, { classId: 1, teacherId: 10, teacherName: '王老师', periodId: 101 })
    const sameClassOccupied = lesson(2, {
      classId: 1,
      teacherId: 20,
      teacherName: '李老师',
      courseName: '语文',
      periodId: 102,
    })
    const sameClassLocked = lesson(3, {
      classId: 1,
      teacherId: 30,
      courseName: '英语',
      periodId: 103,
      locked: true,
    })
    const teacherBusyOtherClass = lesson(4, {
      classId: 2,
      className: '高二(1)班',
      teacherId: 10,
      teacherName: '王老师',
      courseName: '数学',
      periodId: 104,
    })

    const states = computeTargetPeriodStates({
      periods,
      lessons: [selected, sameClassOccupied, sameClassLocked, teacherBusyOtherClass],
      selectedLesson: selected,
      blockedSlots: [{ classId: 1, periodId: 105, reason: '全校规则（周五教研）' }],
    })

    expect(states[102]).toMatchObject({ status: 'swappable', disabled: false, label: '本班已占用' })
    expect(states[102].tooltip).toContain('语文')
    expect(states[103]).toMatchObject({ status: 'locked', disabled: true, label: '已锁定' })
    expect(states[104]).toMatchObject({ status: 'teacher-busy', disabled: true, label: '教师有课' })
    expect(states[104].tooltip).toContain('高二(1)班')
    expect(states[105]).toMatchObject({ status: 'blocked', disabled: true, label: '禁排时段', tooltip: '全校规则（周五教研）' })
  })

  it('结合 moveTargets 时忽略单独的 CLASS_CONFLICT，保留可对调槽位但拦截教师禁排', () => {
    const selected = lesson(1, { classId: 1, teacherId: 10, periodId: 101 })
    const sameClassOccupied = lesson(2, { classId: 1, teacherId: 20, courseName: '语文', periodId: 102 })

    const states = computeTargetPeriodStates({
      periods: [period(1, 1), period(1, 2), period(1, 3)],
      lessons: [selected, sameClassOccupied],
      selectedLesson: selected,
      moveTargets: {
        102: { available: false, conflicts: [{ type: 'CLASS_CONFLICT', message: '本班此时已有课', lessonId: 2 }] },
        103: { available: false, conflicts: [{ type: 'TEACHER_UNAVAILABLE', message: '王老师周一下午禁排', lessonId: 1 }] },
      },
    })

    expect(states[102]).toMatchObject({ status: 'swappable', disabled: false, label: '本班已占用' })
    expect(states[103]).toMatchObject({
      status: 'blocked',
      disabled: true,
      label: '禁排时段',
      tooltip: '王老师周一下午禁排',
    })
  })
})
