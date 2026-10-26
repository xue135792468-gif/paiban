import { computed, ref } from 'vue'
import { buildSavePayload, countsOf, groupRows } from './draftRules'
import { missingParamWarnings, scalarConflicts } from './entryChecks'
import { isEntryType, isSlotSetType, isThresholdType, optionalTargetParam } from './entryModel'
import { emptyThreshold, thresholdConflicts, thresholdWarnings } from './thresholdModel'
import { hasValue } from './paramValues'
import type {
  ConstraintConfigDto,
  ConstraintConfigRequest,
  ConstraintCounts,
  ConstraintTypeDef,
  EntryDraft,
  NavGroup,
  SlotDraft,
  ThresholdDraft,
  TypeDraft,
} from './types'

/** ADR-0018: junior mode hides senior-only constraints from the editor. */
const HIDDEN_IN_JUNIOR = ['TEACHER_MEETING_BLOCKED']

let keySeed = 0

function nextKey(prefix: string): string {
  keySeed += 1
  return `${prefix}-${keySeed}`
}

function emptySlot(): SlotDraft {
  return { key: nextKey('slot'), id: null, dayOfWeek: null, periodNo: null }
}

function valuesFrom(params: unknown): Record<string, unknown> {
  return params && typeof params === 'object' ? { ...(params as Record<string, unknown>) } : {}
}

/**
 * The saved entries of one type as editable cards. Entry-backed types only: an empty
 * list means "not configured" and is never turned into an implicit rule, so the old
 * `params` value is deliberately ignored for them (V26/V27 moved it into entries).
 *
 * A slot-set card (teacher preference) owns several slots, so the server's one-entry-
 * per-slot rows are grouped back into one card per teacher: the "or" lives in the card.
 */
function entriesOf(type: ConstraintTypeDef, saved: ConstraintConfigDto | undefined): EntryDraft[] {
  if (!isEntryType(type)) {
    return []
  }
  const savedEntries = saved?.entries ?? []
  if (savedEntries.length === 0) {
    return []
  }
  if (isSlotSetType(type)) {
    const cards = new Map<string, EntryDraft>()
    savedEntries.forEach((entry) => {
      const params = valuesFrom(entry.params)
      const group = hasValue(params.teacherId) ? `teacher-${params.teacherId}` : nextKey('loose')
      let card = cards.get(group)
      if (!card) {
        card = {
          key: nextKey('entry'),
          id: null,
          values: { teacherId: params.teacherId ?? null },
          slots: [],
        }
        cards.set(group, card)
      }
      card.slots!.push({
        key: nextKey('slot'),
        id: entry.id ?? null,
        dayOfWeek: (params.dayOfWeek as number | undefined) ?? null,
        periodNo: (params.periodNo as number | undefined) ?? null,
      })
    })
    return Array.from(cards.values())
  }
  return savedEntries.map((entry) => ({
    key: nextKey('entry'),
    id: entry.id ?? null,
    values: valuesFrom(entry.params),
  }))
}

function buildRow(type: ConstraintTypeDef, saved: ConstraintConfigDto | undefined): TypeDraft {
  const threshold = isThresholdType(type) ? thresholdDraftOf(type, saved) : undefined
  return {
    code: type.code,
    name: type.name,
    description: type.description,
    category: type.category,
    categoryLabel: type.categoryLabel,
    params: type.params,
    thresholdObjectField: type.thresholdObjectField ?? null,
    thresholdValueField: type.thresholdValueField ?? null,
    entries: entriesOf(type, saved),
    threshold,
    enabled: saved?.enabled ?? false,
    hard: saved?.hard ?? type.defaultHard,
    weight: saved?.weight ?? type.defaultWeight,
  }
}

/**
 * The saved entries of a threshold type as the two pieces the editor shows: the global
 * default (the entry without an object field) and one row per object exception.
 *
 * A response that only carries the legacy `params` map — an older server, or a template
 * written before ticket 04 — is read as the global default, which is exactly what V28
 * migrated it into. A `params` map that already names an object is read as that
 * object's exception.
 */
function thresholdDraftOf(
  type: ConstraintTypeDef,
  saved: ConstraintConfigDto | undefined,
): ThresholdDraft {
  const draft: ThresholdDraft = emptyThreshold()
  const objectField = type.thresholdObjectField
  const valueField = type.thresholdValueField
  if (!objectField || !valueField) {
    return draft
  }
  const sources = (saved?.entries ?? []).map((entry) => ({
    id: entry.id ?? null,
    params: valuesFrom(entry.params),
  }))
  if (sources.length === 0 && saved?.params) {
    sources.push({ id: null, params: valuesFrom(saved.params) })
  }
  sources.forEach((source) => {
    const objectId = hasValue(source.params[objectField]) ? Number(source.params[objectField]) : null
    const value = hasValue(source.params[valueField]) ? Number(source.params[valueField]) : null
    if (objectId === null) {
      // The server allows one global default; read the first and ignore a duplicate.
      if (draft.globalValue === null) {
        draft.globalId = source.id
        draft.globalValue = value
      }
      return
    }
    draft.exceptions.push({
      key: nextKey('exception'),
      id: source.id,
      objectId,
      value,
    })
  })
  return draft
}

/**
 * Page state for the constraint master-detail editor: the catalogue merged with the
 * saved configuration, the type-level controls, the entry lists, and the single
 * payload that both the summary counts and the save request are derived from.
 *
 * HTTP stays in the view: this composable never loads or saves by itself, so its
 * behaviour is testable without stubbing axios.
 */
export function useConstraintDraft() {
  const rows = ref<TypeDraft[]>([])
  const selectedCode = ref<string | null>(null)
  const isJuniorMode = ref(false)
  const snapshot = ref('')

  const visibleRows = computed(() =>
    isJuniorMode.value
      ? rows.value.filter((row) => !HIDDEN_IN_JUNIOR.includes(row.code))
      : rows.value,
  )

  // Counts cover every catalogue type, including the one the school mode hides:
  // it is still part of the 24 and still gets saved, so hiding it must not shrink
  // the denominator or drop its configured entries from the total.
  const counts = computed<ConstraintCounts>(() => countsOf(rows.value))

  const groups = computed<NavGroup[]>(() => groupRows(visibleRows.value))

  const selected = computed<TypeDraft | null>(
    () => visibleRows.value.find((row) => row.code === selectedCode.value) ?? null,
  )

  function payload(): ConstraintConfigRequest[] {
    return buildSavePayload(rows.value, { isJunior: isJuniorMode.value })
  }

  const dirty = computed(() => snapshot.value !== JSON.stringify(payload()))

  function find(code: string): TypeDraft | undefined {
    return rows.value.find((row) => row.code === code)
  }

  function hydrate(
    types: ConstraintTypeDef[],
    configs: ConstraintConfigDto[],
    options: { isJunior: boolean },
  ) {
    isJuniorMode.value = options.isJunior
    const saved = new Map(configs.map((config) => [config.type, config]))
    rows.value = types.map((type) => buildRow(type, saved.get(type.code)))

    const stillVisible = visibleRows.value.some((row) => row.code === selectedCode.value)
    if (!stillVisible) {
      // Default to a type that shows entries, so the main view opens on the lists.
      selectedCode.value =
        visibleRows.value.find((row) => isEntryType(row))?.code ??
        visibleRows.value[0]?.code ??
        null
    }
    markSaved()
  }

  function markSaved() {
    snapshot.value = JSON.stringify(payload())
  }

  function select(code: string) {
    selectedCode.value = code
  }

  function setEnabled(code: string, value: boolean) {
    const row = find(code)
    if (row) {
      row.enabled = value
    }
  }

  function setHard(code: string, value: boolean) {
    const row = find(code)
    if (row) {
      row.hard = value
    }
  }

  function setWeight(code: string, value: number) {
    const row = find(code)
    if (row) {
      row.weight = value
    }
  }

  function addEntry(code: string) {
    const row = find(code)
    if (!row) {
      return
    }
    // A preference-slot card starts with one empty slot; without a slot it has
    // nothing to store. Every other type stores the card itself, and a type whose
    // object may be blank starts on the explicit "every …" target (visible as a
    // ticked box, never as an empty dropdown).
    const target = isSlotSetType(row) ? null : optionalTargetParam(row.params)
    row.entries.push({
      key: nextKey('entry'),
      id: null,
      values: {},
      slots: isSlotSetType(row) ? [emptySlot()] : undefined,
      allTarget: target ? [target.name] : undefined,
    })
  }

  function removeEntry(code: string, key: string) {
    const row = find(code)
    if (row) {
      row.entries = row.entries.filter((entry) => entry.key !== key)
    }
  }

  function updateEntryValue(code: string, key: string, name: string, value: unknown) {
    const entry = find(code)?.entries.find((item) => item.key === key)
    if (entry) {
      entry.values[name] = value
      // Picking an object cancels an earlier "every …" choice for that parameter.
      if (hasValue(value) && entry.allTarget?.includes(name)) {
        entry.allTarget = entry.allTarget.filter((item) => item !== name)
      }
    }
  }

  /**
   * The explicit "every course / every subject / sport class" switch. Ticking it
   * clears the object value; unticking keeps the card empty so the user has to pick
   * one — the choice is never inferred from a blank dropdown.
   */
  function setAllTarget(code: string, entryKey: string, name: string, all: boolean) {
    const entry = find(code)?.entries.find((item) => item.key === entryKey)
    if (!entry) {
      return
    }
    const names = new Set(entry.allTarget ?? [])
    if (all) {
      names.add(name)
      entry.values[name] = null
    } else {
      names.delete(name)
    }
    entry.allTarget = Array.from(names)
  }

  function addSlot(code: string, entryKey: string) {
    const entry = find(code)?.entries.find((item) => item.key === entryKey)
    if (entry) {
      entry.slots = [...(entry.slots ?? []), emptySlot()]
    }
  }

  function removeSlot(code: string, entryKey: string, slotKey: string) {
    const entry = find(code)?.entries.find((item) => item.key === entryKey)
    if (entry) {
      entry.slots = (entry.slots ?? []).filter((slot) => slot.key !== slotKey)
    }
  }

  function updateSlot(
    code: string,
    entryKey: string,
    slotKey: string,
    field: 'dayOfWeek' | 'periodNo',
    value: unknown,
  ) {
    const slot = find(code)
      ?.entries.find((item) => item.key === entryKey)
      ?.slots?.find((item) => item.key === slotKey)
    if (slot) {
      slot[field] = value === null || value === undefined ? null : Number(value)
    }
  }

  function warnings(): string[] {
    return visibleRows.value.flatMap((row) =>
      isThresholdType(row) ? thresholdWarnings(row) : missingParamWarnings([row]),
    )
  }

  /**
   * Conditions the server would refuse, checked before the request: contradictory
   * scalars for one course and duplicate threshold scopes. `objectNames` labels the
   * threshold exception rows (teacher/class/course names).
   */
  function conflicts(
    courseNames?: Map<number, string>,
    objectNames?: Map<number, string>,
  ): string[] {
    return [
      ...scalarConflicts(rows.value, {
        teacherNames: new Map(),
        courseNames: courseNames ?? new Map(),
      }),
      ...thresholdConflictsOf(objectNames ?? courseNames),
    ]
  }

  // ----------------------------------------------------------- thresholds (ticket 04)

  function thresholdOf(code: string): ThresholdDraft | undefined {
    const row = find(code)
    if (!row) {
      return undefined
    }
    if (!row.threshold) {
      row.threshold = emptyThreshold()
    }
    return row.threshold
  }

  /** The global default. `null` removes it, which leaves the unlisted objects unlimited. */
  function setThresholdGlobal(code: string, value: number | null) {
    const draft = thresholdOf(code)
    if (!draft) {
      return
    }
    draft.globalValue = value === null || Number.isNaN(value) ? null : Number(value)
    if (draft.globalValue === null) {
      draft.globalId = null
    }
  }

  function addThresholdException(code: string) {
    thresholdOf(code)?.exceptions.push({
      key: nextKey('exception'),
      id: null,
      objectId: null,
      value: null,
    })
  }

  function removeThresholdException(code: string, key: string) {
    const draft = thresholdOf(code)
    if (draft) {
      draft.exceptions = draft.exceptions.filter((exception) => exception.key !== key)
    }
  }

  function setThresholdExceptionObject(code: string, key: string, objectId: unknown) {
    const exception = thresholdOf(code)?.exceptions.find((item) => item.key === key)
    if (exception) {
      exception.objectId = hasValue(objectId) ? Number(objectId) : null
    }
  }

  function setThresholdExceptionValue(code: string, key: string, value: unknown) {
    const exception = thresholdOf(code)?.exceptions.find((item) => item.key === key)
    if (exception) {
      exception.value =
        value === null || value === undefined || value === '' ? null : Number(value)
    }
  }

  /** Duplicate scopes (two global defaults, two rows for one object) the server refuses. */
  function thresholdConflictsOf(objectNames?: Map<number, string>): string[] {
    return rows.value.flatMap((row) =>
      isThresholdType(row)
        ? thresholdConflicts(row, objectNames ?? new Map()).map((conflict) => conflict.message)
        : [],
    )
  }

  return {
    rows,
    selectedCode,
    visibleRows,
    counts,
    groups,
    selected,
    dirty,
    hydrate,
    markSaved,
    select,
    setEnabled,
    setHard,
    setWeight,
    addEntry,
    removeEntry,
    updateEntryValue,
    setAllTarget,
    addSlot,
    removeSlot,
    updateSlot,
    setThresholdGlobal,
    addThresholdException,
    removeThresholdException,
    setThresholdExceptionObject,
    setThresholdExceptionValue,
    thresholdConflictsOf,
    warnings,
    conflicts,
    payload,
  }
}
