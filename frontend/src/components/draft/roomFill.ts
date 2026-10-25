import type { DraftPublishBlocker } from './types'

/**
 * The preview a person confirms before the server creates fixed ordinary rooms for
 * every class that has lessons without a room (ticket 07). One shape serves the
 * preview and the apply result, so the rows do not jump when the action runs.
 */
export interface RoomFillClassPlan {
  classId: number
  className: string
  /** The class size the capacity is derived from. */
  studentCount: number
  /** Seats the room ends up with, never below the class size. */
  requiredCapacity: number
  lessonCount: number
  roomName: string
  /** Null while the room still has to be created. */
  roomId?: number | null
  roomCapacity: number
  action: 'CREATE' | 'REUSE'
}

/** One class the action will not touch, with the reason. */
export interface RoomFillManualItem {
  classId?: number | null
  className?: string | null
  code: string
  message: string
}

export interface RoomFillPlan {
  applied: boolean
  /** False when confirming cannot work (manual items, published semester, new conflicts). */
  applicable: boolean
  createdRooms: number
  reusedRooms: number
  assignedLessons: number
  classes: RoomFillClassPlan[]
  manualItems: RoomFillManualItem[]
  /** What still stands between the draft and publishing after this action. */
  blockers: DraftPublishBlocker[]
  /** Readable reason for a refusal (null on success); the plan's own fields stay intact. */
  message?: string | null
}
