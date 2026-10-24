import type { DraftCourse, DraftLesson, DraftPeriod } from '@/components/draft/types'

/** One AI-preview row; only `values` matters for the timetable grid. */
export interface PreviewRow {
  values: Record<string, string>
  confidence?: Record<string, number>
  lowConfidence?: string[]
}

/** One unmatched value, as returned by the import preview. */
export interface PreviewIssue {
  row: number
  field: string
  value: string
  candidates: string[]
  required: boolean
  /** Set on page-level blockers (row 0), e.g. a missing current semester. */
  message?: string
  /** Grade inferred from a class name; null/absent when the name carries no grade. */
  inferredGrade?: string | null
  /** The current semester's grade names, so the UI can say 新建 vs 已有. */
  gradeCandidates?: string[]
}

/** Counts the backend derives for a timetable preview. */
export interface TimetableSummary {
  lessons: number
  /** Preview rows that could not be matched to foundation data. */
  unresolved: number
  groups: number
  newAssignments: number
  updatedAssignments: number
}

/** Shape of `GET/POST/PUT /api/ai/import/sessions/{id}` responses. */
export interface ImportPreview {
  sessionId: number
  targetType: string
  /** What the AI thinks the data looks like; shown as a hint, never applied silently. */
  suggestedTarget?: string | null
  columns: { field: string; label: string; required: boolean }[]
  rows: PreviewRow[]
  mappings: { source: string; target: string; note: string }[]
  explanation: string
  threshold: number
  status: string
  issues?: PreviewIssue[]
  summary?: TimetableSummary | null
  /** Present only for sessions parsed in background batches. */
  progress?: ImportProgress | null
  /** Batches the AI could not parse; empty unless the parse is partial. */
  failedBatches?: FailedBatch[]
  /** Present only for a whole-workbook import (phase 11). */
  workbook?: WorkbookSummary | null
  /** Non-blocking notes: skipped free periods, dropped duplicates, unconfirmed structure. */
  warnings?: string[]
  /** The read-only tool rounds the model ran while browsing the workbook. */
  toolTrace?: ToolTrace[]
}

/** One sheet of a workbook import and what it contributed. */
export interface SheetSummary {
  sheet: string
  category: string
  columns: Record<string, string>
  recognizedRows: number
  newRows: number
  skippedReason?: string | null
  note?: string | null
}

/** What writing one category did; the commit summary lists one row per category. */
export interface CategoryResult {
  category: string
  created: number
  updated: number
  skipped: number
  errors?: string[]
}

export interface WorkbookSummary {
  structureConfirmed: boolean
  sheets: SheetSummary[]
  /** Empty until a commit ran. */
  committed?: CategoryResult[]
}

/** One round of the workbook tool loop. */
export interface ToolTrace {
  round: number
  tool: string
  args: string
  result: string
}

/** How far a background parse has got; `currentBatch` is the class in flight. */
export interface ImportProgress {
  total: number
  completed: number
  failed: number
  currentBatch?: string | null
}

/** One batch that failed, named so the user knows which class to retry. */
export interface FailedBatch {
  index: number
  label: string
  reason: string
}

/** One system entity offered by the mapping lookup. */
export interface LookupItem {
  id: number
  name: string
  extra?: string
}

export interface MappingItemPayload {
  field: string
  source: string
  targetId: number
}

export interface CreateItemPayload {
  field: string
  source: string
  name: string
  gradeId?: number
  gradeName?: string
  studentCount?: number
}

export type LookupType = 'class' | 'course' | 'teacher' | 'room'

const FIELD_LABELS: Record<string, string> = {
  className: '班级',
  dayOfWeek: '星期',
  periodNo: '节次',
  courseName: '课程',
  teacherName: '教师',
  roomName: '教室',
}

const LOOKUP_TYPES: Record<string, LookupType> = {
  className: 'class',
  courseName: 'course',
  teacherName: 'teacher',
  roomName: 'room',
}

const DAY_DIGITS = '一二三四五六七'

function text(value: string | null | undefined): string {
  return (value ?? '').trim()
}

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field
}

/** Entity fields can be mapped to an existing record; day/period are edited in place. */
export function lookupType(field: string): LookupType | null {
  return LOOKUP_TYPES[field] ?? null
}

/** Creating a class needs a grade; the other entities do not. */
export function createRequiresGrade(field: string): boolean {
  return field === 'className'
}

/** Mirrors the backend's grade-name match: NFKC, no whitespace, case-insensitive. */
export function normalizeGradeName(value: string | null | undefined): string {
  return (value ?? '').normalize('NFKC').replace(/\s+/g, '').toLowerCase()
}

export type GradeStatus = 'existing' | 'new' | 'unknown'

/**
 * Whether a class group's inferred grade already exists this semester. Unknown means
 * nothing was inferred, so the user has to pick a grade before the class can be created.
 */
export function gradeStatusOf(group: Pick<MappingGroup, 'inferredGrade' | 'gradeCandidates'>): GradeStatus {
  const inferred = text(group.inferredGrade)
  if (!inferred) return 'unknown'
  const normalized = normalizeGradeName(inferred)
  return group.gradeCandidates.some((name) => normalizeGradeName(name) === normalized)
    ? 'existing'
    : 'new'
}

export function parseDay(value: string): number | null {
  const day = text(value).replace(/星期|礼拜|周/g, '')
  if (!day) return null
  if (day === '天' || day === '日' || day === '七' || day === '7') return 7
  if (/^[1-7]$/.test(day)) return Number(day)
  const index = DAY_DIGITS.indexOf(day)
  return day.length === 1 && index >= 0 ? index + 1 : null
}

export function parsePeriodNo(value: string): number | null {
  const period = text(value).replace(/第/g, '').replace(/节/g, '')
  if (!period) return null
  if (/^\d+$/.test(period)) {
    const number = Number(period)
    return number > 0 ? number : null
  }
  return chineseNumber(period)
}

function chineseNumber(value: string): number | null {
  const digits = '一二三四五六七八九'
  let total = 0
  let current = 0
  for (const char of value) {
    const digit = char === '两' ? 2 : digits.indexOf(char) + 1
    if (digit > 0) {
      current = digit
    } else if (char === '十') {
      total += (current === 0 ? 1 : current) * 10
      current = 0
    } else {
      return null
    }
  }
  const result = total + current
  return result > 0 ? result : null
}

/** Classes present in the preview, in first-seen order. */
export function timetableClasses(rows: PreviewRow[]): string[] {
  return distinctNames(rows, 'className')
}

/** Teachers present in the preview, in first-seen order. */
export function timetableTeachers(rows: PreviewRow[]): string[] {
  return distinctNames(rows, 'teacherName')
}

function distinctNames(rows: PreviewRow[], field: string): string[] {
  const names: string[] = []
  for (const row of rows) {
    const name = text(row.values?.[field])
    if (name && !names.includes(name)) {
      names.push(name)
    }
  }
  return names
}

export interface GridFilter {
  className?: string
  teacherName?: string
}

export interface TimetableGrid {
  periods: DraftPeriod[]
  lessons: DraftLesson[]
  courses: DraftCourse[]
  /** Lessons sharing a (day, period) slot with another lesson of the same class. */
  conflictLessonIds: number[]
  /** Lessons whose preview row carries an unmatched value. */
  issueLessonIds: number[]
  /** Rows of this class that cannot be placed because day/period did not parse. */
  unplacedCount: number
}

/**
 * Turns preview rows into the read-only shape `DraftGrid` renders. Lesson ids are
 * synthetic negatives derived from the 1-based row number, so an issue on row N can
 * point at lesson -N without a separate map.
 */
export function buildTimetableGrid(
  rows: PreviewRow[],
  className: string,
  issues: PreviewIssue[] = [],
): TimetableGrid {
  return buildTimetableGridFor(rows, { className }, issues)
}

/** Same as {@link buildTimetableGrid}, filtered by a class and/or teacher. */
export function buildTimetableGridFor(
  rows: PreviewRow[],
  filter: GridFilter,
  issues: PreviewIssue[] = [],
): TimetableGrid {
  const targetClass = text(filter.className)
  const targetTeacher = text(filter.teacherName)
  const courseIds = new Map<string, number>()
  const lessons: DraftLesson[] = []
  const periods: DraftPeriod[] = []
  const slots = new Set<string>()
  let unplacedCount = 0

  rows.forEach((row, index) => {
    const values = row.values ?? {}
    if (targetClass && text(values.className) !== targetClass) return
    if (targetTeacher && text(values.teacherName) !== targetTeacher) return

    const day = parseDay(values.dayOfWeek)
    const periodNo = parsePeriodNo(values.periodNo)
    const courseName = text(values.courseName)
    const courseKey = courseName || '（未识别课程）'
    if (!courseIds.has(courseKey)) {
      courseIds.set(courseKey, courseIds.size + 1)
    }

    lessons.push({
      id: -(index + 1),
      assignmentId: 0,
      courseId: courseIds.get(courseKey)!,
      courseName: courseKey,
      teacherId: 0,
      teacherName: text(values.teacherName),
      classId: 0,
      className: text(values.className),
      studentCount: 0,
      dayOfWeek: day,
      periodNo,
      periodLabel: periodNo == null ? '' : `第${periodNo}节`,
      section: '',
      roomId: null,
      roomName: text(values.roomName),
    })

    if (day == null || periodNo == null) {
      unplacedCount++
      return
    }
    const slot = `${day}-${periodNo}`
    if (!slots.has(slot)) {
      slots.add(slot)
      periods.push({
        id: -periods.length - 1,
        dayOfWeek: day,
        periodNo,
        label: `第${periodNo}节`,
        section: '',
        startTime: '',
        endTime: '',
      })
    }
  })

  periods.sort((left, right) => left.dayOfWeek - right.dayOfWeek || left.periodNo - right.periodNo)

  const bySlot = new Map<string, number[]>()
  for (const lesson of lessons) {
    if (lesson.dayOfWeek == null || lesson.periodNo == null) continue
    const slot = `${lesson.dayOfWeek}-${lesson.periodNo}`
    const ids = bySlot.get(slot)
    if (ids) {
      ids.push(lesson.id)
    } else {
      bySlot.set(slot, [lesson.id])
    }
  }
  const conflictLessonIds = [...bySlot.values()].filter((ids) => ids.length > 1).flat()

  const lessonIds = new Set(lessons.map((lesson) => lesson.id))
  const issueLessonIds = [
    ...new Set(issues.map((issue) => -issue.row).filter((id) => lessonIds.has(id))),
  ]

  const courses: DraftCourse[] = [...courseIds.entries()].map(([, id]) => ({ id }))

  return { periods, lessons, courses, conflictLessonIds, issueLessonIds, unplacedCount }
}

export interface MappingGroup {
  key: string
  field: string
  label: string
  value: string
  /** 1-based preview rows that carry this value. */
  rows: number[]
  candidates: string[]
  required: boolean
  kind: 'entity' | 'slot'
  /** Grade inferred from the class name; null for non-class groups or no inference. */
  inferredGrade: string | null
  /** Current semester grade names, de-duplicated across the group's rows. */
  gradeCandidates: string[]
}

/** Collapses issues so the same unmatched value is resolved once, not once per row. */
export function mappingGroups(issues: PreviewIssue[]): MappingGroup[] {
  const groups = new Map<string, MappingGroup>()
  for (const issue of issues) {
    // Row 0 is a page-level blocker (e.g. no current semester), not a name to map.
    if (issue.row === 0) continue
    const key = `${issue.field}\u0000${issue.value}`
    let group = groups.get(key)
    if (!group) {
      group = {
        key,
        field: issue.field,
        label: fieldLabel(issue.field),
        value: issue.value,
        rows: [],
        candidates: [],
        required: issue.required,
        kind: lookupType(issue.field) ? 'entity' : 'slot',
        inferredGrade: text(issue.inferredGrade) || null,
        gradeCandidates: [],
      }
      groups.set(key, group)
    }
    if (!group.rows.includes(issue.row)) {
      group.rows.push(issue.row)
    }
    if (!group.inferredGrade && text(issue.inferredGrade)) {
      group.inferredGrade = text(issue.inferredGrade)
    }
    for (const candidate of issue.candidates ?? []) {
      if (!group.candidates.includes(candidate)) {
        group.candidates.push(candidate)
      }
    }
    for (const candidate of issue.gradeCandidates ?? []) {
      if (!group.gradeCandidates.includes(candidate)) {
        group.gradeCandidates.push(candidate)
      }
    }
  }
  return [...groups.values()]
    .map((group) => ({ ...group, rows: [...group.rows].sort((left, right) => left - right) }))
    .sort((left, right) => left.rows[0] - right.rows[0])
}

/** Nothing can be imported while any unmatched value remains. */
export function canCommit(issues: PreviewIssue[]): boolean {
  return issues.length === 0
}

export function remainingIssueCount(issues: PreviewIssue[]): number {
  return issues.length
}

/** Rewrites one slot field on the given 1-based rows, leaving the originals untouched. */
export function applySlotValue(
  rows: PreviewRow[],
  field: 'dayOfWeek' | 'periodNo',
  value: string,
  rowNumbers: number[],
): PreviewRow[] {
  const targets = new Set(rowNumbers)
  return rows.map((row, index) =>
    targets.has(index + 1)
      ? { ...row, values: { ...row.values, [field]: value } }
      : row,
  )
}

/** How a workbook sheet's category reads on the preview. */
export const CATEGORY_LABELS: Record<string, string> = {
  timetable: '课表',
  teacher: '教师名单',
  course: '课程清单',
  explanation: '说明',
  unknown: '未识别',
}

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category
}

/** True when the preview came from a whole-workbook upload (no target was chosen). */
export function isWorkbookPreview(preview: ImportPreview | null | undefined): boolean {
  return preview?.targetType === 'workbook'
}
