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
  /**
   * False when any step disagrees with the draft. Real apply rejects the whole plan in that
   * case, so the preview refuses to move anything either: a part-projected plan would be
   * shown as「应用后」while the server would never accept it.
   */
  available: boolean
}

const EMPTY_PROJECTION: CyclePlanProjection = {
  moves: [],
  teacherIds: [],
  projectedCount: 0,
  unmatchedCount: 0,
  available: true,
}

const DAY_NAMES = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** Shown on the steps that are fine on their own while another step breaks the whole group. */
const GROUP_BLOCKED_REASON = '整组未投影：方案另有课节与当前草稿不一致'

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
 * same baseline, so a whole group (including an exchange) projects at once. Applying a
 * plan is all-or-nothing — the server rejects the whole plan when any step disagrees —
 * so one stale, mismatched or locked step leaves the group unprojected instead of
 * showing a partial result as「应用后」. The inputs are never modified.
 */
export function projectCyclePlan(
  lessons: DraftLesson[],
  periods: DraftPeriod[],
  plan: CyclePlan | null,
): CyclePlanProjection {
  if (!plan || plan.moves.length === 0) return { ...EMPTY_PROJECTION }

  const lessonById = new Map(lessons.map((lesson) => [lesson.id, lesson]))
  const periodById = new Map(periods.map((period) => [period.id, period]))
  const teacherIds: number[] = []

  /** Why this step disagrees with the draft; empty when the step itself is sound. */
  function blocker(move: CyclePlanMove): string {
    const lesson = lessonById.get(move.lessonId)
    if (!lesson) return '课节已不在当前草稿'
    if (lesson.periodId !== move.fromPeriodId) return '课节已不在方案预期的位置'
    if (lesson.teacherId !== move.teacherId) return '任课教师与当前草稿不一致'
    if (!periodById.get(move.toPeriodId)) return '目标节次不在当前作息表'
    if (lesson.locked) return '课节已锁定，方案无法应用'
    return ''
  }

  const blockers = plan.moves.map(blocker)
  const available = blockers.every((reason) => reason === '')
  const moves: ProjectedMove[] = plan.moves.map((move, index) => {
    const lesson = lessonById.get(move.lessonId) ?? null
    const targetPeriod = periodById.get(move.toPeriodId) ?? null
    const ownBlocker = blockers[index]
    const reason = available ? '' : ownBlocker || GROUP_BLOCKED_REASON

    const teacherId = lesson?.teacherId ?? move.teacherId
    if (!teacherIds.includes(teacherId)) teacherIds.push(teacherId)

    return {
      move,
      lesson,
      teacherId,
      teacherName: teacherNameOf(move, lesson),
      teacherMismatch: lesson != null && lesson.teacherId !== move.teacherId,
      courseName: lesson?.courseName ?? '课程',
      className: lesson?.className ?? '',
      fromLabel: periodCaption(periods, move.fromPeriodId),
      toLabel: periodCaption(periods, move.toPeriodId),
      projected: available,
      reason,
      targetPeriod: available ? targetPeriod : null,
    }
  })

  const unmatchedCount = available ? 0 : moves.length
  return { moves, teacherIds, projectedCount: available ? moves.length : 0, unmatchedCount, available }
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
