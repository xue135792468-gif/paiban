/** View models shared by the draft editor, its grid and the adjust composable. */

export interface Draft {
  id: number
  semesterId: number
  name: string
  status: string
  scoreHard: number
  scoreSoft: number
  createdBy?: string
  createdAt: string
  lessonCount: number
}

export interface DraftRoom {
  id: number
  name: string
  capacity: number
  type: string
}

export interface DraftConflict {
  type: string
  message: string
  lessonId: number
}

export interface DraftLesson {
  id: number
  assignmentId: number
  courseId: number
  courseName?: string
  teacherId: number
  teacherName?: string
  classId: number
  className?: string
  studentCount: number
  periodId?: number | null
  dayOfWeek?: number | null
  periodNo?: number | null
  section?: string
  periodLabel?: string
  roomId?: number | null
  roomName?: string
}

export interface DraftPeriod {
  id: number
  dayOfWeek: number
  periodNo: number
  label: string
  section: string
  startTime: string
  endTime: string
}

export interface DraftCourse {
  id: number
  color?: string
}

/** One period the dragged lesson may move to, as returned by move-targets. */
export interface MoveTargetState {
  available: boolean
  conflicts: DraftConflict[]
}

/** A lesson that can be exchanged with the selected one, as returned by swap-candidates. */
export interface SwapCandidate {
  lessonId: number
  courseId: number
  courseName?: string
  subject?: string
  teacherId: number
  teacherName?: string
  classId: number
  className?: string
  gradeId?: number
  gradeName?: string
  periodId: number
  dayOfWeek?: number
  periodNo?: number
  periodLabel?: string
  roomId?: number | null
  roomName?: string
  available: boolean
  conflicts: DraftConflict[]
  warnings: string[]
}

export interface SwapCandidatesResponse {
  lessonId: number
  intraClass: SwapCandidate[]
  crossClass: SwapCandidate[]
}

/** Result of POST /swap; the server derives the type from the two lessons. */
export interface SwapResult {
  saved: boolean
  valid: boolean
  hardConflicts: number
  type: 'INTRA_CLASS' | 'CROSS_CLASS'
  conflicts: DraftConflict[]
  warnings: string[]
}
