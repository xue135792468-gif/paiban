import { describe, expect, it } from 'vitest'
import {
  affectedLessonIds,
  applyProjectionToLessons,
  lessonsForTeacher,
  periodCaption,
  projectCyclePlan,
  teacherNameOfId,
} from './cyclePlanPreview'
import type { CyclePlan, CyclePlanMove, DraftLesson, DraftPeriod } from './types'

function period(id: number, dayOfWeek: number, periodNo: number): DraftPeriod {
  return { id, dayOfWeek, periodNo, label: `第${periodNo}节`, startTime: '', endTime: '' }
}

function lesson(
  id: number,
  teacherId: number,
  periodId: number,
  overrides: Partial<DraftLesson> = {},
): DraftLesson {
  const matched = PERIODS.find((item) => item.id === periodId)
  return {
    id,
    assignmentId: id,
    courseId: id,
    courseName: `课程${id}`,
    teacherId,
    teacherName: `教师${teacherId}`,
    classId: 1,
    className: '高一(1)班',
    studentCount: 40,
    periodId,
    dayOfWeek: matched?.dayOfWeek ?? null,
    periodNo: matched?.periodNo ?? null,
    locked: false,
    ...overrides,
  }
}

const PERIODS: DraftPeriod[] = [
  period(101, 1, 1),
  period(102, 1, 2),
  period(103, 1, 3),
  period(201, 2, 1),
  period(202, 2, 2),
]

function plan(moves: CyclePlanMove[]): CyclePlan {
  return {
    token: 'tok',
    kind: 'CYCLE',
    moves,
    movedLessons: moves.length,
    affectedTeachers: new Set(moves.map((move) => move.teacherId)).size,
    affectedDays: 1,
    softPenaltyAfter: 0,
    softPenaltyDelta: 0,
    expiresAt: '2026-01-01T00:00:00Z',
  }
}

/** Two classes of teacher 9 exchange Monday 1 and Monday 2; teacher 10 moves Tuesday 1 → 2. */
const swapLessons = [
  lesson(11, 9, 101, { className: '高一(1)班' }),
  lesson(12, 9, 102, { className: '高一(2)班' }),
  lesson(13, 10, 201, { className: '高二(3)班' }),
]

const swapPlan = plan([
  { lessonId: 11, fromPeriodId: 101, toPeriodId: 102, teacherId: 9, courseId: 1, classId: 1 },
  { lessonId: 12, fromPeriodId: 102, toPeriodId: 101, teacherId: 9, courseId: 2, classId: 2 },
  { lessonId: 13, fromPeriodId: 201, toPeriodId: 202, teacherId: 10, courseId: 3, classId: 3 },
])

describe('projectCyclePlan', () => {
  it('整组 moves 同时投影，互换后一节不丢', () => {
    const projection = projectCyclePlan(swapLessons, PERIODS, swapPlan)
    const after = applyProjectionToLessons(swapLessons, projection)

    expect(projection.projectedCount).toBe(3)
    expect(projection.unmatchedCount).toBe(0)
    expect(after).toHaveLength(swapLessons.length)
    expect(after.find((item) => item.id === 11)?.periodId).toBe(102)
    expect(after.find((item) => item.id === 12)?.periodId).toBe(101)
    expect(after.find((item) => item.id === 13)?.periodId).toBe(202)
    // The grid positions come from the target period, not from the move payload.
    expect(after.find((item) => item.id === 11)).toMatchObject({ dayOfWeek: 1, periodNo: 2 })
  })

  it('不修改传入的草稿与方案', () => {
    const lessons = swapLessons.map((item) => ({ ...item }))
    const snapshot = JSON.stringify(lessons)
    const planSnapshot = JSON.stringify(swapPlan)

    const projection = projectCyclePlan(lessons, PERIODS, swapPlan)
    applyProjectionToLessons(lessons, projection)

    expect(JSON.stringify(lessons)).toBe(snapshot)
    expect(JSON.stringify(swapPlan)).toBe(planSnapshot)
  })

  it('同一教师多节移动全部命中，按教师 id 汇总', () => {
    const projection = projectCyclePlan(swapLessons, PERIODS, swapPlan)

    expect(projection.teacherIds).toEqual([9, 10])
    expect(affectedLessonIds(projection, 9)).toEqual([11, 12])
    expect(lessonsForTeacher(applyProjectionToLessons(swapLessons, projection), 9)).toHaveLength(2)
  })

  it('同名不同 id 的教师不会混成一个人', () => {
    const lessons = [
      lesson(21, 9, 101, { teacherName: '张老师', className: '高一(1)班' }),
      lesson(22, 99, 103, { teacherName: '张老师', className: '高一(4)班' }),
    ]
    const twoTeachers = plan([
      { lessonId: 21, fromPeriodId: 101, toPeriodId: 103, teacherId: 9, courseId: 1, classId: 1 },
      { lessonId: 22, fromPeriodId: 103, toPeriodId: 101, teacherId: 99, courseId: 2, classId: 4 },
    ])

    const projection = projectCyclePlan(lessons, PERIODS, twoTeachers)
    const after = applyProjectionToLessons(lessons, projection)

    expect(projection.teacherIds).toEqual([9, 99])
    expect(teacherNameOfId(projection, 9)).toBe('张老师')
    expect(lessonsForTeacher(after, 9).map((item) => item.id)).toEqual([21])
    expect(lessonsForTeacher(after, 99).map((item) => item.id)).toEqual([22])
  })

  it('课节位置与方案预期不符时单列未投影并说明原因', () => {
    const moved = swapLessons.map((item) => (item.id === 11 ? { ...item, periodId: 103, dayOfWeek: 1, periodNo: 3 } : item))
    const projection = projectCyclePlan(moved, PERIODS, swapPlan)

    expect(projection.projectedCount).toBe(2)
    expect(projection.unmatchedCount).toBe(1)
    const stale = projection.moves.find((row) => row.move.lessonId === 11)
    expect(stale?.projected).toBe(false)
    expect(stale?.reason).toContain('不在方案预期的位置')
    // The stale move is left out rather than guessed at.
    expect(applyProjectionToLessons(moved, projection).find((item) => item.id === 11)?.periodId).toBe(103)
  })

  it('课节或目标时段不存在时同样单列，不静默计入', () => {
    const ghost = plan([
      { lessonId: 999, fromPeriodId: 101, toPeriodId: 102, teacherId: 9, courseId: 1, classId: 1 },
      { lessonId: 13, fromPeriodId: 201, toPeriodId: 777, teacherId: 10, courseId: 3, classId: 3 },
    ])

    const projection = projectCyclePlan(swapLessons, PERIODS, ghost)

    expect(projection.projectedCount).toBe(0)
    expect(projection.unmatchedCount).toBe(2)
    expect(projection.moves.map((row) => row.reason)).toEqual([
      '课节已不在当前草稿',
      '目标节次不在当前作息表',
    ])
  })

  it('空方案与缺字段方案都投影成空结果', () => {
    expect(projectCyclePlan(swapLessons, PERIODS, plan([]))).toMatchObject({
      moves: [],
      teacherIds: [],
      projectedCount: 0,
      unmatchedCount: 0,
    })
    expect(projectCyclePlan(swapLessons, PERIODS, null).moves).toEqual([])
  })

  it('切换方案时各自从同一份草稿投影，不叠加上一个结果', () => {
    const planA = plan([
      { lessonId: 11, fromPeriodId: 101, toPeriodId: 103, teacherId: 9, courseId: 1, classId: 1 },
    ])
    const planB = plan([
      { lessonId: 12, fromPeriodId: 102, toPeriodId: 201, teacherId: 9, courseId: 2, classId: 2 },
    ])

    const afterA = applyProjectionToLessons(swapLessons, projectCyclePlan(swapLessons, PERIODS, planA))
    const afterB = applyProjectionToLessons(swapLessons, projectCyclePlan(swapLessons, PERIODS, planB))

    expect(afterA.find((item) => item.id === 11)?.periodId).toBe(103)
    expect(afterA.find((item) => item.id === 12)?.periodId).toBe(102)
    expect(afterB.find((item) => item.id === 11)?.periodId).toBe(101)
    expect(afterB.find((item) => item.id === 12)?.periodId).toBe(201)
  })
})

describe('periodCaption', () => {
  it('给出节次文案，缺时段时退回可核对的标识', () => {
    expect(periodCaption(PERIODS, 101)).toBe('周一第1节')
    expect(periodCaption(PERIODS, 202)).toBe('周二第2节')
    expect(periodCaption(PERIODS, 555)).toBe('时段 #555')
    expect(periodCaption(PERIODS, null)).toBe('未排课')
  })
})
