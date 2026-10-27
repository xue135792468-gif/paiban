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
  /** True when the type stores its parameters as entry rows (empty list = unconfigured). */
  entryBacked?: boolean
  /** True when one editable card owns several weekday/period rows (one requirement). */
  slotGrouped?: boolean
  /** Threshold types only: the entry field that names the object (`teacherId`, `classId`, `courseId`). */
  thresholdObjectField?: string | null
  /** Threshold types only: the entry field that carries the number. */
  thresholdValueField?: string | null
  params: ParamDef[]
}

export interface ConstraintEntryDto {
  id: number | null
  params: Record<string, unknown>
  /** A paused requirement stays stored but is not evaluated. */
  enabled?: boolean
  /** Null means "inherit the type's hard/soft choice". */
  hard?: boolean | null
  /** Null means "inherit the type's soft weight". */
  weight?: number | null
  /** Rows sharing this key are one requirement (one expanded slot set). */
  groupKey?: string | null
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
  /** Requirement identity shared by every row this card stores. */
  groupKey?: string | null
  /** Pausing keeps the requirement but takes it out of evaluation. */
  enabled?: boolean
  /** Null (or absent) inherits the type default; true/false override it. */
  hard?: boolean | null
  /** Null (or absent) inherits the type default soft weight. */
  weight?: number | null
}

/**
 * One per-object exception of a threshold type: "this teacher may teach at most N a
 * day" while every other object keeps the global default.
 */
export interface ThresholdExceptionDraft {
  key: string
  id: number | null
  objectId: number | null
  value: number | null
  enabled?: boolean
  /** Null or absent inherits the type default. */
  hard?: boolean | null
  /** Null or absent inherits the type default. */
  weight?: number | null
}

/**
 * A threshold type as the page edits it. A threshold stores one entry per scope: the
 * global default (no object field) plus one exception per object, so these two pieces
 * are the whole state — no card list, because a card list would let the page express
 * two global defaults that the server refuses.
 */
export interface ThresholdDraft {
  globalId: number | null
  /** The global default; null means "no global default is configured". */
  globalValue: number | null
  globalEnabled?: boolean
  globalHard?: boolean | null
  globalWeight?: number | null
  exceptions: ThresholdExceptionDraft[]
}

/**
 * A whole constraint type as the page edits it: the type-level controls plus the
 * parameters in the shape the catalogue declares — an entry list for the 13
 * entry-backed types, a global default plus exceptions for the 5 thresholds, and
 * nothing but the switch for the 6 single-switch types.
 */
export interface TypeDraft {
  code: string
  name: string
  description: string
  category: string
  categoryLabel: string
  params: ParamDef[]
  /** True when one card owns several weekday/period rows. */
  slotGrouped?: boolean
  /** Threshold types only: the same fields the catalogue declares, copied for convenience. */
  thresholdObjectField?: string | null
  thresholdValueField?: string | null
  entries: EntryDraft[]
  threshold?: ThresholdDraft
  enabled: boolean
  hard: boolean
  weight: number
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
  enabled?: boolean
  hard?: boolean | null
  weight?: number | null
  groupKey?: string | null
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

/**
 * The objects a constraint entry may name, kept per field. Teachers, administrative
 * classes and courses live in three separate id spaces, so they are never merged into
 * one lookup: the field the entry uses decides which list applies, and two objects that
 * happen to share an id can never be confused for each other.
 */
export interface ObjectOptions {
  teacherId: OptionItem[]
  classId: OptionItem[]
  courseId: OptionItem[]
}

/** The same three fields as id → name maps, for labelling one row. */
export type ObjectNames = { [Key in keyof ObjectOptions]: Map<number, string> }

/** A saved template as `GET /api/constraint-templates` lists it. */
export interface TemplateItem {
  id: number
  name: string
  createdAt?: string
  /** Type rows the template holds. */
  configCount: number
  /** Parameter entries it will restore. */
  entryCount: number
}

/**
 * One template reference the target semester cannot take. `classId` rows can be
 * rebound to a class of the target semester; teachers and courses are global assets,
 * so a missing one has to be restored before the template can be applied.
 */
export interface TemplateReferenceIssue {
  type: string
  typeName: string
  /** 1-based entry inside the type; 0 when the template carries a single params map. */
  position: number
  field: string
  fieldLabel: string
  objectId: number
  name: string | null
  reason: string
}

/** `POST /api/constraint-templates/{id}/preview` — a dry run, nothing is written. */
export interface TemplateApplyPreview {
  configCount: number
  entryCount: number
  invalidReferences: TemplateReferenceIssue[]
  /** Other reasons the apply would be refused (slots outside the timetable, …). */
  errors: string[]
}
