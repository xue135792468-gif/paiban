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

/** One editable row of a multi-entry type, with a client-side stable key. */
export interface EntryDraft {
  key: string
  id: number | null
  values: Record<string, unknown>
}

/**
 * A whole constraint type as the page edits it: type-level controls, the legacy
 * single-parameter values and, for multi-entry types, the entry list.
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
