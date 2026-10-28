export interface Teacher {
  id: number
  name: string
  subject?: string
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
  roomName?: string | null
  room?: string | null
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
  originalTeacherName: string
  substituteTeacherName?: string
  substituteTeacherId?: number
  effectiveDate: string
  periodLabel: string
  targetPeriodId?: number | null
  targetPeriodLabel?: string | null
  type: 'SWAP' | 'COVER'
  reason?: string
  status: string
  approvalMode: string
  approvalComment?: string
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
  roomName?: string
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
  roomName?: string
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
  SUBMITTED: '已提交',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  EFFECTIVE: '已生效',
  CANCELLED: '已取消',
  PROPOSED: '待选择',
  EXPIRED: '已过期',
}

export function statusLabel(status: string) {
  return STATUS_LABELS[status] ?? status
}

export function statusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'EFFECTIVE' || status === 'APPROVED') return 'success'
  if (status === 'REJECTED' || status === 'EXPIRED') return 'danger'
  if (status === 'PENDING_APPROVAL' || status === 'PROPOSED') return 'warning'
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

export function today() {
  return new Date().toISOString().slice(0, 10)
}
