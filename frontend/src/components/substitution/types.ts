export interface Teacher {
  id: number
  name: string
  subject?: string
}

/** 只在实际选项里存在同名异 ID 时补档案编号，避免改变唯一姓名的既有文案。 */
export function teacherOptionLabel(
  teacher: Pick<Teacher, 'id' | 'name'>,
  teachers: ReadonlyArray<Pick<Teacher, 'id' | 'name'>>,
): string {
  const ambiguous = teachers.some(
    (candidate) => candidate.id !== teacher.id && candidate.name === teacher.name,
  )
  return ambiguous ? `${teacher.name}（档案 #${teacher.id}）` : teacher.name
}

export interface Entry {
  id: number
  classId: number
  courseId: number
  courseName: string
  className: string
  teacherId: number
  teacherName: string
  periodId: number
  periodNo: number
  dayOfWeek: number
  substituted: boolean
  /** 工作台需要的补充字段；教师自助表单不会用到。 */
  periodLabel?: string | null
  lessonDate?: string | null
  targetLessonDate?: string | null
}

export interface SwapTarget {
  periodId: number
  periodNo: number
  courseId: number
  courseName: string
  teacherId: number
  teacherName: string
}

export interface RequestRow {
  id: number
  entryId: number
  className: string
  courseName: string
  originalTeacherId?: number
  originalTeacherName: string
  /** 已生效时是实际接课教师，待审批时是期望代课教师。 */
  substituteTeacherName?: string
  substituteTeacherId?: number
  /** 提交时老师期望的代课人；批准时可被管理员改人，二者都要能展示。 */
  expectedSubstituteTeacherId?: number | null
  expectedSubstituteTeacherName?: string | null
  approverUserId?: number | null
  approvedAt?: string | null
  /** 审批意见；驳回时即驳回理由。 */
  approvalComment?: string | null
  operatorUserId?: number | null
  effectiveDate: string
  periodLabel: string
  targetPeriodId?: number | null
  targetPeriodLabel?: string | null
  type: 'SWAP' | 'COVER'
  reason?: string
  status: string
  createdAt: string
}

export interface RecordRow {
  id: number
  requestId: number
  entryId: number
  type: 'SWAP' | 'COVER'
  planId?: number | null
  effectiveDate: string
  className: string
  courseName: string
  originalTeacherName: string
  substituteTeacherName: string
  status: string
  fromPeriodLabel: string
  toPeriodLabel: string
}

export interface PlanMove {
  id?: number
  entryId: number
  className: string
  courseName: string
  teacherName: string
  fromPeriodLabel: string
  toPeriodLabel: string
  fromDate: string
  toDate: string
  order: number
}

export interface Plan {
  id: number
  requestId: number
  status: string
  rankingPreset: string
  rankingPresetLabel: string
  impactDays: number
  impactTeachers: number
  softPenalty: number
  cycleLength: number
  sameWeek: boolean
  createdAt: string
  expiresAt: string
  moves: PlanMove[]
}

export interface Diagnosis {
  code: string
  message: string
  entryId?: number | null
  periodId?: number | null
}

export interface GeneratePlansResponse {
  requestId: number
  rankingPreset: string
  rankingPresetLabel: string
  plans: Plan[]
  diagnoses: Diagnosis[]
}

export interface HistoryRow {
  id: number
  fromStatus?: string
  toStatus: string
  actor?: string
  comment?: string
  createdAt: string
}

export interface Stats {
  teacherName: string
  substitutedOut: number
  substitutedIn: number
  swapParticipation: number
  weeklyLessons: number
  netLessons: number
}

export interface AiStatus {
  configured: boolean
  tasks?: Record<string, { configured: boolean } | undefined>
}

export interface Recommendation {
  teacherId: number
  name: string
  reason?: string
  score?: number
}

const STATUS_LABELS: Record<string, string> = {
  SUBMITTED: '待审批',
  EFFECTIVE: '已生效',
  CANCELLED: '系统撤销',
  PROPOSED: '待选择',
  EXPIRED: '已作废',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}

export function statusLabel(status: string) {
  return STATUS_LABELS[status] ?? status
}

export function statusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'EFFECTIVE') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PROPOSED' || status === 'SUBMITTED') return 'warning'
  return 'info'
}

export function typeLabel(type?: string) {
  if (type === 'SWAP') return '调课'
  if (type === 'COVER') return '代课'
  return '调代'
}

export function formatTime(value: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

export function weekdayOf(date: string) {
  const day = new Date(`${date}T00:00:00`).getDay()
  return day === 0 ? 7 : day
}

export function today(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function isPastDate(dateIso: string): boolean {
  if (!dateIso) return false
  return dateIso < today()
}

// ---------------------------------------------------------------------
