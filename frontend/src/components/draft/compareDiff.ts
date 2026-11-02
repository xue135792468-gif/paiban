import type { DraftLesson } from './types'

/** One lesson whose slot or teacher differs between the two drafts. */
export interface CompareChange {
  /** Stable lesson identity: `<assignmentId>#<lessonIndex>`. */
  key: string
  assignmentId: number
  lessonIndex: number
  courseName: string | null
  className: string | null
  fromPeriodId: number | null
  toPeriodId: number | null
  fromTeacherId: number
  toTeacherId: number
  fromTeacherName: string | null
  toTeacherName: string | null
  fromDay: number | null
  toDay: number | null
  fromLabel: string | null
  toLabel: string | null
  /** Either side carries the lock flag; a locked lesson that moved is a red flag. */
  locked: boolean
}

export interface CompareDiff {
  changes: CompareChange[]
  /** Lesson keys present on only one side; they are never silently treated as equal. */
  unpaired: string[]
  /** All differing lessons (locked included). */
  changedLessons: number
  /** Differing lessons that are not locked — what the user actually agreed to change. */
  unlockedChanges: number
  /** Differing lessons that are locked on either side. */
  lockedChanges: number
  /** Locked lessons (on the source side) whose slot and teacher stayed identical. */
  lockedUnchanged: number
  affectedTeachers: number[]
  affectedDays: number[]
}

function dayOf(lesson: DraftLesson): number | null {
  return lesson.dayOfWeek ?? null
}

/**
 * Builds the lesson identity map for one draft.
 *
 * The contract identity is `(assignmentId, lessonIndex)`. The detail API now sends
 * `lessonIndex`; the id-order rank is only a compatibility fallback for an older
 * payload, because rows arrive ordered by id and manual edits never renumber them.
 */
export function lessonKeyMap(lessons: DraftLesson[]): Map<string, DraftLesson> {
  const rankByAssignment = new Map<number, number>()
  const map = new Map<string, DraftLesson>()
  for (const lesson of lessons) {
    let index: number
    if (lesson.lessonIndex != null) {
      index = lesson.lessonIndex
    } else {
      index = rankByAssignment.get(lesson.assignmentId) ?? 0
      rankByAssignment.set(lesson.assignmentId, index + 1)
    }
    map.set(`${lesson.assignmentId}#${index}`, lesson)
  }
  return map
}

function sameSlotAndTeacher(a: DraftLesson, b: DraftLesson): boolean {
  return (a.periodId ?? null) === (b.periodId ?? null) && a.teacherId === b.teacherId
}

/**
 * Pure diff for the "new draft vs source draft" comparison. Locked lessons are
 * shown separately: the optimization must keep them in place, so any locked
 * change is surfaced, never folded into the ordinary change count.
 */
export function compareDrafts(lessonsA: DraftLesson[], lessonsB: DraftLesson[]): CompareDiff {
  const mapA = lessonKeyMap(lessonsA)
  const mapB = lessonKeyMap(lessonsB)

  const changes: CompareChange[] = []
  const unpaired: string[] = []
  const teacherIds = new Set<number>()
  const days = new Set<number>()
  let unlockedChanges = 0
  let lockedChanges = 0
  let lockedUnchanged = 0

  const keys = [...new Set([...mapA.keys(), ...mapB.keys()])]
  for (const key of keys) {
    const a = mapA.get(key)
    const b = mapB.get(key)
    if (!a || !b) {
      unpaired.push(key)
      continue
    }
    if (!sameSlotAndTeacher(a, b)) {
      const locked = a.locked || b.locked
      changes.push({
        key,
        assignmentId: a.assignmentId,
        lessonIndex: Number(key.split('#')[1]),
        courseName: a.courseName ?? b.courseName ?? null,
        className: a.className ?? b.className ?? null,
        fromPeriodId: a.periodId ?? null,
        toPeriodId: b.periodId ?? null,
        fromTeacherId: a.teacherId,
        toTeacherId: b.teacherId,
        fromTeacherName: a.teacherName ?? null,
        toTeacherName: b.teacherName ?? null,
        fromDay: dayOf(a),
        toDay: dayOf(b),
        fromLabel: a.periodLabel ?? null,
        toLabel: b.periodLabel ?? null,
        locked,
      })
      teacherIds.add(a.teacherId)
      teacherIds.add(b.teacherId)
      if (dayOf(a) != null) days.add(dayOf(a)!)
      if (dayOf(b) != null) days.add(dayOf(b)!)
      if (locked) lockedChanges += 1
      else unlockedChanges += 1
    } else if (a.locked) {
      lockedUnchanged += 1
    }
  }

  return {
    changes,
    unpaired: unpaired.sort(),
    changedLessons: changes.length,
    unlockedChanges,
    lockedChanges,
    lockedUnchanged,
    affectedTeachers: [...teacherIds].sort((x, y) => x - y),
    affectedDays: [...days].sort((x, y) => x - y),
  }
}

/** Human summary used by the dialog; wording never claims success or publishability. */
export function summaryLines(diff: CompareDiff): string[] {
  const lines = [
    `未锁课节变化 ${diff.unlockedChanges} 节；锁定课节 ${diff.lockedUnchanged} 节保持不变。`,
  ]
  if (diff.unpaired.length > 0) {
    lines.push(`有 ${diff.unpaired.length} 节课无法配对（两侧授课身份不一致），未计入变化。`)
  }
  return lines
}
