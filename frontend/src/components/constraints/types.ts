/**
 * Shape of the constraint configuration page. The type catalogue
 * (`GET /api/constraint-types`) declares the parameter schema; a saved config
 * (`GET /api/constraints`) carries the type-level controls plus its entries.
 */

export interface ParamDef {
  name: string
  label: string
  type: string
  required: boolean
  min?: number | null
  max?: number | null
  defaultValue?: unknown
  options?: string[]
}

export interface ConstraintTypeDef {
  code: string
  name: string
  category: string
  categoryLabel: string
  description: string
  defaultHard: boolean
  defaultWeight: number
  params: ParamDef[]
}

export interface ConstraintEntryDto {
  id: number | null
  params: Record<string, unknown>
}

export interface ConstraintConfigDto {
  type: string
  params: Record<string, unknown>
  weight: number
  hard: boolean
  enabled: boolean
  entries?: ConstraintEntryDto[] | null
}

/**
 * One (weekday, period) inside a preference-slot card. The server stores one entry
 * per slot and combines the slots of a teacher with "or", so a card owns several of
 * these while a card of any other type owns none.
 */
export interface SlotDraft {
  key: string
  id: number | null
  dayOfWeek: number | null
  periodNo: number | null
}

/** One editable row of a multi-entry type, with a client-side stable key. */
export interface EntryDraft {
  key: string
  id: number | null
  /** Object-level values (`teacherId` for a slot-set card, the object otherwise). */
  values: Record<string, unknown>
  /** Only the slot-set types use this; empty for every other entry-backed type. */
  slots?: SlotDraft[]
  /**
   * Parameters this card explicitly targets with "every course / every subject /
   * sport class". Absent means the choice follows the value: no value → the whole
   * target, a value → that one object. Keeping the choice explicit is what lets the
   * card show a ticked box instead of an empty dropdown.
   */
  allTarget?: string[]
}

/**
 * A whole constraint type as the page edits it: type-level controls, the legacy
 * single-parameter values and, for entry-backed types, the entry list.
 */
export interface TypeDraft {
  code: string
  name: string
  description: string
  category: string
  categoryLabel: string
  params: ParamDef[]
  values: Record<string, unknown>
  entries: EntryDraft[]
  enabled: boolean
  hard: boolean
  weight: number
  /**
   * Whether a single-parameter type carries a condition the user or the server put
   * there. A form pre-filled with catalogue defaults is not an entry by itself, so
   * a closed, untouched row must not be saved (or counted) as one.
   */
  touched: boolean
}

export interface ConstraintCounts {
  enabledTypes: number
  totalTypes: number
  hardTypes: number
  entries: number
}

export interface NavGroup {
  category: string
  categoryLabel: string
  counts: ConstraintCounts
  rows: TypeDraft[]
}

export interface SaveEntry {
  id?: number | null
  params: Record<string, unknown>
}

/** One config in `PUT /api/constraints`. `entries` wins when present. */
export interface ConstraintConfigRequest {
  type: string
  params?: Record<string, unknown>
  entries?: SaveEntry[]
  weight: number
  hard: boolean
  enabled: boolean
}

export interface OptionItem {
  id: number
  name: string
}
