import type {
  ConstraintConfigRequest,
  ConstraintCounts,
  EntryDraft,
  NavGroup,
  ParamDef,
  TypeDraft,
} from './types'

/**
 * The only multi-entry type in this ticket. Everything else keeps the legacy
 * single-parameter form, so this list is the one place that decides which panel
 * the page renders.
 */
export const MULTI_ENTRY_TYPES = ['TEACHER_UNAVAILABLE']

/** `已启用 N/24 类` needs a stable order, not the catalogue order. */
const CATEGORY_ORDER = ['CONFLICT', 'RESOURCE', 'PEDAGOGY', 'PREFERENCE']

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

export function isSwitchType(row: { params: ParamDef[] }): boolean {
  return row.params.length === 0
}

export function isMultiEntryType(row: { code: string }): boolean {
  return MULTI_ENTRY_TYPES.includes(row.code)
}

/**
 * Draft values are `unknown` because the catalogue only describes them at runtime;
 * these narrow them for the Element Plus inputs, which type their models but not the
 * meaning of each parameter.
 */
export type SelectValue = string | number | boolean | Record<string, any> | null | undefined

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

function hasValue(value: unknown): boolean {
  return value !== null && value !== undefined && value !== ''
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

/**
 * How many concrete entries a type contributes to `具体条目 M 条`.
 * The number is read off the same values `buildSavePayload` sends, so the badge can
 * never promise an entry the save will drop:
 * - a switch type never has entries;
 * - a multi-entry type counts the complete cards (an untouched card carries no
 *   condition and is not stored);
 * - a single-parameter type is one entry once it has a value the user or the server
 *   put there, and it keeps that entry when the type is switched off.
 */
export function draftEntryCount(row: TypeDraft): number {
  if (isSwitchType(row)) {
    return 0
  }
  if (isMultiEntryType(row)) {
    return row.entries.filter((entry) => Object.keys(filledValues(entry.values, row.params)).length > 0)
      .length
  }
  return Object.keys(singleParams(row)).length > 0 ? 1 : 0
}

/**
 * What a single-parameter type sends as `params`. A row that is switched on or was
 * configured sends its values; a closed, never-touched row sends `{}` so the
 * catalogue defaults on screen do not become a stored entry by accident.
 */
function singleParams(row: TypeDraft): Record<string, unknown> {
  if (!row.enabled && !row.touched) {
    return {}
  }
  return filledValues(row.values, row.params)
}

/**
 * The two counts the page shows side by side: types and entries are never mixed.
 * Hard types are counted only when enabled — a disabled hard row is not in play.
 */
export function countsOf(rows: TypeDraft[]): ConstraintCounts {
  return rows.reduce<ConstraintCounts>(
    (counts, row) => {
      counts.totalTypes += 1
      if (row.enabled) {
        counts.enabledTypes += 1
        if (row.hard) {
          counts.hardTypes += 1
        }
      }
      counts.entries += draftEntryCount(row)
      return counts
    },
    { enabledTypes: 0, totalTypes: 0, hardTypes: 0, entries: 0 },
  )
}

export function groupRows(rows: TypeDraft[]): NavGroup[] {
  const groups = new Map<string, TypeDraft[]>()
  rows.forEach((row) => {
    const bucket = groups.get(row.category) ?? []
    bucket.push(row)
    groups.set(row.category, bucket)
  })
  return Array.from(groups.entries())
    .sort(([left], [right]) => {
      const leftIndex = CATEGORY_ORDER.indexOf(left)
      const rightIndex = CATEGORY_ORDER.indexOf(right)
      return (leftIndex < 0 ? CATEGORY_ORDER.length : leftIndex) -
        (rightIndex < 0 ? CATEGORY_ORDER.length : rightIndex)
    })
    .map(([category, groupRowsInCategory]) => ({
      category,
      categoryLabel: groupRowsInCategory[0].categoryLabel,
      counts: countsOf(groupRowsInCategory),
      rows: groupRowsInCategory,
    }))
}

function signatureOf(values: Record<string, unknown>, params: ParamDef[]): string {
  return params
    .filter((param) => hasValue(values[param.name]))
    .map((param) => `${param.name}=${String(values[param.name])}`)
    .join('|')
}

/**
 * Entries that repeat an earlier one. The backend rejects duplicates by comparing
 * the normalised parameter map, so the page predicts the same thing from the same
 * inputs: only complete entries can collide. Map values are the earlier 0-based index.
 */
export function duplicateEntryIndexes(
  entries: EntryDraft[],
  params: ParamDef[],
): Record<number, number> {
  const firstSeen = new Map<string, number>()
  const duplicates: Record<number, number> = {}
  entries.forEach((entry, index) => {
    const filled = filledValues(entry.values, params)
    const complete = params.every((param) => !param.required || hasValue(filled[param.name]))
    if (!complete) {
      return
    }
    const signature = signatureOf(entry.values, params)
    const first = firstSeen.get(signature)
    if (first === undefined) {
      firstSeen.set(signature, index)
    } else {
      duplicates[index] = first
    }
  })
  return duplicates
}

/** One-line card title: `张伟 · 周三 第2节`. Missing fields are named, not blank. */
export function entrySummary(
  values: Record<string, unknown>,
  teacherNames: Map<number, string>,
): string {
  const teacher = hasValue(values.teacherId)
    ? (teacherNames.get(Number(values.teacherId)) ?? `教师#${values.teacherId}`)
    : '未选教师'
  const day = hasValue(values.dayOfWeek)
    ? (DAY_OPTIONS.find((option) => option.value === Number(values.dayOfWeek))?.label ??
      `星期${values.dayOfWeek}`)
    : '未选星期'
  const period = hasValue(values.periodNo) ? `第${values.periodNo}节` : '未选节次'
  return `${teacher} · ${day} ${period}`
}

/**
 * Save-time warnings for required parameters. Checked against what the payload will
 * send, not against what is switched on: a condition survives turning its type off,
 * so a half-filled condition would still be rejected by the server. A fully blank
 * card/row carries nothing and is silently skipped instead of nagging.
 */
export function missingParamWarnings(rows: TypeDraft[]): string[] {
  const warnings: string[] = []
  rows
    .filter((row) => !isSwitchType(row))
    .forEach((row) => {
      if (isMultiEntryType(row)) {
        row.entries.forEach((entry, index) => {
          if (Object.keys(filledValues(entry.values, row.params)).length === 0) {
            return
          }
          row.params
            .filter((param) => param.required && !hasValue(entry.values[param.name]))
            .forEach((param) => warnings.push(`「${row.name}」第${index + 1}条的「${param.label}」`))
        })
        return
      }
      const sent = singleParams(row)
      // A switched-off, untouched row sends nothing, so it cannot be rejected. A
      // switched-on row must not save without its required condition — that is the
      // old warning, kept for the same reason.
      if (Object.keys(sent).length === 0 && !row.enabled) {
        return
      }
      row.params
        .filter((param) => param.required && !hasValue(row.values[param.name]))
        .forEach((param) => warnings.push(`「${row.name}」的「${param.label}」`))
    })
  return warnings
}

/**
 * `PUT /api/constraints` body. Multi-entry types send `entries` (never `params`),
 * every other type keeps the legacy `params` field so its meaning cannot drift.
 *
 * A switched-off type that was never configured is left out of the request: the
 * server rejects any config whose required parameters are empty (it validates the
 * legacy shape whether or not the type is on), and leaving the row out is exactly
 * what "no configuration" means — the semester simply has no row for it. Rows with
 * values, and rows the school mode hides, are always sent so a save never erases a
 * condition that exists.
 */
export function buildSavePayload(
  rows: TypeDraft[],
  options: { isJunior: boolean },
): ConstraintConfigRequest[] {
  return rows.flatMap<ConstraintConfigRequest>((row) => {
    // Junior mode only exposes "must / prefer": must = weight 100, prefer = 50.
    const weight = options.isJunior ? (row.hard ? 100 : 50) : row.weight
    if (isMultiEntryType(row)) {
      return [
        {
          type: row.code,
          entries: row.entries
            .map((entry) => ({ id: entry.id, params: filledValues(entry.values, row.params) }))
            .filter((entry) => Object.keys(entry.params).length > 0),
          weight,
          hard: row.hard,
          enabled: row.enabled,
        },
      ]
    }
    const params = singleParams(row)
    if (!row.enabled && Object.keys(params).length === 0) {
      return []
    }
    return [
      {
        type: row.code,
        params,
        weight,
        hard: row.hard,
        enabled: row.enabled,
      },
    ]
  })
}
