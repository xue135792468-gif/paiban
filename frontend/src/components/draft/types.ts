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
