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
}

/** Counts the backend derives for a timetable preview. */
export interface TimetableSummary {
  lessons: number
  groups: number
  newAssignments: number
  updatedAssignments: number
}

/** Shape of `GET/POST/PUT /api/ai/import/sessions/{id}` responses. */
export interface ImportPreview {
  sessionId: number
  targetType: string
  columns: { field: string; label: string; required: boolean }[]
  rows: PreviewRow[]
  mappings: { source: string; target: string; note: string }[]
  explanation: string
  threshold: number
  status: string
  issues?: PreviewIssue[]
  summary?: TimetableSummary | null
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
}

/** Collapses issues so the same unmatched value is resolved once, not once per row. */
export function mappingGroups(issues: PreviewIssue[]): MappingGroup[] {
  const groups = new Map<string, MappingGroup>()
  for (const issue of issues) {
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
      }
      groups.set(key, group)
    }
    if (!group.rows.includes(issue.row)) {
      group.rows.push(issue.row)
    }
    for (const candidate of issue.candidates ?? []) {
      if (!group.candidates.includes(candidate)) {
        group.candidates.push(candidate)
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
