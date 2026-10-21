import type { DraftLesson, DraftPeriod, SwapCandidate } from './types'

export type SwapType = 'INTRA_CLASS' | 'CROSS_CLASS'

export interface SwapPreview {
  type: SwapType
  before: DraftLesson[]
  after: DraftLesson[]
}

/** Same class means the two lessons trade periods; otherwise they trade teachers. */
export function swapTypeOf(
  source: Pick<DraftLesson, 'classId'>,
  candidate: Pick<SwapCandidate, 'classId'>,
): SwapType {
  return source.classId === candidate.classId ? 'INTRA_CLASS' : 'CROSS_CLASS'
}

const DAY_NAMES = ['', '一', '二', '三', '四', '五', '六', '日']

function periodLabel(period: DraftPeriod) {
  return `周${DAY_NAMES[period.dayOfWeek] ?? period.dayOfWeek}第${period.periodNo}节`
}

/**
 * A moved lesson keeps its identity but must carry the new cell coordinates,
 * otherwise the grid would render it in its old slot.
 */
function relocate(lesson: DraftLesson, periodId: number, periods: DraftPeriod[]): DraftLesson {
  const period = periods.find((item) => item.id === periodId)
  if (!period) return { ...lesson, periodId }
  return {
    ...lesson,
    periodId,
    dayOfWeek: period.dayOfWeek,
    periodNo: period.periodNo,
    section: period.section,
    periodLabel: periodLabel(period),
  }
}

/**
 * Builds the two snapshots the preview toggles between. Lessons that are not part
 * of the exchange are shared by both snapshots; the input array is never mutated.
 */
export function buildSwapPreview(
  lessons: DraftLesson[],
  source: DraftLesson,
  candidate: SwapCandidate,
  periods: DraftPeriod[],
  swapRooms: boolean,
): SwapPreview {
  const type = swapTypeOf(source, candidate)
  const sourcePeriod = source.periodId ?? null
  const candidatePeriod = candidate.periodId ?? null
  if (sourcePeriod == null || candidatePeriod == null) {
    return { type, before: lessons, after: lessons }
  }

  const after = lessons.map((lesson) => {
    if (lesson.id === source.id) {
      if (type === 'CROSS_CLASS') {
        return { ...lesson, teacherId: candidate.teacherId, teacherName: candidate.teacherName }
      }
      const moved = relocate(lesson, candidatePeriod, periods)
      return swapRooms
        ? { ...moved, roomId: candidate.roomId ?? null, roomName: candidate.roomName }
        : moved
    }
    if (lesson.id === candidate.lessonId) {
      if (type === 'CROSS_CLASS') {
        return { ...lesson, teacherId: source.teacherId, teacherName: source.teacherName }
      }
      const moved = relocate(lesson, sourcePeriod, periods)
      return swapRooms ? { ...moved, roomId: source.roomId ?? null, roomName: source.roomName } : moved
    }
    return lesson
  })

  return { type, before: lessons, after }
}

/** One side of the side-by-side preview: every lesson the teacher owns in a snapshot. */
export function teacherPaneLessons(lessons: DraftLesson[], teacherId: number) {
  return lessons.filter((lesson) => lesson.teacherId === teacherId)
}
