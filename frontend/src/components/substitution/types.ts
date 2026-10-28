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

// ---------------------------------------------------------------------
// 历史调代日期转换（票 02）：预检报告、转换状态与确认批次
// ---------------------------------------------------------------------

export interface ConversionLesson {
  substitutionId?: number | null
  teacherId: number
  teacherName?: string | null
  roomId?: number | null
  roomName?: string | null
  dayOfWeek: number
  periodNo: number
  periodLabel: string
  className?: string | null
  courseName?: string | null
}

/** 某一天该条目转换前后的差异；before/after 为 null 表示该日无课。 */
export interface ConversionAffectedDate {
  date: string
  restoredToBaseline: boolean
  before: ConversionLesson | null
  after: ConversionLesson | null
}

export interface ConversionItem {
  substitutionId: number
  requestId?: number | null
  planId?: number | null
  entryId: number
  type: 'SWAP' | 'COVER'
  status: 'CONVERTIBLE' | 'BLOCKED' | 'ALREADY_CONVERTED'
  basis?: string | null
  effectiveDate?: string | null
  lessonDate?: string | null
  targetLessonDate?: string | null
  blockerCode?: string | null
  blockerReason?: string | null
  className?: string | null
  courseName?: string | null
  originalTeacherName?: string | null
  substituteTeacherName?: string | null
  fromPeriodLabel?: string | null
  affectedDates: ConversionAffectedDate[]
}

export interface ConversionSummary {
  totalRows: number
  convertible: number
  blocked: number
  alreadyConverted: number
  changes: number
}

export interface ConversionReport {
  semesterId: number
  generatedAt: string
  summary: ConversionSummary
  items: ConversionItem[]
}

export interface ConversionItemRecord {
  substitutionId: number
  entryId: number
  type: string
  lessonDateBefore?: string | null
  targetLessonDateBefore?: string | null
  lessonDateAfter?: string | null
  targetLessonDateAfter?: string | null
  basis?: string | null
}

export interface ConversionBatch {
  id: number
  status: 'CONVERTED' | 'FAILED'
  legacyTotal: number
  convertedCount: number
  blockedCount: number
  confirmedBy: string
  confirmedAt: string
  confirmationComment?: string | null
  failureReason?: string | null
  createdAt: string
  items: ConversionItemRecord[]
}

export interface ConversionStatusInfo {
  semesterId: number
  legacyRowCount: number
  convertibleCount: number
  blockedCount: number
  converted: boolean
  canConfirm: boolean
  conversions: ConversionBatch[]
}

export const CONVERSION_ITEM_STATUS_LABELS: Record<string, string> = {
  CONVERTIBLE: '可转换',
  BLOCKED: '阻塞',
  ALREADY_CONVERTED: '已转换',
}

export const CONVERSION_BLOCKER_LABELS: Record<string, string> = {
  MISSING_REQUEST: '缺失来源申请',
  MISSING_MOVE: '无对应方案移动',
  AMBIGUOUS_MOVE: '方案移动歧义',
  VERSION_CONFLICT: '版本冲突',
  UNKNOWN_TYPE: '无法唯一解释',
}

export function conversionItemStatusLabel(status: string) {
  return CONVERSION_ITEM_STATUS_LABELS[status] ?? status
}

export function conversionBlockerLabel(code?: string | null) {
  if (!code) return ''
  return CONVERSION_BLOCKER_LABELS[code] ?? code
}
