/**
 * 管理调代工作台（票 03）的前端类型，与后端 SubstitutionWorkbenchDtos 一一对应。
 */

export interface WorkbenchCandidate {
  teacherId: number
  name: string
  subject?: string | null
  sameSubject: boolean
  busyAtPeriod: boolean
  busyReason?: string | null
  crossSubject: boolean
}

export interface WorkbenchCandidateList {
  entryId: number
  lessonDate: string
  classId: number
  className?: string | null
  courseId: number
  courseName?: string | null
  courseSubject?: string | null
  currentTeacherId: number
  currentTeacherName?: string | null
  periodId: number
  periodNo: number
  periodLabel: string
  legacyLocked: boolean
  activeRequestId?: number | null
  candidates: WorkbenchCandidate[]
}

export interface PreviewRequest {
  entryId: number
  lessonDate: string
  substituteTeacherId: number
  reason?: string
}

export interface GridCell {
  entryId: number
  periodNo: number
  periodLabel: string
  courseName?: string | null
  className?: string | null
  teacherId: number
  teacherName?: string | null
  roomName?: string | null
  changed: boolean
}

export interface ChangeItem {
  entryId: number
  classId: number
  className?: string | null
  courseId: number
  courseName?: string | null
  lessonDate: string
  periodLabelBefore: string
  periodLabelAfter: string
  teacherIdBefore: number
  teacherNameBefore?: string | null
  teacherIdAfter: number
  teacherNameAfter?: string | null
  roomNameBefore?: string | null
  roomNameAfter?: string | null
  changeKind: string
}

export interface PreviewResponse {
  voucherId: number
  deadlineAt: string
  expiresAt: string
  lessonDate: string
  classId: number
  className?: string | null
  sourceEntryId: number
  previousTeacherId: number
  previousTeacherName?: string | null
  substituteTeacherId: number
  substituteTeacherName?: string | null
  sameSubject: boolean
  left: GridCell[]
  right: GridCell[]
  changes: ChangeItem[]
  issues: string[]
  canSubmitApproval: boolean
  canConfirm: boolean
}

export interface WorkbenchActionResult {
  requestId: number
  status: string
  replayed: boolean
}
