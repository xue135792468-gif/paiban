import { computed, ref } from 'vue'
import { conflictReport, type ConflictReport } from './conflictModel'
import { buildSavePayload, countsOf, groupRows } from './draftRules'
import { missingParamWarnings } from './entryChecks'
import {
  headParams,
  isEntryType,
  isSlotSetType,
  isThresholdType,
  optionalTargetParam,
} from './entryModel'
import { emptyObjectNames, emptyThreshold, thresholdRelaxations, thresholdWarnings } from './thresholdModel'
import {
  controlsOf as proposalControls,
  findSameConditions,
  proposalThreshold,
  scalarConflict,
  sameControls,
  thresholdRelaxation,
} from './proposalApply'
import type { ConstraintProposal } from './useConstraintAi'
import { hasValue } from './paramValues'
import type {
  ConstraintConfigDto,
  ConstraintConfigRequest,
  ConstraintCounts,
  ConstraintTypeDef,
  EntryDraft,
  NavGroup,
  ObjectNames,
  SlotDraft,
  ThresholdDraft,
  TypeDraft,
} from './types'

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

/** The card-level values of a slot-set row (everything that is not day/period). */
function headValues(type: { params: ConstraintTypeDef['params'] }, params: Record<string, unknown>) {
  const heads: Record<string, unknown> = {}
  headParams(type).forEach((param) => {
    if (hasValue(params[param.name])) {
      heads[param.name] = params[param.name]
    }
  })
  return heads
}

function controlsOf(entry: {
  enabled?: boolean
  hard?: boolean | null
  weight?: number | null
  groupKey?: string | null
}) {
  return {
    enabled: entry.enabled ?? true,
    hard: entry.hard ?? null,
    weight: entry.weight ?? null,
    groupKey: entry.groupKey?.trim() || null,
  }
}

/**
 * The saved entries of one type as editable cards. Entry-backed types only: an empty
 * list means "not configured" and is never turned into an implicit rule, so the old
 * `params` value is deliberately ignored for them.
 *
 * Slot-grouped types group their rows by `groupKey`: every row of one requirement
 * becomes one card with several slots. A legacy row without a group key is its own
 * card and receives a fresh key on the next save, which preserves its meaning (it was
 * an individual requirement) without mixing it with a same-teacher requirement that
 * carries different controls.
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
    savedEntries.forEach((entry, index) => {
      const params = valuesFrom(entry.params)
      const controls = controlsOf(entry)
      // A saved requirement carries its group key. A legacy row does not: the
      // preference type has always meant "these slots are one optional set per
      // teacher", so same-teacher rows with the same controls rejoin one card; the
      // prohibition types keep every legacy row as its own requirement, which is
      // exactly what it meant before groups existed.
      const legacyGroup = type.code === 'TEACHER_PREFERRED_SLOT'
        ? `legacy-teacher-${params.teacherId ?? index}|${controls.hard}|${controls.weight}|${controls.enabled}`
        : `legacy-${index}`
      const group = controls.groupKey || legacyGroup
      let card = cards.get(group)
      if (!card) {
        card = {
          key: nextKey('entry'),
          id: null,
          values: headValues(type, params),
          slots: [],
          groupKey: controls.groupKey ?? nextKey('group'),
          enabled: controls.enabled,
          hard: controls.hard,
          weight: controls.weight,
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
    ...controlsOf(entry),
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
    slotGrouped: type.slotGrouped ?? false,
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
    enabled: entry.enabled ?? true,
    hard: entry.hard ?? null,
    weight: entry.weight ?? null,
  }))
  if (sources.length === 0 && saved?.params) {
    sources.push({ id: null, params: valuesFrom(saved.params), enabled: true, hard: null, weight: null })
  }
  sources.forEach((source) => {
    const objectId = hasValue(source.params[objectField]) ? Number(source.params[objectField]) : null
    const value = hasValue(source.params[valueField]) ? Number(source.params[valueField]) : null
    if (objectId === null) {
      // The server allows one global default; read the first and ignore a duplicate.
      if (draft.globalValue === null) {
        draft.globalId = source.id
        draft.globalValue = value
        draft.globalEnabled = source.enabled
        draft.globalHard = source.hard
        draft.globalWeight = source.weight
      }
      return
    }
    draft.exceptions.push({
      key: nextKey('exception'),
      id: source.id,
      objectId,
      value,
      enabled: source.enabled,
      hard: source.hard,
      weight: source.weight,
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
  /** The catalogue the page hydrated from; used to rebuild rows after a conflict. */
  const catalogueTypes = ref<ConstraintTypeDef[]>([])
  /** The configuration version the current draft is based on. */
  const baseVersion = ref(0)
  /** The payload the page loaded with, so a conflict can tell "my edit" from "theirs". */
  const basePayload = ref<ConstraintConfigRequest[]>([])

  const visibleRows = computed(() => rows.value)

  // Counts cover every catalogue type; hiding one for a school mode is the caller's
  // concern and must not shrink the denominator or drop configured entries.
  const counts = computed<ConstraintCounts>(() => countsOf(rows.value))

  const groups = computed<NavGroup[]>(() => groupRows(visibleRows.value))

  const selected = computed<TypeDraft | null>(
    () => visibleRows.value.find((row) => row.code === selectedCode.value) ?? null,
  )

  function payload(): ConstraintConfigRequest[] {
    return buildSavePayload(rows.value)
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
    catalogueTypes.value = types
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
    basePayload.value = payload()
    snapshot.value = JSON.stringify(basePayload.value)
  }

  function setBaseVersion(version: number) {
    baseVersion.value = version
  }

  /** Marks the current rows as "dirty relative to this payload" (used after a restore). */
  function setSnapshot(json: string) {
    snapshot.value = json
  }

  /**
   * Rebuilds the editor from a merged configuration after a conflict, keeping the
   * catalogue and the selected type. The result is unsaved until the caller succeeds.
   */
  function rehydrate(configs: ConstraintConfigDto[]) {
    if (catalogueTypes.value.length === 0) {
      return
    }
    hydrate(catalogueTypes.value, configs, { isJunior: isJuniorMode.value })
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
    // A slot-grouped card starts with one empty slot; without a slot it has nothing
    // to store. Every other type stores the card itself, and a type whose object may
    // be blank starts on the explicit "every …" target (a ticked box, never an
    // empty dropdown).
    if (isSlotSetType(row)) {
      row.entries.push({
        key: nextKey('entry'),
        id: null,
        values: {},
        slots: [emptySlot()],
        groupKey: nextKey('group'),
        enabled: true,
        hard: null,
        weight: null,
      })
      return
    }
    const target = isSlotSetType(row) ? null : optionalTargetParam(row.params)
    row.entries.push({
      key: nextKey('entry'),
      id: null,
      values: {},
      allTarget: target ? [target.name] : undefined,
      groupKey: null,
      enabled: true,
      hard: null,
      weight: null,
    })
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
    if (!row || !entry) {
      return
    }
    // A slot-grouped card stores weekday/period per slot; routing the generic setter
    // to the first slot keeps callers that do not care about slot rows working.
    if (isSlotSetType(row) && (name === 'dayOfWeek' || name === 'periodNo')) {
      if (!entry.slots || entry.slots.length === 0) {
        entry.slots = [emptySlot()]
      }
      entry.slots[0][name] = value === null || value === undefined ? null : Number(value)
      return
    }
    entry.values[name] = value
    // Picking an object cancels an earlier "every …" choice for that parameter.
    if (hasValue(value) && entry.allTarget?.includes(name)) {
      entry.allTarget = entry.allTarget.filter((item) => item !== name)
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

  // ------------------------------------------------- requirement controls (14/01)

  function entryOf(code: string, key: string): EntryDraft | undefined {
    return find(code)?.entries.find((item) => item.key === key)
  }

  function setEntryEnabled(code: string, key: string, value: boolean) {
    const entry = entryOf(code, key)
    if (entry) {
      entry.enabled = value
    }
  }

  /** `null` restores the type default; `true`/`false` overrides it. */
  function setEntryHard(code: string, key: string, value: boolean | null) {
    const entry = entryOf(code, key)
    if (entry) {
      entry.hard = value
    }
  }

  /** `null` restores the type default weight. */
  function setEntryWeight(code: string, key: string, value: number | null) {
    const entry = entryOf(code, key)
    if (entry) {
      entry.weight = value === null || Number.isNaN(value) ? null : Number(value)
    }
  }

  function addSlot(code: string, entryKey: string) {
    const entry = entryOf(code, entryKey)
    if (entry) {
      entry.slots = [...(entry.slots ?? []), emptySlot()]
    }
  }

  /**
   * Expands one slot into the same period on every teaching day of the semester. The
   * expansion is explicit and immediate (ticket-14 spec: 每个上课日按确认时展开), so a
   * later timetable change never widens a saved requirement.
   */
  function fillAllDays(code: string, entryKey: string, slotKey: string, days: number[]) {
    const entry = entryOf(code, entryKey)
    const slot = entry?.slots?.find((item) => item.key === slotKey)
    if (!entry || !slot || slot.periodNo === null || days.length === 0) {
      return
    }
    const others = (entry.slots ?? []).filter((item) => item.key !== slotKey)
    const existing = new Set(others.map((item) => `${item.dayOfWeek}-${item.periodNo}`))
    const expanded = days
      .filter((day) => !existing.has(`${day}-${slot.periodNo}`))
      .map((day) => ({
        key: nextKey('slot'),
        id: null,
        dayOfWeek: day,
        periodNo: slot.periodNo,
      }))
    entry.slots = [...others, ...expanded].sort(
      (left, right) => Number(left.dayOfWeek ?? 0) - Number(right.dayOfWeek ?? 0),
    )
  }

  function removeSlot(code: string, entryKey: string, slotKey: string) {
    const entry = entryOf(code, entryKey)
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
    const slot = entryOf(code, entryKey)?.slots?.find((item) => item.key === slotKey)
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
   * Conditions the server would refuse, checked before the request: identical entries,
   * contradictory scalars for one object and duplicate threshold scopes. Computed once
   * per change and read twice — the pre-check bar shows `messages`, the left navigation
   * turns `codes` red — so neither reader can disagree with the other.
   */
  function conflictsOf(names: ObjectNames = emptyObjectNames()): ConflictReport {
    return conflictReport(rows.value, names)
  }

  /** Per-object exceptions that loosen the global limit, in user-facing sentences. */
  function relaxations(names: ObjectNames = emptyObjectNames()): string[] {
    return rows.value.flatMap((row) =>
      isThresholdType(row) ? thresholdRelaxations(row, names) : [],
    )
  }

  // --------------------------------------------------- proposal confirmation (14/10)

  /** A deep copy of the rows, so a rejected merge can roll back exactly. */
  function snapshotRows(): TypeDraft[] {
    return JSON.parse(JSON.stringify(rows.value)) as TypeDraft[]
  }

  function restoreRows(snapshot: TypeDraft[]) {
    rows.value = snapshot
  }

  type ApplyAction = 'ADDED' | 'EXISTS' | 'REPLACED' | 'CONFLICT' | 'RELAXES' | 'SKIPPED'

  interface ApplyOutcome {
    action: ApplyAction
    message: string
  }

  /**
   * Adds one confirmed proposal to the borrowed draft (ticket 14/10). Nothing is
   * saved here: duplicates are reported, control changes and scalar conflicts keep
   * the old value unless `replace` is explicit, and a looser threshold exception
   * needs `confirmRelaxation`.
   */
  function applyProposal(
    code: string,
    proposal: ConstraintProposal,
    options: { replace?: boolean; confirmRelaxation?: boolean; objectName?: string } = {},
  ): ApplyOutcome {
    const row = find(code)
    if (!row) {
      return { action: 'SKIPPED', message: `未知约束类型 ${code}` }
    }
    if (proposal.status !== 'READY' || proposal.entries.length === 0) {
      return { action: 'SKIPPED', message: '该建议还不能加入约束表' }
    }
    const controls = proposalControls(proposal)
    if (isThresholdType(row)) {
      return applyThresholdProposal(row, proposal, controls, options)
    }

    const same = findSameConditions(row, proposal)
    if (same) {
      if (sameControls(same.card, row, proposal)) {
        return { action: 'EXISTS', message: `「${row.name}」已存在相同要求，未重复加入。` }
      }
      if (!options.replace) {
        return {
          action: 'CONFLICT',
          message: `「${row.name}」已有相同条件但强度不同；默认保留原设置，确认后才替换。`,
        }
      }
      same.card.hard = controls.hard
      same.card.weight = controls.weight
      same.card.enabled = controls.enabled
      return { action: 'REPLACED', message: `已替换「${row.name}」的控制设置。` }
    }

    const conflict = scalarConflict(row, proposal)
    if (conflict && !options.replace) {
      return { action: 'CONFLICT', message: conflict.message }
    }
    if (conflict) {
      row.entries = row.entries.filter((entry) => entry.key !== conflict.card.key)
    }

    const entry = cardFromProposal(row, proposal, controls)
    row.entries.push(entry)
    return {
      action: conflict ? 'REPLACED' : 'ADDED',
      message: conflict
        ? `已用新建议替换「${row.name}」的旧参数。`
        : `已加入「${row.name}」：${proposal.entries.length} 个课节。`,
    }
  }

  function cardFromProposal(
    row: TypeDraft,
    proposal: ConstraintProposal,
    controls: { hard: boolean | null; weight: number | null; enabled: boolean },
  ): EntryDraft {
    if (isSlotSetType(row)) {
      const first = proposal.entries[0].params
      const head: Record<string, unknown> = {}
      Object.keys(first).forEach((key) => {
        if (key !== 'dayOfWeek' && key !== 'periodNo') {
          head[key] = first[key]
        }
      })
      return {
        key: nextKey('entry'),
        id: null,
        values: head,
        slots: proposal.entries.map((entry) => ({
          key: nextKey('slot'),
          id: null,
          dayOfWeek: (entry.params.dayOfWeek as number | undefined) ?? null,
          periodNo: (entry.params.periodNo as number | undefined) ?? null,
        })),
        groupKey: `ai-${proposal.proposalId}-${nextKey('g')}`,
        ...controls,
      }
    }
    return {
      key: nextKey('entry'),
      id: null,
      values: { ...proposal.entries[0].params },
      groupKey: null,
      ...controls,
    }
  }

  function applyThresholdProposal(
    row: TypeDraft,
    proposal: ConstraintProposal,
    controls: { hard: boolean | null; weight: number | null; enabled: boolean },
    options: { replace?: boolean; confirmRelaxation?: boolean; objectName?: string },
  ): ApplyOutcome {
    const scope = proposalThreshold(row, proposal)
    if (!scope || scope.value === null) {
      return { action: 'CONFLICT', message: `「${row.name}」建议缺少数值，不能加入。` }
    }
    const relaxation = thresholdRelaxation(
      row, proposal, options.objectName ?? `#${scope.objectId ?? '全局'}`)
    if (relaxation && !options.confirmRelaxation) {
      return { action: 'RELAXES', message: relaxation }
    }
    const draft = thresholdOf(row.code) ?? emptyThreshold()
    if (scope.objectId === null) {
      if (draft.globalValue !== null) {
        const sameControl = (draft.globalHard ?? row.hard) === (controls.hard ?? row.hard)
        if (Number(draft.globalValue) === scope.value && sameControl) {
          return { action: 'EXISTS', message: `「${row.name}」已有相同全局默认。` }
        }
        if (!options.replace) {
          return {
            action: 'CONFLICT',
            message: `「${row.name}」全局默认已是 ${draft.globalValue}；默认保留旧值，确认后才替换。`,
          }
        }
        draft.globalValue = scope.value
        draft.globalEnabled = controls.enabled
        draft.globalHard = controls.hard
        draft.globalWeight = controls.weight
        return { action: 'REPLACED', message: `已替换「${row.name}」的全局默认。` }
      }
      draft.globalValue = scope.value
      draft.globalEnabled = controls.enabled
      draft.globalHard = controls.hard
      draft.globalWeight = controls.weight
      return { action: 'ADDED', message: `已加入「${row.name}」全局默认。` }
    }
    const existing = draft.exceptions.find((exception) => exception.objectId === scope.objectId)
    if (existing) {
      const sameControl = (existing.hard ?? row.hard) === (controls.hard ?? row.hard)
      if (Number(existing.value) === scope.value && sameControl) {
        return { action: 'EXISTS', message: `「${row.name}」该对象已有相同例外。` }
      }
      if (!options.replace) {
        return {
          action: 'CONFLICT',
          message: `「${row.name}」该对象已有一条例外；默认保留旧值，确认后才替换。`,
        }
      }
      existing.value = scope.value
      existing.enabled = controls.enabled
      existing.hard = controls.hard
      existing.weight = controls.weight
      return { action: 'REPLACED', message: `已替换「${row.name}」的对象例外。` }
    }
    draft.exceptions.push({
      key: nextKey('exception'),
      id: null,
      objectId: scope.objectId,
      value: scope.value,
      enabled: controls.enabled,
      hard: controls.hard,
      weight: controls.weight,
    })
    return { action: 'ADDED', message: `已加入「${row.name}」对象例外。` }
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
      enabled: true,
      hard: null,
      weight: null,
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

  // ------------------------------------------------ threshold scope controls (14/05)

  function setThresholdGlobalEnabled(code: string, value: boolean) {
    const draft = thresholdOf(code)
    if (draft) {
      draft.globalEnabled = value
    }
  }

  function setThresholdGlobalHard(code: string, value: boolean | null) {
    const draft = thresholdOf(code)
    if (draft) {
      draft.globalHard = value
    }
  }

  function setThresholdGlobalWeight(code: string, value: number | null) {
    const draft = thresholdOf(code)
    if (draft) {
      draft.globalWeight = value === null || Number.isNaN(value) ? null : Number(value)
    }
  }

  function setThresholdExceptionEnabled(code: string, key: string, value: boolean) {
    const exception = thresholdOf(code)?.exceptions.find((item) => item.key === key)
    if (exception) {
      exception.enabled = value
    }
  }

  function setThresholdExceptionHard(code: string, key: string, value: boolean | null) {
    const exception = thresholdOf(code)?.exceptions.find((item) => item.key === key)
    if (exception) {
      exception.hard = value
    }
  }

  function setThresholdExceptionWeight(code: string, key: string, value: number | null) {
    const exception = thresholdOf(code)?.exceptions.find((item) => item.key === key)
    if (exception) {
      exception.weight = value === null || Number.isNaN(value) ? null : Number(value)
    }
  }

  return {
    rows,
    selectedCode,
    baseVersion,
    basePayload,
    visibleRows,
    counts,
    groups,
    selected,
    dirty,
    hydrate,
    rehydrate,
    setBaseVersion,
    setSnapshot,
    snapshotRows,
    restoreRows,
    applyProposal,
    markSaved,
    select,
    setEnabled,
    setHard,
    setWeight,
    addEntry,
    removeEntry,
    updateEntryValue,
    setAllTarget,
    setEntryEnabled,
    setEntryHard,
    setEntryWeight,
    addSlot,
    fillAllDays,
    removeSlot,
    updateSlot,
    setThresholdGlobal,
    addThresholdException,
    removeThresholdException,
    setThresholdExceptionObject,
    setThresholdExceptionValue,
    setThresholdGlobalEnabled,
    setThresholdGlobalHard,
    setThresholdGlobalWeight,
    setThresholdExceptionEnabled,
    setThresholdExceptionHard,
    setThresholdExceptionWeight,
    warnings,
    conflictsOf,
    relaxations,
    payload,
  }
}
