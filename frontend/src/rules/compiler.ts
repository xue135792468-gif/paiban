/**
 * The sentence builder's compiler: it turns "全校 / 王老师 的 课 在 周五下午 不排"
 * into the constraint rows the rest of the system already evaluates. There is no
 * second evaluator here — the output is exactly `constraint_config` /
 * `constraint_entry`, so the solver, draft validation and substitution all keep
 * reading the same `ConstraintChecker` snapshot.
 *
 * Everything in this file is pure: the page owns HTTP, this module owns the shape.
 */
import type {
  ConstraintConfigDto,
  ConstraintConfigRequest,
  ConstraintEntryDto,
  ConstraintTypeDef,
  SaveEntry,
} from '@/components/constraints/types'

export type RuleType = 'CLASS_UNAVAILABLE' | 'TEACHER_UNAVAILABLE'
export type RuleStrength = 'HARD' | 'SOFT'

export const CLASS_UNAVAILABLE: RuleType = 'CLASS_UNAVAILABLE'
export const TEACHER_UNAVAILABLE: RuleType = 'TEACHER_UNAVAILABLE'

/** The only two types the sentence builder manages in this slice. */
export const RULE_TYPES: RuleType[] = [CLASS_UNAVAILABLE, TEACHER_UNAVAILABLE]
const MANAGED_TYPES = new Set<string>(RULE_TYPES)

/** Mirror of `ConstraintType.ENTRY_BACKED`, used only when a caller omits the catalogue. */
const KNOWN_ENTRY_BACKED = new Set<string>([
  'CLASS_UNAVAILABLE',
  'TEACHER_UNAVAILABLE',
  'TEACHER_MEETING_BLOCKED',
  'COURSE_REQUIRED_ROOM_TYPE',
  'COURSE_NOT_IN_EVENING',
  'COURSE_UNAVAILABLE',
  'SAME_COURSE_ONCE_PER_DAY',
  'SPORT_NOT_FIRST_AFTERNOON',
  'CONSECUTIVE_LESSONS',
  'COURSE_SPREAD',
  'MAIN_SUBJECT_MORNING',
  'TEACHER_PREFERRED_SLOT',
  'TEACHER_NO_FIRST_PERIOD',
  'TEACHER_DAY_OFF',
  'PE_LAST_PERIOD_AVOID',
  'TEACHER_MAX_PER_DAY',
  'CLASS_MAX_PER_DAY',
  'COURSE_MAX_PER_DAY',
  'EVENING_MAX',
  'TEACHER_MAX_CONSECUTIVE',
])

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** One `period` row as the rule form needs it. */
export interface PeriodOption {
  dayOfWeek: number
  periodNo: number
  section?: string | null
}

/** The four slots of one sentence, without the card's storage identity. */
export interface RuleInput {
  type: RuleType
  teacherId: number | null
  dayOfWeek: number
  periodNos: number[]
  strength: RuleStrength
}

/**
 * One editable rule card. `key` is the page's stable identity while editing;
 * `groupKey` is the persisted requirement identity (null for a legacy row that
 * never had one). `entryIds` lets an edit keep the row ids it already owns.
 */
export interface RuleCard extends RuleInput {
  key: string
  groupKey: string | null
  enabled: boolean
  entryIds: Record<string, number>
}

export interface MergeOptions {
  /**
   * Type codes whose configuration is stored in `entries`. The page passes the
   * catalogue's `entryBacked` flags so other rules survive a save unchanged; when
   * omitted, the shape of the saved config is used instead.
   */
  entryBackedTypes?: ReadonlySet<string>
}

export function isManagedType(type: string): type is RuleType {
  return MANAGED_TYPES.has(type)
}

export function weekdayLabel(dayOfWeek: number): string {
  return WEEKDAYS[dayOfWeek] ?? `星期${dayOfWeek}`
}

export function normalizePeriods(periodNos: number[]): number[] {
  return Array.from(new Set(periodNos)).sort((left, right) => left - right)
}

export function slotKey(dayOfWeek: number, periodNo: number): string {
  return `${dayOfWeek}-${periodNo}`
}

/** The afternoon periods of one weekday, read from the semester timetable. */
export function afternoonPeriods(periods: PeriodOption[], dayOfWeek: number): number[] {
  return normalizePeriods(
    periods
      .filter((period) => period.dayOfWeek === dayOfWeek && period.section === 'AFTERNOON')
      .map((period) => period.periodNo),
  )
}

/** "周五下午" when the selection is exactly the afternoon, otherwise "周五第 7 节". */
export function timeLabel(
  dayOfWeek: number,
  periodNos: number[],
  periods: PeriodOption[],
): string {
  const label = weekdayLabel(dayOfWeek)
  const sorted = normalizePeriods(periodNos)
  if (sorted.length === 0) {
    return `${label}（还没选节次）`
  }
  const afternoon = afternoonPeriods(periods, dayOfWeek)
  if (afternoon.length > 0 && sameNumbers(sorted, afternoon)) {
    return `${label}下午`
  }
  return `${label}第 ${sorted.join('、')} 节`
}

function sameNumbers(left: number[], right: number[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

/** The sentence the card shows: "王老师 的 课 在 周五下午 不排". */
export function describeRule(
  parts: Pick<RuleInput, 'type' | 'teacherId' | 'dayOfWeek' | 'periodNos'>,
  teacherNameOf: (id: number) => string,
  periods: PeriodOption[],
): string {
  const scope =
    parts.type === CLASS_UNAVAILABLE
      ? '全校'
      : parts.teacherId === null
        ? '教师'
        : teacherNameOf(parts.teacherId)
  return `${scope} 的 课 在 ${timeLabel(parts.dayOfWeek, parts.periodNos, periods)} 不排`
}

export function describeCard(
  card: RuleCard,
  teacherNameOf: (id: number) => string,
  periods: PeriodOption[],
): string {
  return describeRule(card, teacherNameOf, periods)
}

export function newGroupKey(): string {
  const uuid =
    globalThis.crypto?.randomUUID?.() ??
    `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  return `pt-${uuid}`
}

export function newCardKey(): string {
  return `new:${newGroupKey()}`
}

export function cardFromInput(input: RuleInput): RuleCard {
  return {
    ...input,
    periodNos: normalizePeriods(input.periodNos),
    strength: input.type === CLASS_UNAVAILABLE ? 'HARD' : input.strength,
    key: newCardKey(),
    groupKey: newGroupKey(),
    enabled: true,
    entryIds: {},
  }
}

/**
 * One sentence compiled on its own. Used by tests and by callers that only need
 * the payload for a fresh rule; the page saves through `mergeRuleConfigs`.
 */
export function compileRule(input: RuleInput): ConstraintConfigRequest {
  if (normalizePeriods(input.periodNos).length === 0) {
    throw new Error('规则至少要选择一个节次')
  }
  const card = cardFromInput(input)
  const config = configsFromCards([], [card], { entryBackedTypes: new Set() }).find(
    (item) => item.type === card.type,
  )
  if (!config) {
    throw new Error('规则没有生成约束配置')
  }
  return config
}

/** The save request for the whole semester: other types are carried over untouched. */
export function configsFromCards(
  existing: ConstraintConfigDto[],
  cards: RuleCard[],
  options: MergeOptions = {},
): ConstraintConfigRequest[] {
  const existingByType = new Map<string, ConstraintConfigDto>()
  existing.forEach((config) => existingByType.set(config.type, config))

  // Only groups this page can render are rebuilt from cards. A group it cannot
  // represent (multiple weekdays, a class-specific no-class row, …) is passed
  // through exactly as stored, so opening the builder never rewrites it.

  const result: ConstraintConfigRequest[] = []
  for (const config of existing) {
    const type = config.type
    if (!isManagedType(type)) {
      result.push(preserve(config, options))
      continue
    }
    const cardsForType = cards.filter((card) => card.type === type)
    const cardByIdentity = new Map(cardsForType.map((card) => [card.key, card]))
    const representable = new Set(
      groupEntries(config)
        .filter((group) => cardFromGroup(type, group, config))
        .map((group) => group.identity),
    )
    const emitted = new Set<string>()
    const entries: SaveEntry[] = []
    ;(config.entries ?? []).forEach((entry, index) => {
      const identity = identityOfEntry(entry, index)
      if (!representable.has(identity)) {
        entries.push(toSaveEntry(entry))
        return
      }
      if (emitted.has(identity)) {
        return
      }
      emitted.add(identity)
      const card = cardByIdentity.get(identity)
      if (card) {
        entries.push(...cardEntries(card))
      }
    })
    for (const card of cardsForType) {
      if (!emitted.has(card.key)) {
        entries.push(...cardEntries(card))
      }
    }
    if (entries.length === 0) {
      continue
    }
    result.push({
      type: config.type,
      entries,
      weight: config.weight,
      hard: true,
      enabled: config.enabled,
    })
  }

  for (const type of RULE_TYPES) {
    if (existingByType.has(type)) {
      continue
    }
    const entries = cards.filter((card) => card.type === type).flatMap(cardEntries)
    if (entries.length === 0) {
      continue
    }
    result.push({ type, entries, weight: 100, hard: true, enabled: true })
  }
  return result
}

interface EntryGroup {
  identity: string
  groupKey: string | null
  entries: ConstraintEntryDto[]
}

function groupEntries(config: ConstraintConfigDto): EntryGroup[] {
  const groups = new Map<string, EntryGroup>()
  ;(config.entries ?? []).forEach((entry, index) => {
    const identity = identityOfEntry(entry, index)
    const groupKey = entry.groupKey?.trim() || null
    const group = groups.get(identity)
    if (group) {
      group.entries.push(entry)
      return
    }
    groups.set(identity, { identity, groupKey, entries: [entry] })
  })
  return Array.from(groups.values())
}

function identityOfEntry(entry: ConstraintEntryDto, index: number): string {
  const groupKey = entry.groupKey?.trim() || null
  return groupKey ? `group:${groupKey}` : `entry:${entry.id ?? index}`
}

/**
 * The sentence model covers exactly one weekday, one scope and a set of periods.
 * A stored group that says anything else is not a card; it stays in the advanced
 * page and is preserved on save.
 */
function cardFromGroup(
  type: RuleType,
  group: EntryGroup,
  config: ConstraintConfigDto,
): RuleCard | null {
  const days = new Set<number>()
  const periods = new Set<number>()
  const teachers = new Set<number>()
  for (const entry of group.entries) {
    const params = entry.params ?? {}
    const day = toInteger(params.dayOfWeek)
    const periodNo = toInteger(params.periodNo)
    if (day === null || periodNo === null) {
      return null
    }
    days.add(day)
    periods.add(periodNo)
    if (type === TEACHER_UNAVAILABLE) {
      const teacherId = toInteger(params.teacherId)
      if (teacherId === null) {
        return null
      }
      teachers.add(teacherId)
    } else if (params.classId !== undefined && params.classId !== null) {
      return null
    }
  }
  if (days.size !== 1) {
    return null
  }
  if (type === TEACHER_UNAVAILABLE && teachers.size !== 1) {
    return null
  }
  const hard = group.entries.some(
    (entry) => entry.hard ?? config.hard ?? true,
  )
  const entryIds: Record<string, number> = {}
  group.entries.forEach((entry) => {
    if (entry.id === null) {
      return
    }
    const day = toInteger(entry.params?.dayOfWeek)
    const periodNo = toInteger(entry.params?.periodNo)
    if (day !== null && periodNo !== null) {
      entryIds[slotKey(day, periodNo)] = entry.id
    }
  })
  return {
    key: group.identity,
    groupKey: group.groupKey,
    type,
    teacherId: type === TEACHER_UNAVAILABLE ? Array.from(teachers)[0] : null,
    dayOfWeek: Array.from(days)[0],
    periodNos: normalizePeriods(Array.from(periods)),
    strength: type === CLASS_UNAVAILABLE || hard ? 'HARD' : 'SOFT',
    enabled: group.entries.every((entry) => entry.enabled !== false),
    entryIds,
  }
}

/** Cards the builder can render, read back from `constraint_state`. */
export function cardsFromState(configs: ConstraintConfigDto[]): RuleCard[] {
  const cards: RuleCard[] = []
  for (const config of configs) {
    if (!isManagedType(config.type)) {
      continue
    }
    for (const group of groupEntries(config)) {
      const card = cardFromGroup(config.type, group, config)
      if (card) {
        cards.push(card)
      }
    }
  }
  return cards
}

function cardEntries(card: RuleCard): SaveEntry[] {
  const hard = card.type === CLASS_UNAVAILABLE || card.strength === 'HARD'
  return normalizePeriods(card.periodNos).map((periodNo) => {
    const params: Record<string, unknown> =
      card.type === CLASS_UNAVAILABLE
        ? { dayOfWeek: card.dayOfWeek, periodNo }
        : { teacherId: card.teacherId, dayOfWeek: card.dayOfWeek, periodNo }
    return {
      id: card.entryIds[slotKey(card.dayOfWeek, periodNo)] ?? null,
      params,
      enabled: card.enabled,
      hard,
      weight: null,
      groupKey: card.groupKey,
    }
  })
}

function toSaveEntry(entry: ConstraintEntryDto): SaveEntry {
  return {
    id: entry.id,
    params: { ...(entry.params ?? {}) },
    enabled: entry.enabled,
    hard: entry.hard,
    weight: entry.weight,
    groupKey: entry.groupKey,
  }
}

/**
 * Carry one type the builder does not manage. Entry-backed types must travel as
 * `entries` (a legacy `params` map would drop every other row); switch types have
 * no parameters and must not send an entry list at all.
 */
function preserve(
  config: ConstraintConfigDto,
  options: MergeOptions,
): ConstraintConfigRequest {
  const entryBacked =
    options.entryBackedTypes?.has(config.type) ??
    inferEntryBacked(config)
  if (entryBacked) {
    return {
      type: config.type,
      entries: (config.entries ?? []).map(toSaveEntry),
      weight: config.weight,
      hard: config.hard,
      enabled: config.enabled,
    }
  }
  return {
    type: config.type,
    params: {},
    weight: config.weight,
    hard: config.hard,
    enabled: config.enabled,
  }
}

function inferEntryBacked(config: ConstraintConfigDto): boolean {
  if (KNOWN_ENTRY_BACKED.has(config.type)) {
    return true
  }
  const entries = config.entries ?? []
  return entries.length > 1 || entries.some((entry) => Object.keys(entry.params ?? {}).length > 0)
}

/** Enabled rules of every type this page does not manage. */
export function otherRuleCount(configs: ConstraintConfigDto[]): number {
  return configs.filter((config) => !isManagedType(config.type) && config.enabled).length
}

/**
 * Groups of the two managed types that the sentence model cannot express
 * (multiple weekdays, a class-specific no-class row, …). They stay in the
 * advanced page; counting them keeps the page from implying they do not exist.
 */
export function unrepresentedGroupCount(configs: ConstraintConfigDto[]): number {
  let count = 0
  for (const config of configs) {
    const type = config.type
    if (!isManagedType(type)) {
      continue
    }
    count += groupEntries(config).filter(
      (group) => !cardFromGroup(type, group, config),
    ).length
  }
  return count
}

/** `entryBacked` flags from `GET /constraint-types`. */
export function entryBackedTypeCodes(types: ConstraintTypeDef[]): Set<string> {
  return new Set(types.filter((type) => type.entryBacked).map((type) => type.code))
}

function toInteger(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.trunc(value)
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? Math.trunc(parsed) : null
  }
  return null
}
