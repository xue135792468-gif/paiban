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

// ------------------------- 票 05：管理员同班调课（SWAP） -------------------------

/** 指定目标日期里，来源班当前已有课的槽位（可作置换目标的候选格）。 */
export interface SwapTargetSlot {
  periodId: number
  periodNo: number
  courseId: number
  courseName?: string | null
  teacherId: number
  teacherName?: string | null
}

export interface SwapTargetsResponse {
  entryId: number
  lessonDate: string
  targetDate: string
  classId: number
  className?: string | null
  slots: SwapTargetSlot[]
}

export interface SwapPlanGenerateRequest {
  entryId: number
  lessonDate: string
  targetDate: string
  targetPeriodId: number
  rankingPreset?: string
  reason?: string
}

/** 方案里的一步移动：前后都是实际日期。 */
export interface SwapPlanMove {
  entryId: number
  classId: number
  className?: string | null
  courseId: number
  courseName?: string | null
  teacherId: number
  teacherName?: string | null
  roomId?: number | null
  roomName?: string | null
  fromPeriodId: number
  fromDayOfWeek: number
  fromPeriodNo: number
  fromPeriodLabel: string
  fromDate: string
  toPeriodId: number
  toDayOfWeek: number
  toPeriodNo: number
  toPeriodLabel: string
  toDate: string
  order: number
}

export interface SwapCandidate {
  planId: number
  impactDays: number
  impactTeachers: number
  softPenalty: number
  cycleLength: number
  sameWeek: boolean
  moves: SwapPlanMove[]
}

export interface PlanDiagnosis {
  code: string
  message: string
  entryId?: number | null
  periodId?: number | null
}

export interface SwapPlansResponse {
  entryId: number
  lessonDate: string
  classId: number
  className?: string | null
  courseId: number
  courseName?: string | null
  targetDate: string
  targetPeriodId: number
  targetPeriodNo?: number | null
  rankingPreset: string
  rankingPresetLabel: string
  plans: SwapCandidate[]
  diagnoses: PlanDiagnosis[]
}

export interface SwapPreviewCreateRequest {
  entryId: number
  lessonDate: string
  planId: number
  reason?: string
}

export interface SwapDayCompare {
  date: string
  left: GridCell[]
  right: GridCell[]
}

export interface SwapChangeItem {
  entryId: number
  classId: number
  className?: string | null
  courseId: number
  courseName?: string | null
  fromDate: string
  fromLabel: string
  toDate: string
  toLabel: string
  teacherId: number
  teacherName?: string | null
  roomName?: string | null
  changeKind: string
}

export interface SwapPreviewResponse {
  voucherId: number
  planId: number
  deadlineAt: string
  expiresAt: string
  entryId: number
  lessonDate: string
  targetDate: string
  targetPeriodId: number
  targetPeriodNo?: number | null
  classId: number
  className?: string | null
  affectedTeacherIds: number[]
  affectedTeacherNames: string[]
  days: SwapDayCompare[]
  changes: SwapChangeItem[]
  issues: string[]
  canSubmitApproval: boolean
  canConfirm: boolean
}

export interface SwapCompareResponse {
  date: string
  view: string
  viewId: number
  viewName?: string | null
  left: GridCell[]
  right: GridCell[]
}
