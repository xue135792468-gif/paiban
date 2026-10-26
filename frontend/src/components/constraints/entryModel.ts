import { filledValues, hasValue } from './paramValues'
import type { EntryDraft, ParamDef, TypeDraft } from './types'

/**
 * What a constraint type's entry model is, read off the catalogue alone:
 * - no parameters at all → a single switch (the six conflict/pedagogy switches);
 * - exactly one integer parameter → a single-value threshold, still one number per
 *   type (ticket 04 widens these to defaults plus per-object exceptions);
 * - anything else → entry-backed: one card per object/condition.
 *
 * The backend registry is `ConstraintType.ENTRY_BACKED`; this partition is the
 * front-end mirror of it, derived from the parameter schemas so the page cannot
 * drift when a type changes shape.
 */
export type TypeShape = 'SWITCH' | 'THRESHOLD' | 'ENTRY'

export function typeShape(row: { params: ParamDef[] }): TypeShape {
  if (row.params.length === 0) {
    return 'SWITCH'
  }
  if (row.params.length === 1 && row.params[0].type === 'INT') {
    return 'THRESHOLD'
  }
  return 'ENTRY'
}

export function isSwitchType(row: { params: ParamDef[] }): boolean {
  return typeShape(row) === 'SWITCH'
}

export function isThresholdType(row: { params: ParamDef[] }): boolean {
  return typeShape(row) === 'THRESHOLD'
}

export function isEntryType(row: { params: ParamDef[] }): boolean {
  return typeShape(row) === 'ENTRY'
}

/**
 * Types whose one card holds a set of slots that the server stores as one entry
 * per slot and combines as "or". The catalogue cannot express this difference:
 * `TEACHER_PREFERRED_SLOT` and `TEACHER_UNAVAILABLE` declare exactly the same three
 * parameters, but the first means "any of these slots satisfies the preference"
 * and the second means "none of these slots may be used".
 */
export const SLOT_SET_TYPES = ['TEACHER_PREFERRED_SLOT']

export function isSlotSetType(row: { code: string }): boolean {
  return SLOT_SET_TYPES.includes(row.code)
}

/** The parameter a card may leave blank: "every course / every subject / sport class". */
export function optionalTargetParam(params: ParamDef[]): ParamDef | null {
  return params.find((param) => !param.required) ?? null
}

const OBJECT_NOUNS: Record<string, string> = {
  teacherId: '教师',
  courseId: '课程',
  subject: '学科',
}

/**
 * The words a blank optional target means for the user. Derived from the catalogue
 * label's own parenthetical ("课程（留空表示体育类）"), so the checkbox says the same
 * thing the form always did: 全部课程 / 全部学科 / 体育类.
 */
export function allTargetLabel(param: ParamDef): string {
  const noun = OBJECT_NOUNS[param.name] ?? param.label
  if (param.required) {
    // A required object has no "every …" form; the noun is still useful for labels.
    return noun
  }
  const phrase = /留空表示([^）)]+)/.exec(param.label)?.[1]?.trim()
  if (!phrase || phrase === '全部') {
    return `全部${noun}`
  }
  return phrase
}

/**
 * Whether a card targets the whole object for this parameter. The card carries the
 * choice explicitly (`allTarget`), so unticking the box and clearing the dropdown
 * still read as different states; when the card has no explicit choice yet (legacy
 * row, freshly loaded data) the absence of a value is the "every …" target.
 */
export function declaresAllTarget(
  entry: { allTarget?: string[]; values: Record<string, unknown> },
  param: ParamDef,
): boolean {
  // A required object has no "every …" form: an empty field there means "not filled
  // in yet", and the control must stay usable.
  if (param.required || hasValue(entry.values[param.name])) {
    return false
  }
  return entry.allTarget === undefined ? true : entry.allTarget.includes(param.name)
}

/**
 * The user unticked "every …" but has not picked an object yet. Such a card must not
 * silently become an "every course" entry (the server would store `{}`), so it is
 * neither counted nor saved, and the required-parameter warning names it.
 */
export function targetUnresolved(
  entry: { allTarget?: string[]; values: Record<string, unknown> },
  params: ParamDef[],
): boolean {
  const target = optionalTargetParam(params)
  return (
    target !== null &&
    entry.allTarget !== undefined &&
    !entry.allTarget.includes(target.name) &&
    !hasValue(entry.values[target.name])
  )
}

/** The card's values with every explicit "every …" target blanked out. */
function effectiveValues(entry: EntryDraft): Record<string, unknown> {
  if (!entry.allTarget || entry.allTarget.length === 0) {
    return entry.values
  }
  const values = { ...entry.values }
  entry.allTarget.forEach((name) => {
    values[name] = null
  })
  return values
}

/** One entry as it will appear in `PUT /api/constraints` — same order, same params. */
export interface PlannedEntry {
  /** 1-based position in the request's `entries` array; the server locates errors by it. */
  position: number
  /** Card the entry came from (0-based). */
  cardIndex: number
  /** Slot inside the card, for the types that store one entry per slot. */
  slotIndex?: number
  /** Stable identity returned by the server, so an edit updates instead of replacing. */
  id: number | null
  params: Record<string, unknown>
  /** False when a required parameter is missing; such an entry is counted but not saved. */
  complete: boolean
}

function allRequiredPresent(params: ParamDef[], values: Record<string, unknown>): boolean {
  return params.every((param) => !param.required || hasValue(values[param.name]))
}

/**
 * The request entries one type would send, in request order. This is the single
 * source of the save payload, the entry count and the position the server's error
 * messages come back with, so the three can never disagree.
 */
export function plannedEntries(row: TypeDraft): PlannedEntry[] {
  const planned: PlannedEntry[] = []
  const push = (entry: Omit<PlannedEntry, 'position'>) => {
    planned.push({ ...entry, position: planned.length + 1 })
  }
  row.entries.forEach((entry, cardIndex) => {
    if (isSlotSetType(row)) {
      const teacherId = entry.values.teacherId
      ;(entry.slots ?? []).forEach((slot, slotIndex) => {
        const params: Record<string, unknown> = {}
        if (hasValue(teacherId)) {
          params.teacherId = teacherId
        }
        if (hasValue(slot.dayOfWeek)) {
          params.dayOfWeek = slot.dayOfWeek
        }
        if (hasValue(slot.periodNo)) {
          params.periodNo = slot.periodNo
        }
        if (Object.keys(params).length === 0) {
          return
        }
        push({
          cardIndex,
          slotIndex,
          id: slot.id,
          params,
          complete: allRequiredPresent(row.params, params),
        })
      })
      return
    }

    const params = filledValues(effectiveValues(entry), row.params)
    const unresolved = targetUnresolved(entry, row.params)
    if (Object.keys(params).length === 0) {
      // A card whose object cannot be blank carries no condition yet: nothing is
      // stored for it (the same rule the old single-value form used). For a type
      // whose target may be blank the empty card *is* the explicit "every course /
      // sport class" entry — but only while that choice is actually ticked.
      if (unresolved || row.params.some((param) => param.required)) {
        return
      }
      push({ cardIndex, id: entry.id, params: {}, complete: true })
      return
    }
    push({
      cardIndex,
      id: entry.id,
      params,
      complete: !unresolved && allRequiredPresent(row.params, params),
    })
  })
  return planned
}

/**
 * How many concrete entries a type contributes to `具体条目 M 条`: exactly the
 * entries the save will store, so the badge can never promise one more or less.
 * A slot-set card contributes one per slot (the server stores one slot per entry).
 */
export function draftEntryCount(row: TypeDraft): number {
  if (!isEntryType(row)) {
    return 0
  }
  return plannedEntries(row).filter((entry) => entry.complete).length
}
