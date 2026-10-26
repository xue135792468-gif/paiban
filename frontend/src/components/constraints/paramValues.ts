import type { ParamDef } from './types'

/**
 * Value plumbing shared by the catalogue-driven controls: every parameter arrives
 * from the server as plain JSON, so these helpers narrow it for the Element Plus
 * inputs without teaching any component what a specific parameter means.
 */
export const DAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

export const ROOM_TYPE_OPTIONS = [
  { value: 'NORMAL', label: '普通教室' },
  { value: 'LAB', label: '实验室' },
  { value: 'PLAYGROUND', label: '操场' },
  { value: 'MULTIMEDIA', label: '多媒体' },
  { value: 'OTHER', label: '其他' },
]

export type SelectValue = string | number | boolean | Record<string, any> | null | undefined

export function hasValue(value: unknown): boolean {
  return value !== null && value !== undefined && value !== ''
}

export function selectValue(values: Record<string, unknown>, name: string): SelectValue {
  const value = values[name]
  return value === null || value === undefined ? value : (value as SelectValue)
}

export function numberValue(
  values: Record<string, unknown>,
  name: string,
): number | null | undefined {
  const value = values[name]
  return typeof value === 'number' ? value : null
}

export function textValue(values: Record<string, unknown>, name: string): string {
  const value = values[name]
  return value === null || value === undefined ? '' : String(value)
}

/** Declared parameters only, blank values dropped — the same shape the server normalises. */
export function filledValues(
  values: Record<string, unknown>,
  params: ParamDef[],
): Record<string, unknown> {
  const filled: Record<string, unknown> = {}
  params.forEach((param) => {
    const value = values[param.name]
    if (hasValue(value)) {
      filled[param.name] = value
    }
  })
  return filled
}

/** The catalogue label without its parenthetical: `课程（留空表示全部）` → `课程`. */
export function plainLabel(param: ParamDef): string {
  return param.label.replace(/（[^）]*）/g, '').trim()
}

export function dayLabel(dayOfWeek: unknown): string {
  const option = DAY_OPTIONS.find((item) => item.value === Number(dayOfWeek))
  return option?.label ?? `星期${dayOfWeek}`
}

export function roomTypeLabel(code: unknown): string {
  const option = ROOM_TYPE_OPTIONS.find((item) => item.value === String(code))
  return option?.label ?? String(code)
}

/**
 * `data-testid` suffix for one parameter inside an entry card. The E2E specs pick
 * fields by this name, so it is derived here once instead of in each component.
 */
const FIELD_IDS: Record<string, string> = {
  teacherId: 'teacher',
  courseId: 'course',
  classId: 'class',
  subject: 'subject',
  dayOfWeek: 'day',
  periodNo: 'period',
  roomType: 'room-type',
  minConsecutive: 'min-consecutive',
  beforePeriodNo: 'before-period',
  maxPerDay: 'max-per-day',
  maxConsecutive: 'max-consecutive',
}

export function fieldTestId(name: string): string {
  return FIELD_IDS[name] ?? name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)
}

/** Which catalogue list a `ID` parameter points at. Names are the server's contract. */
export function idOptions(
  param: ParamDef,
  teachers: Array<{ id: number; name: string }>,
  courses: Array<{ id: number; name: string }>,
): Array<{ id: number; name: string }> {
  const lower = param.name.toLowerCase()
  if (lower.includes('teacher')) {
    return teachers
  }
  if (lower.includes('course')) {
    return courses
  }
  return []
}
