import type { LocationQuery, LocationQueryValue } from 'vue-router'

export type TimetableMode = 'class' | 'teacher' | 'school'
export type TimetableViewKind = 'latest' | 'baseline'

export interface TimetableFilterState {
  mode: TimetableMode
  targetId: number | null
  date: string
  view: TimetableViewKind
}

export interface TimetableFilterContext {
  canViewAll: boolean
  canViewClassScope: boolean
  canManageSchedule: boolean
  ownTeacherId: number | null
  classes: Array<{ id: number }>
  headClasses: Array<{ id: number }>
  teachers: Array<{ id: number }>
  defaultDate: string
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

function firstValue(value: LocationQueryValue | LocationQueryValue[] | undefined): string | null {
  const raw = Array.isArray(value) ? value[0] : value
  return typeof raw === 'string' && raw.length > 0 ? raw : null
}

export function parseTimetableQuery(query: LocationQuery): {
  mode?: TimetableMode
  id?: number
  date?: string
  view?: TimetableViewKind
} {
  const modeRaw = firstValue(query.mode)
  const mode =
    modeRaw === 'class' || modeRaw === 'teacher' || modeRaw === 'school' ? modeRaw : undefined

  const idRaw = firstValue(query.id)
  const idNum = idRaw != null && /^\d+$/.test(idRaw) ? Number(idRaw) : NaN
  const id = Number.isInteger(idNum) && idNum > 0 ? idNum : undefined

  const dateRaw = firstValue(query.date)
  const date = dateRaw && DATE_RE.test(dateRaw) ? dateRaw : undefined

  const viewRaw = firstValue(query.view)
  const view = viewRaw === 'latest' || viewRaw === 'baseline' ? viewRaw : undefined

  return { mode, id, date, view }
}

/**
 * Resolves initial timetable filters from URL query while enforcing role scope
 * and valid option ids so stale bookmarks or unauthorized queries never trigger
 * invalid timetable requests.
 */
export function resolveTimetableFilters(
  query: LocationQuery,
  ctx: TimetableFilterContext,
): TimetableFilterState {
  let defaultMode: TimetableMode = 'class'
  let defaultTargetId: number | null = ctx.classes[0]?.id ?? null

  if (!ctx.canManageSchedule && ctx.ownTeacherId != null) {
    defaultMode = 'teacher'
    defaultTargetId = ctx.ownTeacherId
  } else if (!ctx.canViewAll) {
    if (ctx.canViewClassScope && ctx.headClasses.length > 0) {
      defaultMode = 'class'
      defaultTargetId = ctx.headClasses[0].id
    } else {
      defaultMode = 'teacher'
      defaultTargetId = ctx.ownTeacherId ?? null
    }
  }

  const allowedModes: TimetableMode[] = !ctx.canViewAll && !ctx.canViewClassScope
    ? ['teacher']
    : ctx.canViewAll
      ? ['class', 'teacher', 'school']
      : ctx.headClasses.length > 0
        ? ['class', 'teacher']
        : ['teacher']

  const parsed = parseTimetableQuery(query)
  const mode = parsed.mode && allowedModes.includes(parsed.mode) ? parsed.mode : defaultMode

  let targetId: number | null = null
  if (mode === 'class') {
    const pool = ctx.canViewAll ? ctx.classes : ctx.headClasses
    if (parsed.id != null && pool.some((item) => item.id === parsed.id)) {
      targetId = parsed.id
    } else if (mode === defaultMode && defaultTargetId != null && pool.some((item) => item.id === defaultTargetId)) {
      targetId = defaultTargetId
    } else {
      targetId = pool[0]?.id ?? null
    }
  } else if (mode === 'teacher') {
    if (!ctx.canViewAll && !ctx.canViewClassScope) {
      targetId = ctx.ownTeacherId ?? null
    } else if (parsed.id != null && ctx.teachers.some((item) => item.id === parsed.id)) {
      targetId = parsed.id
    } else if (
      mode === defaultMode &&
      defaultTargetId != null &&
      (ctx.teachers.length === 0 ||
        ctx.teachers.some((item) => item.id === defaultTargetId) ||
        defaultTargetId === ctx.ownTeacherId)
    ) {
      targetId = defaultTargetId
    } else {
      targetId = ctx.teachers[0]?.id ?? ctx.ownTeacherId ?? null
    }
  }

  return {
    mode,
    targetId,
    date: parsed.date ?? ctx.defaultDate,
    view: parsed.view ?? 'latest',
  }
}

export function buildTimetableQuery(state: TimetableFilterState): Record<string, string> {
  const query: Record<string, string> = {
    mode: state.mode,
    view: state.view,
  }
  if (state.mode !== 'school' && state.targetId != null) {
    query.id = String(state.targetId)
  }
  if (state.date) {
    query.date = state.date
  }
  return query
}
