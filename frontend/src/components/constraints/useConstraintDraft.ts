import { computed, ref } from 'vue'
import { buildSavePayload, countsOf, groupRows, isMultiEntryType, missingParamWarnings } from './draftRules'
import type {
  ConstraintConfigDto,
  ConstraintConfigRequest,
  ConstraintCounts,
  ConstraintTypeDef,
  EntryDraft,
  NavGroup,
  TypeDraft,
} from './types'

/** ADR-0018: junior mode hides senior-only constraints from the editor. */
const HIDDEN_IN_JUNIOR = ['TEACHER_MEETING_BLOCKED']

let entryKeySeed = 0

function nextEntryKey(): string {
  entryKeySeed += 1
  return `entry-${entryKeySeed}`
}

function valuesFrom(params: unknown): Record<string, unknown> {
  return params && typeof params === 'object' ? { ...(params as Record<string, unknown>) } : {}
}

function entriesOf(type: ConstraintTypeDef, saved: ConstraintConfigDto | undefined): EntryDraft[] {
  const savedEntries = saved?.entries ?? []
  if (savedEntries.length > 0) {
    return savedEntries.map((entry) => ({
      key: nextEntryKey(),
      id: entry.id ?? null,
      values: valuesFrom(entry.params),
    }))
  }
  // Legacy single `params` is the first (and only) entry.
  const legacy = valuesFrom(saved?.params)
  const filled = type.params.filter((param) => {
    const value = legacy[param.name]
    return value !== null && value !== undefined && value !== ''
  })
  return filled.length > 0
    ? [{ key: nextEntryKey(), id: null, values: legacy }]
    : []
}

function buildRow(type: ConstraintTypeDef, saved: ConstraintConfigDto | undefined): TypeDraft {
  const values: Record<string, unknown> = {}
  if (!isMultiEntryType(type)) {
    type.params.forEach((param) => {
      const savedValue = saved?.params?.[param.name]
      values[param.name] = savedValue !== undefined ? savedValue : (param.defaultValue ?? null)
    })
  }
  const savedValues = saved?.params ?? {}
  return {
    code: type.code,
    name: type.name,
    description: type.description,
    category: type.category,
    categoryLabel: type.categoryLabel,
    params: type.params,
    values,
    entries: entriesOf(type, saved),
    enabled: saved?.enabled ?? false,
    hard: saved?.hard ?? type.defaultHard,
    weight: saved?.weight ?? type.defaultWeight,
    // Catalogue defaults alone are not a configured condition.
    touched: Object.keys(valuesFrom(savedValues)).length > 0,
  }
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
      // Default to the type this ticket expands, so the main view opens on entries.
      selectedCode.value =
        visibleRows.value.find((row) => isMultiEntryType(row))?.code ??
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

  function updateValue(code: string, name: string, value: unknown) {
    const row = find(code)
    if (row) {
      row.values[name] = value
      row.touched = true
    }
  }

  function addEntry(code: string) {
    const row = find(code)
    if (row) {
      row.entries.push({ key: nextEntryKey(), id: null, values: {} })
    }
  }

  function removeEntry(code: string, key: string) {
    const row = find(code)
    if (row) {
      row.entries = row.entries.filter((entry) => entry.key !== key)
    }
  }

  function updateEntryValue(code: string, key: string, name: string, value: unknown) {
    const row = find(code)
    const entry = row?.entries.find((item) => item.key === key)
    if (entry) {
      entry.values[name] = value
    }
  }

  function warnings(): string[] {
    return missingParamWarnings(visibleRows.value)
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
    updateValue,
    addEntry,
    removeEntry,
    updateEntryValue,
    warnings,
    payload,
  }
}
