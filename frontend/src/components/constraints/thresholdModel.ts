import type { ObjectNames, ParamDef, ThresholdDraft, TypeDraft } from './types'
import { hasValue } from './paramValues'
import type { PlannedEntry } from './entryModel'

/** No names known (e.g. a unit test that only checks the message shape). */
export function emptyObjectNames(): ObjectNames {
  return { teacherId: new Map(), classId: new Map(), courseId: new Map() }
}

/**
 * The name lookup for one entry field. A threshold names exactly one kind of object, so
 * asking for its own field can never return a same-id teacher, class or course by
 * accident — which is what a single merged map used to do.
 */
export function namesForField(names: ObjectNames, field: string | null | undefined): Map<number, string> {
  if (field === 'teacherId' || field === 'classId' || field === 'courseId') {
    return names[field]
  }
  return new Map()
}

/**
 * What a threshold type's entries mean. A threshold stores one entry per scope:
 *
 * - no object field  → the global default, which applies to every object;
 * - a filled object  → that object's exception, which replaces the global default for
 *   that object only.
 *
 * The server resolves "exception, else global, else no limit", so an exceptions-only
 * configuration must not pretend the other objects are limited by anything.
 */
export interface ThresholdShape {
  /** The entry field naming the object (`teacherId` / `classId` / `courseId`). */
  objectField: string
  /** The entry field carrying the number (`maxPerDay` / `maxConsecutive`). */
  valueField: string
  objectParam: ParamDef
  valueParam: ParamDef
}

/** The scope of one threshold type, or null for every other type. */
export function thresholdShape(row: {
  params: ParamDef[]
  thresholdObjectField?: string | null
  thresholdValueField?: string | null
}): ThresholdShape | null {
  const objectField = row.thresholdObjectField
  const valueField = row.thresholdValueField
  if (!objectField || !valueField) {
    return null
  }
  const objectParam = row.params.find((param) => param.name === objectField)
  const valueParam = row.params.find((param) => param.name === valueField)
  if (!objectParam || !valueParam) {
    return null
  }
  return { objectField, valueField, objectParam, valueParam }
}

/** An empty draft: no global default, no exceptions (= the type is not configured). */
export function emptyThreshold(): ThresholdDraft {
  return { globalId: null, globalValue: null, exceptions: [] }
}

/**
 * The entries one threshold type would send, in request order: the global default
 * first (when set), then every exception that has both an object and a value.
 *
 * Positions run 1..N across the whole request, exactly like the 13 entry-backed types,
 * so the server's 「第N条」 errors land on the right row. `cardIndex` is the editor row
 * (0 = the global default, i+1 = the i-th exception), never a request position.
 */
export function thresholdEntries(row: TypeDraft): PlannedEntry[] {
  const shape = thresholdShape(row)
  const draft = row.threshold
  if (!shape || !draft) {
    return []
  }
  const planned: PlannedEntry[] = []
  const push = (cardIndex: number, id: number | null, objectId: number | null, value: number | null) => {
    if (!hasValue(value)) {
      return
    }
    const params: Record<string, unknown> = {}
    if (objectId !== null) {
      params[shape.objectField] = objectId
    }
    params[shape.valueField] = Number(value)
    planned.push({
      position: planned.length + 1,
      cardIndex,
      id,
      params,
      complete: true,
    })
  }
  // An exception without an object is not stored: sending only the number would turn
  // it into a second global default, which the server rejects (and which the user did
  // not ask for).
  push(0, draft.globalId, null, draft.globalValue)
  draft.exceptions.forEach((exception, index) => {
    if (exception.objectId === null) {
      return
    }
    push(index + 1, exception.id, exception.objectId, exception.value)
  })
  return planned
}

/** How many entries the type contributes to 具体条目 M 条 (one per scope). */
export function thresholdEntryCount(row: TypeDraft): number {
  return thresholdEntries(row).length
}

/**
 * Save-time warnings for the rows that cannot be stored: an exception without an
 * object, or a row without its number. A fully blank threshold stays silent — it is
 * simply "not configured", the same rule the entry-backed types follow.
 */
export function thresholdWarnings(row: TypeDraft): string[] {
  const draft = row.threshold
  const shape = thresholdShape(row)
  if (!draft || !shape) {
    return []
  }
  const warnings: string[] = []
  if (draft.exceptions.some((exception) => exception.objectId === null)) {
    warnings.push(`「${row.name}」有例外没有选择${shape.objectParam.label.replace(/（[^）]*）/g, '')}`)
  }
  if (draft.exceptions.some((exception) => exception.objectId !== null && !hasValue(exception.value))) {
    warnings.push(`「${row.name}」有例外没填「${shape.valueParam.label}」`)
  }
  return warnings
}

/**
 * Two rows for the same scope (two global defaults, or two exceptions for the same
 * object) would leave "which value applies" to the read order, so the server refuses
 * the whole request. Predict exactly that here.
 *
 * `names` is keyed by entry field, so the label of an exception always comes from the
 * list that type actually uses (a teacher limit never labels its row with a course).
 */
export function thresholdConflicts(
  row: TypeDraft,
  names: ObjectNames = emptyObjectNames(),
): Array<{ message: string; cardIndexes: number[] }> {
  const draft = row.threshold
  if (!draft) {
    return []
  }
  const conflicts: Array<{ message: string; cardIndexes: number[] }> = []
  const seen = new Map<string, number>()
  const objectNames = namesForField(names, thresholdShape(row)?.objectField)
  thresholdEntries(row).forEach((entry, cardIndex) => {
    const shape = thresholdShape(row)
    const objectId = shape ? entry.params[shape.objectField] : null
    const scope = objectId === null || objectId === undefined ? 'global' : `object-${objectId}`
    const first = seen.get(scope)
    if (first === undefined) {
      seen.set(scope, cardIndex)
      return
    }
    const where =
      scope === 'global'
        ? '全局默认'
        : `对象「${objectNames.get(Number(objectId)) ?? `#${objectId}`}」`
    conflicts.push({
      message: `「${row.name}」：第 ${first + 1} 条与第 ${cardIndex + 1} 条都是${where}，同一作用域最多一条阈值，保存会被拒绝。`,
      cardIndexes: [first, cardIndex],
    })
  })
  return conflicts
}

/**
 * The values of one threshold entry as the editor shows them: the object id (null for
 * the global default) and the number.
 */
export function thresholdRowValues(
  row: TypeDraft,
  params: Record<string, unknown>,
): { objectId: number | null; value: number | null } {
  const shape = thresholdShape(row)
  if (!shape) {
    return { objectId: null, value: null }
  }
  const rawObject = params[shape.objectField]
  const rawValue = params[shape.valueField]
  return {
    objectId: hasValue(rawObject) ? Number(rawObject) : null,
    value: hasValue(rawValue) ? Number(rawValue) : null,
  }
}
