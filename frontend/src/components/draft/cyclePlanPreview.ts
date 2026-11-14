/** Pure projections behind the cycle-plan teacher preview; no state and no writes. */
import type { CyclePlan, CyclePlanMove, DraftLesson, DraftPeriod } from './types'

export type CyclePlanPreviewMode = 'after' | 'current'

/** One move of a plan, resolved against the current draft. */
export interface ProjectedMove {
  move: CyclePlanMove
  /** The draft lesson for this move, or null when the lesson id is gone. */
  lesson: DraftLesson | null
  /**
   * Teacher this move belongs to: the draft lesson's own teacher id when the lesson is
   * there, falling back to the id the plan declares. Grouping by it keeps the teacher
   * chips and the rendered timetable in agreement.
   */
  teacherId: number
  teacherName: string
  /** True when the plan declares a different teacher id than the draft lesson. */
  teacherMismatch: boolean
  courseName: string
  className: string
  fromLabel: string
  toLabel: string
  /** False when the lesson is missing, no longer at `fromPeriodId`, or has no target period. */
  projected: boolean
  /** Why an unprojected move is skipped; empty for a projected one. */
  reason: string
  /** Target period of a projected move, used when copying the lesson. */
  targetPeriod: DraftPeriod | null
}

export interface CyclePlanProjection {
  moves: ProjectedMove[]
  /** Teacher ids the plan touches, in first-seen order; the preview switches by id. */
  teacherIds: number[]
  projectedCount: number
  unmatchedCount: number
}

const EMPTY_PROJECTION: CyclePlanProjection = {
  moves: [],
  teacherIds: [],
  projectedCount: 0,
  unmatchedCount: 0,
}

const DAY_NAMES = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

export function dayName(dayOfWeek: number): string {
  return DAY_NAMES[dayOfWeek] ?? `周${dayOfWeek}`
}

/** Position wording for a period id, shared by the plan cards and the preview. */
export function periodCaption(periods: DraftPeriod[], periodId: number | null | undefined): string {
  if (periodId == null) return '未排课'
  const period = periods.find((item) => item.id === periodId)
  if (!period) return `时段 #${periodId}`
  return `${dayName(period.dayOfWeek)}第${period.periodNo}节`
}

function teacherNameOf(move: CyclePlanMove, lesson: DraftLesson | null): string {
  return lesson?.teacherName ?? `教师 #${move.teacherId}`
}

/**
 * Resolve a plan against the draft it was searched from. Every move is judged on the
 * same baseline, so a whole group (including an exchange) projects at once and a stale
 * move is reported instead of being guessed at. The inputs are never modified.
 */
export function projectCyclePlan(
  lessons: DraftLesson[],
  periods: DraftPeriod[],
  plan: CyclePlan | null,
): CyclePlanProjection {
  if (!plan || plan.moves.length === 0) return { ...EMPTY_PROJECTION }

  const lessonById = new Map(lessons.map((lesson) => [lesson.id, lesson]))
  const periodById = new Map(periods.map((period) => [period.id, period]))
  const moves: ProjectedMove[] = []
  const teacherIds: number[] = []
  let projectedCount = 0
  let unmatchedCount = 0

  plan.moves.forEach((move) => {
    const lesson = lessonById.get(move.lessonId) ?? null
    const targetPeriod = periodById.get(move.toPeriodId) ?? null
    const teacherMismatch = lesson != null && lesson.teacherId !== move.teacherId

    let projected = true
    let reason = ''
    if (!lesson) {
      projected = false
      reason = '课节已不在当前草稿'
    } else if (lesson.periodId !== move.fromPeriodId) {
      projected = false
      reason = '课节已不在方案预期的位置'
    } else if (teacherMismatch) {
      projected = false
      reason = '任课教师与当前草稿不一致'
    } else if (!targetPeriod) {
      projected = false
      reason = '目标节次不在当前作息表'
    }

    const teacherId = lesson?.teacherId ?? move.teacherId
    if (!teacherIds.includes(teacherId)) teacherIds.push(teacherId)
    if (projected) projectedCount += 1
    else unmatchedCount += 1

    moves.push({
      move,
      lesson,
      teacherId,
      teacherName: teacherNameOf(move, lesson),
      teacherMismatch,
      courseName: lesson?.courseName ?? '课程',
      className: lesson?.className ?? '',
      fromLabel: periodCaption(periods, move.fromPeriodId),
      toLabel: periodCaption(periods, move.toPeriodId),
      projected,
      reason,
      targetPeriod: projected ? targetPeriod : null,
    })
  })

  return { moves, teacherIds, projectedCount, unmatchedCount }
}

/** Lessons the preview renders: the draft with every projected move applied at once. */
export function applyProjectionToLessons(
  lessons: DraftLesson[],
  projection: CyclePlanProjection,
): DraftLesson[] {
  const targets = new Map<number, DraftPeriod>()
  projection.moves.forEach((row) => {
    if (row.projected && row.targetPeriod) targets.set(row.move.lessonId, row.targetPeriod)
  })
  return lessons.map((lesson) => {
    const target = targets.get(lesson.id)
    if (!target) return lesson
    return {
      ...lesson,
      periodId: target.id,
      dayOfWeek: target.dayOfWeek,
      periodNo: target.periodNo,
      periodLabel: target.label,
    }
  })
}

/** One teacher's week across every administrative class, matched by teacher id only. */
export function lessonsForTeacher(lessons: DraftLesson[], teacherId: number): DraftLesson[] {
  return lessons.filter((lesson) => lesson.teacherId === teacherId)
}

/** Lessons the plan touches for one teacher; these are the ones the preview calls out. */
export function affectedLessonIds(projection: CyclePlanProjection, teacherId: number): number[] {
  return projection.moves
    .filter((row) => row.teacherId === teacherId && row.lesson)
    .map((row) => row.move.lessonId)
}

/** Name of one teacher in the projection, used for the switch chips. */
export function teacherNameOfId(projection: CyclePlanProjection, teacherId: number): string {
  const row = projection.moves.find((item) => item.teacherId === teacherId)
  return row?.teacherName ?? `教师 #${teacherId}`
}
