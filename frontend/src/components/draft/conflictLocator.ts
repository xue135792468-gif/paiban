import type { DraftConflict, DraftLesson } from './types'

export interface LocatableConflict {
  conflict: DraftConflict
  lesson: DraftLesson
}

/**
 * Extracts conflicts that point to a known lesson in the draft, deduplicated by
 * lessonId so repeated clicks on「逐条定位」advance to a distinct lesson each time.
 */
export function locatableConflicts(
  conflicts: DraftConflict[],
  lessons: DraftLesson[],
): LocatableConflict[] {
  const byId = new Map(lessons.map((lesson) => [lesson.id, lesson]))
  const seenLessonIds = new Set<number>()
  const result: LocatableConflict[] = []

  for (const conflict of conflicts) {
    if (!conflict.lessonId || seenLessonIds.has(conflict.lessonId)) continue
    const lesson = byId.get(conflict.lessonId)
    if (!lesson) continue
    seenLessonIds.add(lesson.id)
    result.push({ conflict, lesson })
  }
  return result
}

/** Advances the cursor cyclically through locatable conflicts. */
export function nextConflictIndex(currentIndex: number, total: number): number {
  if (total <= 0) return -1
  if (currentIndex < 0 || currentIndex >= total - 1) return 0
  return currentIndex + 1
}

/** Picks the lesson a conflict dialog should target when「换到别的位置」is clicked. */
export function resolveConflictLesson(
  conflicts: DraftConflict[],
  lessons: DraftLesson[],
  fallbackLesson: DraftLesson | null,
): DraftLesson | null {
  const byId = new Map(lessons.map((lesson) => [lesson.id, lesson]))
  if (fallbackLesson) {
    const fresh = byId.get(fallbackLesson.id) ?? fallbackLesson
    const mentioned = conflicts.some((conflict) => conflict.lessonId === fresh.id)
    if (mentioned || conflicts.length === 0) return fresh
  }
  for (const conflict of conflicts) {
    if (conflict.lessonId) {
      const matched = byId.get(conflict.lessonId)
      if (matched) return matched
    }
  }
  return fallbackLesson
}
