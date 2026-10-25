/**
 * The periods a saved import preview uses but the current semester's timetable lacks
 * (ticket 08). The backend derives the list from the saved preview, so this module only
 * shapes what the page shows and what it sends back.
 */
export interface MissingPeriodItem {
  dayOfWeek: number
  periodNo: number
  /** PROPOSED: the backend computed a time; MANUAL: a human has to set it. */
  status: 'PROPOSED' | 'MANUAL'
  label: string
  section?: string | null
  startTime?: string | null
  endTime?: string | null
  /** Why a MANUAL item needs a human (or why nothing can be appended). */
  reason?: string | null
}

export interface MissingPeriodsPlan {
  semesterId: number
  semesterName: string
  /** A published semester's timetable is read-only, so every item is manual. */
  published: boolean
  items: MissingPeriodItem[]
}

export interface MissingPeriodApplyItem {
  dayOfWeek: number
  periodNo: number
  startTime: string
  endTime: string
}

export interface ApplyMissingPeriodsResult {
  created: number
  plan: MissingPeriodsPlan
}

const DAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

const SECTION_LABELS: Record<string, string> = {
  MORNING: '上午',
  AFTERNOON: '下午',
  EVENING: '晚自习',
}

export function dayLabel(dayOfWeek: number): string {
  return DAY_LABELS[dayOfWeek - 1] ?? `星期${dayOfWeek}`
}

export function sectionLabel(section?: string | null): string {
  if (!section) return '—'
  return SECTION_LABELS[section] ?? section
}

/** `08:00:00` → `08:00`; a missing or unreadable value stays readable. */
export function clock(value?: string | null): string {
  const text = (value ?? '').trim()
  if (!text) return '—'
  const match = /^(\d{1,2}):(\d{2})/.exec(text)
  return match ? `${match[1].padStart(2, '0')}:${match[2]}` : text
}

export function timeRange(item: MissingPeriodItem): string {
  if (!item.startTime || !item.endTime) return '—'
  return `${clock(item.startTime)}–${clock(item.endTime)}`
}

/** Stable key for a slot; also what the selection list holds. */
export function keyOf(item: Pick<MissingPeriodItem, 'dayOfWeek' | 'periodNo'>): string {
  return `${item.dayOfWeek}-${item.periodNo}`
}

/** Only these can be confirmed; manual items need the period form. */
export function proposedItems(plan: MissingPeriodsPlan | null): MissingPeriodItem[] {
  return (plan?.items ?? []).filter((item) => item.status === 'PROPOSED')
}

/** The request body for one ticked item; null when the time is missing. */
export function applyItem(item: MissingPeriodItem): MissingPeriodApplyItem | null {
  if (!item.startTime || !item.endTime) return null
  return {
    dayOfWeek: item.dayOfWeek,
    periodNo: item.periodNo,
    startTime: item.startTime,
    endTime: item.endTime,
  }
}

/** The one-line description the confirm dialog lists. */
export function describeItem(item: MissingPeriodItem): string {
  const section = item.section ? `${sectionLabel(item.section)} ` : ''
  return `${dayLabel(item.dayOfWeek)} ${item.label} ${section}${timeRange(item)}`
}
