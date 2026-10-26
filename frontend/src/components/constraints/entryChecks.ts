import {
  allTargetLabel,
  isEntryType,
  isSlotSetType,
  isThresholdType,
  optionalTargetParam,
  plannedEntries,
  targetUnresolved,
  type PlannedEntry,
} from './entryModel'
import { filledValues, hasValue, roomTypeLabel } from './paramValues'
import type { SummaryContext } from './entrySummary'
import type { TypeDraft } from './types'

function signatureOf(params: Record<string, unknown>): string {
  return Object.keys(params)
    .sort()
    .map((key) => `${key}=${String(params[key])}`)
    .join('|')
}

/**
 * Entries that repeat an earlier one, per card. The server rejects duplicates on
 * the normalised parameter map (including two empty "every course" entries), so the
 * page predicts exactly that from the same values. Only complete entries can
 * collide — an unfinished card is not compared.
 */
export function duplicateMessages(row: TypeDraft): Record<number, string[]> {
  const firstSeen = new Map<string, PlannedEntry>()
  const messages: Record<number, string[]> = {}
  plannedEntries(row)
    .filter((entry) => entry.complete)
    .forEach((entry) => {
      const signature = signatureOf(entry.params)
      const first = firstSeen.get(signature)
      if (!first) {
        firstSeen.set(signature, entry)
        return
      }
      const text =
        first.cardIndex === entry.cardIndex && entry.slotIndex !== undefined
          ? `本条第${entry.slotIndex + 1}个时段与第${(first.slotIndex ?? 0) + 1}个时段相同，保存会被拒绝。`
          : `与第 ${first.cardIndex + 1} 条完全相同，保存会被拒绝。`
      const bucket = (messages[entry.cardIndex] ??= [])
      bucket.push(text)
    })
  return messages
}

/** What a single-value (threshold) type sends as `params`. */
function thresholdParams(row: TypeDraft): Record<string, unknown> {
  if (!row.enabled && !row.touched) {
    return {}
  }
  return filledValues(row.values, row.params)
}

/**
 * Save-time warnings for required parameters, checked against what the payload will
 * send rather than against whether the type is switched on: a condition survives
 * turning its type off, so a half-filled condition would still be rejected. A fully
 * blank card carries nothing (or is the explicit "every course" entry) and is
 * silently skipped instead of nagging.
 */
export function missingParamWarnings(rows: TypeDraft[]): string[] {
  const warnings: string[] = []
  rows.forEach((row) => {
    if (isThresholdType(row)) {
      const sent = thresholdParams(row)
      if (Object.keys(sent).length === 0 && !row.enabled) {
        return
      }
      row.params
        .filter((param) => param.required && !hasValue(row.values[param.name]))
        .forEach((param) => warnings.push(`「${row.name}」的「${param.label}」`))
      return
    }
    if (!isEntryType(row)) {
      return
    }
    row.entries.forEach((entry, index) => {
      const card = index + 1
      if (isSlotSetType(row)) {
        const slots = entry.slots ?? []
        if (slots.length === 0) {
          warnings.push(`「${row.name}」第${card}条还没有时段`)
          return
        }
        if (!hasValue(entry.values.teacherId)) {
          warnings.push(`「${row.name}」第${card}条的「教师」`)
        }
        slots.forEach((slot, slotIndex) => {
          row.params
            .filter(
              (param) =>
                param.required &&
                param.name !== 'teacherId' &&
                !hasValue(slot[param.name as 'dayOfWeek' | 'periodNo']),
            )
            .forEach((param) =>
              warnings.push(`「${row.name}」第${card}条第${slotIndex + 1}个时段的「${param.label}」`),
            )
        })
        return
      }
      const filled = filledValues(entry.values, row.params)
      const target = optionalTargetParam(row.params)
      if (Object.keys(filled).length === 0) {
        if (target && targetUnresolved(entry, row.params)) {
          warnings.push(
            `「${row.name}」第${card}条：请选择目标，或勾选「${allTargetLabel(target)}」`,
          )
        }
        return
      }
      if (target && targetUnresolved(entry, row.params)) {
        warnings.push(`「${row.name}」第${card}条：请选择目标，或勾选「${allTargetLabel(target)}」`)
      }
      row.params
        .filter((param) => param.required && !hasValue(filled[param.name]))
        .forEach((param) => warnings.push(`「${row.name}」第${card}条的「${param.label}」`))
    })
  })
  return warnings
}

/**
 * The scalar conditions a type attaches to one object, mirroring the server's
 * `SCALAR_SPECS`: two entries for the same course with different values contradict
 * each other, so the page says so before the request instead of letting the server
 * reject the whole save. Nothing is "fixed" silently — the user decides.
 */
export const SCALAR_SPECS: Record<string, { object: string; scalars: string[] }> = {
  COURSE_REQUIRED_ROOM_TYPE: { object: 'courseId', scalars: ['roomType'] },
  CONSECUTIVE_LESSONS: { object: 'courseId', scalars: ['minConsecutive'] },
  MAIN_SUBJECT_MORNING: { object: 'courseId', scalars: ['beforePeriodNo'] },
}

function displayValue(field: string, value: unknown): string {
  return field === 'roomType' ? roomTypeLabel(value) : String(value)
}

interface ScalarConflict {
  message: string
  /** 1-based request positions of the contradicting entries. */
  positions: number[]
}

function scalarConflictBuckets(
  rows: TypeDraft[],
  ctx: SummaryContext,
): ScalarConflict[] {
  const conflicts: ScalarConflict[] = []
  rows.forEach((row) => {
    const spec = SCALAR_SPECS[row.code]
    if (!spec) {
      return
    }
    const byObject = new Map<string, PlannedEntry[]>()
    plannedEntries(row)
      .filter((entry) => hasValue(entry.params[spec.object]))
      .forEach((entry) => {
        const key = String(entry.params[spec.object])
        const bucket = byObject.get(key) ?? []
        bucket.push(entry)
        byObject.set(key, bucket)
      })
    byObject.forEach((entries, objectId) => {
      spec.scalars.forEach((field) => {
        const values = entries
          .filter((entry) => hasValue(entry.params[field]))
          .map((entry) => entry.params[field])
        const distinct = Array.from(new Set(values.map((value) => String(value))))
        if (distinct.length < 2) {
          return
        }
        const label = row.params.find((param) => param.name === field)?.label ?? field
        const objectName = ctx.courseNames.get(Number(objectId)) ?? `课程#${objectId}`
        const positions = entries.map((entry) => entry.position)
        conflicts.push({
          positions,
          message: `「${row.name}」：课程「${objectName}」在第 ${positions.join('、')} 条给了不同的「${label}」（${distinct
            .map((value) => displayValue(field, value))
            .join(' / ')}），同一目标不能并存，保存会被拒绝。`,
        })
      })
    })
  })
  return conflicts
}

export function scalarConflicts(
  rows: TypeDraft[],
  ctx: SummaryContext = { teacherNames: new Map(), courseNames: new Map() },
): string[] {
  return scalarConflictBuckets(rows, ctx).map((conflict) => conflict.message)
}

/** The same contradictions, located on the cards they belong to. */
export function scalarConflictsByCard(
  row: TypeDraft,
  ctx: SummaryContext = { teacherNames: new Map(), courseNames: new Map() },
): Record<number, string[]> {
  const result: Record<number, string[]> = {}
  const planned = plannedEntries(row)
  scalarConflictBuckets([row], ctx).forEach((conflict) => {
    planned
      .filter((entry) => conflict.positions.includes(entry.position))
      .forEach((entry) => {
        const bucket = (result[entry.cardIndex] ??= [])
        bucket.push(conflict.message)
      })
  })
  return result
}

/**
 * Server messages for one card. Messages are located by the position the entry had
 * in the request, which is why a slot-set card can own several positions.
 */
export function entryErrorsOf(
  row: TypeDraft,
  cardIndex: number,
  byPosition: Record<number, string[]>,
): string[] {
  const messages: string[] = []
  plannedEntries(row)
    .filter((entry) => entry.cardIndex === cardIndex)
    .forEach((entry) => {
      ;(byPosition[entry.position] ?? []).forEach((message) => messages.push(message))
    })
  return messages
}
