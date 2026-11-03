/**
 * The sentence builder's compiler: it turns a requirement such as
 * "数学 每天最多 1 节" or "全校 周五第 7 节 不排" into the constraint rows the
 * rest of the system already evaluates. There is no second evaluator here — the
 * output is exactly `constraint_config` / `constraint_entry`, so the solver,
 * draft validation, substitution and the read-only CLI keep reading one snapshot.
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

export type RuleStrength = 'HARD' | 'SOFT'
export type RuleGroup = 'TIME' | 'QUANTITY' | 'SHAPE' | 'PRESET'

export type RuleType =
  | 'CLASS_UNAVAILABLE'
  | 'TEACHER_UNAVAILABLE'
  | 'COURSE_UNAVAILABLE'
  | 'COURSE_MAX_PER_DAY'
  | 'TEACHER_MAX_PER_DAY'
  | 'CLASS_MAX_PER_DAY'
  | 'SAME_COURSE_ONCE_PER_DAY'
  | 'CONSECUTIVE_LESSONS'
  | 'COURSE_SPREAD'
  | 'CLASS_GAP_MINIMIZED'
  | 'TEACHER_MAX_CONSECUTIVE'
  | 'SPORT_NOT_FIRST_AFTERNOON'
  | 'PE_LAST_PERIOD_AVOID'
  | 'MAIN_SUBJECT_MORNING'
  | 'TEACHER_NO_FIRST_PERIOD'
  | 'TEACHER_DAY_OFF'
  | 'TEACHER_PREFERRED_SLOT'

export const CLASS_UNAVAILABLE: RuleType = 'CLASS_UNAVAILABLE'
export const TEACHER_UNAVAILABLE: RuleType = 'TEACHER_UNAVAILABLE'
export const COURSE_UNAVAILABLE: RuleType = 'COURSE_UNAVAILABLE'
export const COURSE_MAX_PER_DAY: RuleType = 'COURSE_MAX_PER_DAY'
export const TEACHER_MAX_PER_DAY: RuleType = 'TEACHER_MAX_PER_DAY'
export const CLASS_MAX_PER_DAY: RuleType = 'CLASS_MAX_PER_DAY'
export const SAME_COURSE_ONCE_PER_DAY: RuleType = 'SAME_COURSE_ONCE_PER_DAY'
export const CONSECUTIVE_LESSONS: RuleType = 'CONSECUTIVE_LESSONS'
export const COURSE_SPREAD: RuleType = 'COURSE_SPREAD'
export const CLASS_GAP_MINIMIZED: RuleType = 'CLASS_GAP_MINIMIZED'
export const TEACHER_MAX_CONSECUTIVE: RuleType = 'TEACHER_MAX_CONSECUTIVE'
export const SPORT_NOT_FIRST_AFTERNOON: RuleType = 'SPORT_NOT_FIRST_AFTERNOON'
export const PE_LAST_PERIOD_AVOID: RuleType = 'PE_LAST_PERIOD_AVOID'
export const MAIN_SUBJECT_MORNING: RuleType = 'MAIN_SUBJECT_MORNING'
export const TEACHER_NO_FIRST_PERIOD: RuleType = 'TEACHER_NO_FIRST_PERIOD'
export const TEACHER_DAY_OFF: RuleType = 'TEACHER_DAY_OFF'
export const TEACHER_PREFERRED_SLOT: RuleType = 'TEACHER_PREFERRED_SLOT'

/** The 17 types the sentence builder creates; the two system conflicts stay advanced-only. */
export const RULE_TYPES: RuleType[] = [
  CLASS_UNAVAILABLE,
  TEACHER_UNAVAILABLE,
  COURSE_UNAVAILABLE,
  COURSE_MAX_PER_DAY,
  TEACHER_MAX_PER_DAY,
  CLASS_MAX_PER_DAY,
  SAME_COURSE_ONCE_PER_DAY,
  CONSECUTIVE_LESSONS,
  COURSE_SPREAD,
  CLASS_GAP_MINIMIZED,
  TEACHER_MAX_CONSECUTIVE,
  SPORT_NOT_FIRST_AFTERNOON,
  PE_LAST_PERIOD_AVOID,
  MAIN_SUBJECT_MORNING,
  TEACHER_NO_FIRST_PERIOD,
  TEACHER_DAY_OFF,
  TEACHER_PREFERRED_SLOT,
]

const MANAGED_TYPES = new Set<string>(RULE_TYPES)
const SWITCH_TYPES = new Set<string>([CLASS_GAP_MINIMIZED])

/** Mirror of the active `ConstraintType.ENTRY_BACKED`, used only when a caller omits the catalogue. */
const KNOWN_ENTRY_BACKED = new Set<string>([
  'CLASS_UNAVAILABLE',
  'TEACHER_UNAVAILABLE',
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
  'TEACHER_MAX_CONSECUTIVE',
])

const SINGLE_ENTRY_KEY = 'single'

type Target = 'CLASS' | 'TEACHER' | 'COURSE' | 'NONE'
type CourseMode = 'ALL' | 'REQUIRED' | 'OPTIONAL'
type TimeMode = 'REQUIRED' | 'FORBIDDEN'

interface AmountSpec {
  field: string
  label: string
  min: number
  max: number
  defaultValue: number
}

interface RuleSpec {
  type: RuleType
  group: RuleGroup
  label: string
  target: Target
  /** A CLASS target may stay blank (= 全校). */
  allowSchool: boolean
  /** A TEACHER target may stay blank (= 所有教师). */
  allowAnyTeacher: boolean
  course: CourseMode
  coursePlaceholder: string
  day: TimeMode
  periods: TimeMode
  amount: AmountSpec | null
  defaultWeight: number
  hardOnly: boolean
}

function spec(
  type: RuleType,
  group: RuleGroup,
  label: string,
  target: Target,
  options: Partial<Pick<RuleSpec,
    'allowSchool' | 'allowAnyTeacher' | 'course' | 'coursePlaceholder' |
    'day' | 'periods' | 'amount' | 'defaultWeight' | 'hardOnly'>> = {},
): RuleSpec {
  return {
    type,
    group,
    label,
    target,
    allowSchool: options.allowSchool ?? false,
    allowAnyTeacher: options.allowAnyTeacher ?? false,
    course: options.course ?? 'ALL',
    coursePlaceholder: options.coursePlaceholder ?? '全部课程',
    day: options.day ?? 'FORBIDDEN',
    periods: options.periods ?? 'FORBIDDEN',
    amount: options.amount ?? null,
    defaultWeight: options.defaultWeight ?? 100,
    hardOnly: options.hardOnly ?? false,
  }
}

/** Every createable requirement, keyed by the existing constraint type it compiles to. */
export const RULE_SPECS: Record<RuleType, RuleSpec> = {
  CLASS_UNAVAILABLE: spec(CLASS_UNAVAILABLE, 'TIME', '全校/班级 的 课 不排', 'CLASS', {
    allowSchool: true, day: 'REQUIRED', periods: 'REQUIRED', hardOnly: true,
  }),
  TEACHER_UNAVAILABLE: spec(TEACHER_UNAVAILABLE, 'TIME', '教师 的 课 不排', 'TEACHER', {
    day: 'REQUIRED', periods: 'REQUIRED',
  }),
  COURSE_UNAVAILABLE: spec(COURSE_UNAVAILABLE, 'TIME', '课程 不排', 'COURSE', {
    course: 'REQUIRED', day: 'REQUIRED', periods: 'REQUIRED',
  }),
  COURSE_MAX_PER_DAY: spec(COURSE_MAX_PER_DAY, 'QUANTITY', '课程 每天最多 N 节', 'COURSE', {
    course: 'REQUIRED', amount: { field: 'maxPerDay', label: '每天最多节数', min: 1, max: 8, defaultValue: 2 }, defaultWeight: 50,
  }),
  TEACHER_MAX_PER_DAY: spec(TEACHER_MAX_PER_DAY, 'QUANTITY', '教师 每天最多 N 节', 'TEACHER', {
    amount: { field: 'maxPerDay', label: '每天最多节数', min: 1, max: 12, defaultValue: 4 }, defaultWeight: 60,
  }),
  CLASS_MAX_PER_DAY: spec(CLASS_MAX_PER_DAY, 'QUANTITY', '班级 每天最多 N 节', 'CLASS', {
    amount: { field: 'maxPerDay', label: '每天最多节数', min: 1, max: 12, defaultValue: 8 }, defaultWeight: 50,
  }),
  SAME_COURSE_ONCE_PER_DAY: spec(SAME_COURSE_ONCE_PER_DAY, 'QUANTITY', '同一课程 一天最多一节', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '全部课程',
  }),
  CONSECUTIVE_LESSONS: spec(CONSECUTIVE_LESSONS, 'SHAPE', '课程 连堂 N 节', 'COURSE', {
    course: 'REQUIRED', amount: { field: 'minConsecutive', label: '连排节数', min: 2, max: 4, defaultValue: 2 }, defaultWeight: 60,
  }),
  COURSE_SPREAD: spec(COURSE_SPREAD, 'SHAPE', '课程 分散到不同天', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '全部课程', defaultWeight: 50,
  }),
  CLASS_GAP_MINIMIZED: spec(CLASS_GAP_MINIMIZED, 'SHAPE', '班级 不空堂', 'NONE', {
    defaultWeight: 30,
  }),
  TEACHER_MAX_CONSECUTIVE: spec(TEACHER_MAX_CONSECUTIVE, 'SHAPE', '教师 连续不超过 N 节', 'TEACHER', {
    allowAnyTeacher: true,
    amount: { field: 'maxConsecutive', label: '最多连续节数', min: 1, max: 8, defaultValue: 3 }, defaultWeight: 45,
  }),
  SPORT_NOT_FIRST_AFTERNOON: spec(SPORT_NOT_FIRST_AFTERNOON, 'PRESET', '体育 不在下午第一节', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '体育类', defaultWeight: 70,
  }),
  PE_LAST_PERIOD_AVOID: spec(PE_LAST_PERIOD_AVOID, 'PRESET', '体育 不在最后一节', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '体育类', defaultWeight: 35,
  }),
  MAIN_SUBJECT_MORNING: spec(MAIN_SUBJECT_MORNING, 'PRESET', '主科 排在上午（前 N 节）', 'COURSE', {
    course: 'REQUIRED',
    amount: { field: 'beforePeriodNo', label: '上午截止节次', min: 2, max: 8, defaultValue: 4 }, defaultWeight: 55,
  }),
  TEACHER_NO_FIRST_PERIOD: spec(TEACHER_NO_FIRST_PERIOD, 'PRESET', '教师 不排第一节', 'TEACHER', {
    defaultWeight: 35,
  }),
  TEACHER_DAY_OFF: spec(TEACHER_DAY_OFF, 'PRESET', '教师 固定无课日', 'TEACHER', {
    day: 'REQUIRED', defaultWeight: 30,
  }),
  TEACHER_PREFERRED_SLOT: spec(TEACHER_PREFERRED_SLOT, 'PRESET', '教师 偏好时段', 'TEACHER', {
    day: 'REQUIRED', periods: 'REQUIRED', defaultWeight: 40,
  }),
}

export interface RuleGroupDef {
  group: RuleGroup
  label: string
  types: RuleType[]
}

/** The requirement dropdown order: one group per sentence shape. */
export const RULE_GROUPS: RuleGroupDef[] = [
  { group: 'TIME', label: '时段型', types: [CLASS_UNAVAILABLE, TEACHER_UNAVAILABLE, COURSE_UNAVAILABLE] },
  {
    group: 'QUANTITY',
    label: '数量型',
    types: [COURSE_MAX_PER_DAY, TEACHER_MAX_PER_DAY, CLASS_MAX_PER_DAY, SAME_COURSE_ONCE_PER_DAY],
  },
  {
    group: 'SHAPE',
    label: '形态型',
    types: [CONSECUTIVE_LESSONS, COURSE_SPREAD, CLASS_GAP_MINIMIZED, TEACHER_MAX_CONSECUTIVE],
  },
  {
    group: 'PRESET',
    label: '预设',
    types: [
      SPORT_NOT_FIRST_AFTERNOON, PE_LAST_PERIOD_AVOID, MAIN_SUBJECT_MORNING,
      TEACHER_NO_FIRST_PERIOD, TEACHER_DAY_OFF, TEACHER_PREFERRED_SLOT,
    ],
  },
]

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** One `period` row as the rule form needs it. */
export interface PeriodOption {
  dayOfWeek: number
  periodNo: number
  section?: string | null
}

/** The values the form edits, without the card's storage identity. */
export interface RuleInput {
  type: RuleType
  classId?: number | null
  teacherId?: number | null
  courseId?: number | null
  dayOfWeek?: number | null
  periodNos?: number[]
  amount?: number | null
  strength?: RuleStrength
}

/**
 * One editable rule card. `key` is the page's stable identity while editing;
 * `groupKey` is the persisted requirement identity (null for a legacy row that
 * never had one). `entryIds` lets an edit keep the row ids it already owns.
 */
export interface RuleCard {
  key: string
  groupKey: string | null
  type: RuleType
  classId: number | null
  teacherId: number | null
  courseId: number | null
  dayOfWeek: number | null
  periodNos: number[]
  amount: number | null
  strength: RuleStrength
  enabled: boolean
  entryIds: Record<string, number>
}

export interface RuleNames {
  teacher: (id: number) => string
  course: (id: number) => string
  klass: (id: number) => string
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

export function isSwitchRule(type: RuleType): boolean {
  return SWITCH_TYPES.has(type)
}

export function ruleLabel(type: RuleType): string {
  return RULE_SPECS[type].label
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
  dayOfWeek: number | null,
  periodNos: number[],
  periods: PeriodOption[],
): string {
  if (dayOfWeek === null) {
    return '（请选择星期）'
  }
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

type RuleShape = Pick<
  RuleCard,
  'type' | 'classId' | 'teacherId' | 'courseId' | 'dayOfWeek' | 'periodNos' | 'amount'
>

/** The sentence the card shows: "数学 每天最多 1 节" / "全校 的 课 在 周五第 7 节 不排". */
export function describeRule(parts: RuleShape, names: RuleNames, periods: PeriodOption[]): string {
  const type = parts.type
  const spec = RULE_SPECS[type]
  const teacher = parts.teacherId === null ? '教师' : names.teacher(parts.teacherId)
  const anyTeacher = parts.teacherId === null ? '所有教师' : teacher
  const klass = parts.classId === null ? '全校' : names.klass(parts.classId)
  const course = parts.courseId === null ? '全部课程' : names.course(parts.courseId)
  const sport = parts.courseId === null ? '体育' : names.course(parts.courseId)
  const amount = parts.amount === null ? 'N' : String(parts.amount)
  const day = parts.dayOfWeek === null ? '（请选择星期）' : weekdayLabel(parts.dayOfWeek)
  const time = timeLabel(parts.dayOfWeek, parts.periodNos, periods)

  switch (type) {
    case CLASS_UNAVAILABLE:
      return `${klass} 的 课 在 ${time} 不排`
    case TEACHER_UNAVAILABLE:
      return `${teacher} 的 课 在 ${time} 不排`
    case COURSE_UNAVAILABLE:
      return `${course} 在 ${time} 不排`
    case COURSE_MAX_PER_DAY:
      return `${course} 每天最多 ${amount} 节`
    case TEACHER_MAX_PER_DAY:
      return `${teacher} 每天最多 ${amount} 节`
    case CLASS_MAX_PER_DAY:
      return `${klass} 每天最多 ${amount} 节`
    case SAME_COURSE_ONCE_PER_DAY:
      return parts.courseId === null ? '同一课程 每天最多 1 节' : `${course} 每天最多 1 节`
    case CONSECUTIVE_LESSONS:
      return `${course} 连堂 ${amount} 节`
    case COURSE_SPREAD:
      return `${course} 分散到不同天`
    case CLASS_GAP_MINIMIZED:
      return '班级 不空堂'
    case TEACHER_MAX_CONSECUTIVE:
      return `${anyTeacher} 连续不超过 ${amount} 节`
    case SPORT_NOT_FIRST_AFTERNOON:
      return `${sport} 不在下午第一节`
    case PE_LAST_PERIOD_AVOID:
      return `${sport} 不在最后一节`
    case MAIN_SUBJECT_MORNING:
      return `${course} 排在上午（前 ${amount} 节）`
    case TEACHER_NO_FIRST_PERIOD:
      return `${teacher} 不排第一节`
    case TEACHER_DAY_OFF:
      return `${teacher} ${day} 不排课`
    case TEACHER_PREFERRED_SLOT:
      return `${teacher} 偏好 ${time}`
    default:
      return spec.label
  }
}

export function describeCard(card: RuleCard, names: RuleNames, periods: PeriodOption[]): string {
  return describeRule(card, names, periods)
}

/** The slots the requirement still needs before it can be saved. */
export function missingParts(card: RuleShape): string[] {
  const spec = RULE_SPECS[card.type]
  const missing: string[] = []
  if (spec.target === 'CLASS' && !spec.allowSchool && card.classId === null) {
    missing.push('选择班级')
  }
  if (spec.target === 'TEACHER' && !spec.allowAnyTeacher && card.teacherId === null) {
    missing.push('选择教师')
  }
  if (spec.course === 'REQUIRED' && card.courseId === null) {
    missing.push('选择课程')
  }
  if (spec.day === 'REQUIRED' && card.dayOfWeek === null) {
    missing.push('选择星期')
  }
  if (spec.periods === 'REQUIRED' && card.periodNos.length === 0) {
    missing.push('选择第几节')
  }
  if (spec.amount && (card.amount === null
    || card.amount < spec.amount.min || card.amount > spec.amount.max)) {
    missing.push(`填写${spec.amount.label}`)
  }
  return missing
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
  const spec = RULE_SPECS[input.type]
  return {
    type: input.type,
    classId: spec.target === 'CLASS' ? toInteger(input.classId) : null,
    teacherId: spec.target === 'TEACHER' ? toInteger(input.teacherId) : null,
    courseId: spec.course === 'ALL' ? null : toInteger(input.courseId),
    dayOfWeek: spec.day === 'REQUIRED' ? toInteger(input.dayOfWeek) : null,
    periodNos: spec.periods === 'REQUIRED' ? normalizePeriods(input.periodNos ?? []) : [],
    amount: spec.amount ? toInteger(input.amount) ?? spec.amount.defaultValue : null,
    strength: spec.hardOnly ? 'HARD' : input.strength ?? 'HARD',
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
  const card = cardFromInput(input)
  const missing = missingParts(card)
  if (missing.includes('选择第几节')) {
    throw new Error('规则至少要选择一个节次')
  }
  if (missing.length > 0) {
    throw new Error(`规则缺少：${missing.join('、')}`)
  }
  return configFromCard(card)
}

/** The save request for the whole semester: other types are carried over untouched. */
export function configsFromCards(
  existing: ConstraintConfigDto[],
  cards: RuleCard[],
  options: MergeOptions = {},
): ConstraintConfigRequest[] {
  const existingByType = new Map<string, ConstraintConfigDto>()
  existing.forEach((config) => existingByType.set(config.type, config))

  const result: ConstraintConfigRequest[] = []
  const handled = new Set<string>()
  for (const config of existing) {
    const type = config.type
    if (!isManagedType(type)) {
      result.push(preserve(config, options))
      continue
    }
    handled.add(type)
    const cardsForType = cards.filter((card) => card.type === type)
    if (isSwitchRule(type)) {
      const card = cardsForType[0]
      if (card) {
        result.push(configFromCard(card))
      }
      continue
    }
    const cardByIdentity = new Map(cardsForType.map((card) => [card.key, card]))
    const entries: SaveEntry[] = []
    // Pre-ADR rows kept a single value in `params_json`. The sentence model cannot
    // render that shape, but dropping it would silently erase a rule the advanced
    // page still shows; carry it through as one legacy entry.
    if ((config.entries ?? []).length === 0 && Object.keys(config.params ?? {}).length > 0) {
      entries.push({
        params: { ...config.params },
        enabled: true,
        hard: null,
        weight: null,
        groupKey: null,
      })
    }
    const representable = new Set(
      groupEntries(config)
        .filter((group) => cardFromGroup(type, group, config))
        .map((group) => group.identity),
    )
    const emitted = new Set<string>()
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
      type,
      entries,
      weight: config.weight ?? RULE_SPECS[type].defaultWeight,
      hard: config.hard ?? true,
      enabled: cardEnabled(cardsForType) || (config.enabled !== false
        && entries.some((entry) => entry.enabled !== false)),
    })
  }

  for (const type of RULE_TYPES) {
    if (handled.has(type) || existingByType.has(type)) {
      continue
    }
    if (isSwitchRule(type)) {
      const card = cards.find((item) => item.type === type)
      if (card) {
        result.push(configFromCard(card))
      }
      continue
    }
    const entries = cards.filter((card) => card.type === type).flatMap(cardEntries)
    if (entries.length === 0) {
      continue
    }
    result.push({
      type,
      entries,
      weight: RULE_SPECS[type].defaultWeight,
      hard: true,
      enabled: true,
    })
  }
  return result
}

function cardEnabled(cards: RuleCard[]): boolean {
  return cards.some((card) => card.enabled)
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
 * The sentence model covers exactly one weekday, one scope and a set of periods
 * (or one single-value requirement). A stored group that says anything else is not
 * a card; it stays in the advanced page and is preserved on save.
 */
function cardFromGroup(
  type: RuleType,
  group: EntryGroup,
  config: ConstraintConfigDto,
): RuleCard | null {
  const spec = RULE_SPECS[type]
  if (isSwitchRule(type)) {
    return null
  }
  if (spec.periods === 'REQUIRED') {
    return slotCard(type, group, config)
  }
  if (group.entries.length !== 1) {
    return null
  }
  const entry = group.entries[0]
  const params = entry.params ?? {}
  const card = singleCard(type, params, entry, config, group)
  return card
}

function slotCard(
  type: RuleType,
  group: EntryGroup,
  config: ConstraintConfigDto,
): RuleCard | null {
  const spec = RULE_SPECS[type]
  const days = new Set<number>()
  const periods = new Set<number>()
  const objects = new Set<number | null>()
  for (const entry of group.entries) {
    const params = entry.params ?? {}
    if (!allowedKeys(params, spec, false)) {
      return null
    }
    const day = toInteger(params.dayOfWeek)
    const periodNo = toInteger(params.periodNo)
    if (day === null || periodNo === null) {
      return null
    }
    days.add(day)
    periods.add(periodNo)
    objects.add(objectValue(spec, params))
  }
  if (days.size !== 1 || objects.size !== 1) {
    return null
  }
  const object = Array.from(objects)[0] ?? null
  if (objectRequired(spec) && object === null) {
    return null
  }
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
    classId: spec.target === 'CLASS' ? object : null,
    teacherId: spec.target === 'TEACHER' ? object : null,
    courseId: spec.target === 'COURSE' ? object : null,
    dayOfWeek: Array.from(days)[0],
    periodNos: normalizePeriods(Array.from(periods)),
    amount: null,
    strength: strengthOf(group.entries, config, spec),
    enabled: group.entries.every((entry) => entry.enabled !== false) && config.enabled !== false,
    entryIds,
  }
}

function singleCard(
  type: RuleType,
  params: Record<string, unknown>,
  entry: ConstraintEntryDto,
  config: ConstraintConfigDto,
  group: EntryGroup,
): RuleCard | null {
  const spec = RULE_SPECS[type]
  if (!allowedKeys(params, spec, true)) {
    return null
  }
  const object = objectValue(spec, params)
  if (objectRequired(spec) && object === null) {
    return null
  }
  let amount: number | null = null
  if (spec.amount) {
    amount = toInteger(params[spec.amount.field])
    if (amount === null || amount < spec.amount.min || amount > spec.amount.max) {
      return null
    }
  }
  let dayOfWeek: number | null = null
  if (spec.day === 'REQUIRED') {
    dayOfWeek = toInteger(params.dayOfWeek)
    if (dayOfWeek === null) {
      return null
    }
  }
  return {
    key: group.identity,
    groupKey: group.groupKey,
    type,
    classId: spec.target === 'CLASS' ? object : null,
    teacherId: spec.target === 'TEACHER' ? object : null,
    courseId: spec.target === 'COURSE' ? object : null,
    dayOfWeek,
    periodNos: [],
    amount,
    strength: strengthOf([entry], config, spec),
    enabled: entry.enabled !== false && config.enabled !== false,
    entryIds: entry.id === null ? {} : { [SINGLE_ENTRY_KEY]: entry.id },
  }
}

/** True when the stored parameter map is exactly what this requirement edits. */
function allowedKeys(
  params: Record<string, unknown>,
  spec: RuleSpec,
  single: boolean,
): boolean {
  const allowed = new Set<string>()
  if (spec.target !== 'NONE') {
    allowed.add(objectField(spec))
  }
  if (spec.amount) {
    allowed.add(spec.amount.field)
  }
  if (spec.day === 'REQUIRED' && single) {
    allowed.add('dayOfWeek')
  }
  if (spec.periods === 'REQUIRED') {
    allowed.add('dayOfWeek')
    allowed.add('periodNo')
  }
  return Object.keys(params).every((key) => allowed.has(key))
}

function objectField(spec: RuleSpec): string {
  if (spec.target === 'CLASS') return 'classId'
  if (spec.target === 'TEACHER') return 'teacherId'
  if (spec.target === 'COURSE') return 'courseId'
  return ''
}

function objectValue(spec: RuleSpec, params: Record<string, unknown>): number | null {
  if (spec.target === 'NONE') {
    return null
  }
  const value = params[objectField(spec)]
  return value === undefined || value === null ? null : toInteger(value)
}

function objectRequired(spec: RuleSpec): boolean {
  if (spec.target === 'CLASS') return !spec.allowSchool
  if (spec.target === 'TEACHER') return !spec.allowAnyTeacher
  if (spec.target === 'COURSE') return spec.course === 'REQUIRED'
  return false
}

function strengthOf(
  entries: ConstraintEntryDto[],
  config: ConstraintConfigDto,
  spec: RuleSpec,
): RuleStrength {
  if (spec.hardOnly) {
    return 'HARD'
  }
  return entries.some((entry) => entry.hard ?? config.hard ?? true) ? 'HARD' : 'SOFT'
}

/** Cards the builder can render, read back from `constraint_state`. */
export function cardsFromState(configs: ConstraintConfigDto[]): RuleCard[] {
  const cards: RuleCard[] = []
  for (const config of configs) {
    if (!isManagedType(config.type)) {
      continue
    }
    if (isSwitchRule(config.type)) {
      cards.push({
        key: `switch:${config.type}`,
        groupKey: null,
        type: config.type,
        classId: null,
        teacherId: null,
        courseId: null,
        dayOfWeek: null,
        periodNos: [],
        amount: null,
        strength: RULE_SPECS[config.type].hardOnly || config.hard ? 'HARD' : 'SOFT',
        enabled: config.enabled !== false,
        entryIds: {},
      })
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
  const spec = RULE_SPECS[card.type]
  if (isSwitchRule(card.type)) {
    return []
  }
  const hard = spec.hardOnly || card.strength === 'HARD'
  if (spec.periods === 'REQUIRED') {
    return normalizePeriods(card.periodNos).map((periodNo) => ({
      id: card.entryIds[slotKey(card.dayOfWeek ?? 0, periodNo)] ?? null,
      params: slotParams(card, periodNo),
      enabled: card.enabled,
      hard,
      weight: null,
      groupKey: card.groupKey,
    }))
  }
  return [{
    id: card.entryIds[SINGLE_ENTRY_KEY] ?? null,
    params: singleParams(card),
    enabled: card.enabled,
    hard,
    weight: null,
    groupKey: card.groupKey,
  }]
}

function slotParams(card: RuleCard, periodNo: number): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (card.classId !== null) {
    params.classId = card.classId
  }
  if (card.teacherId !== null) {
    params.teacherId = card.teacherId
  }
  if (card.courseId !== null) {
    params.courseId = card.courseId
  }
  params.dayOfWeek = card.dayOfWeek
  params.periodNo = periodNo
  return params
}

function singleParams(card: RuleCard): Record<string, unknown> {
  const spec = RULE_SPECS[card.type]
  const params: Record<string, unknown> = {}
  if (spec.target === 'CLASS' && card.classId !== null) {
    params.classId = card.classId
  }
  if (spec.target === 'TEACHER' && card.teacherId !== null) {
    params.teacherId = card.teacherId
  }
  if (spec.target === 'COURSE' && card.courseId !== null) {
    params.courseId = card.courseId
  }
  if (spec.amount && card.amount !== null) {
    params[spec.amount.field] = card.amount
  }
  if (spec.day === 'REQUIRED') {
    params.dayOfWeek = card.dayOfWeek
  }
  return params
}

function configFromCard(card: RuleCard): ConstraintConfigRequest {
  const spec = RULE_SPECS[card.type]
  if (isSwitchRule(card.type)) {
    return {
      type: card.type,
      params: {},
      weight: spec.defaultWeight,
      hard: card.strength === 'HARD',
      enabled: card.enabled,
    }
  }
  return {
    type: card.type,
    entries: cardEntries(card),
    weight: spec.defaultWeight,
    hard: true,
    enabled: card.enabled,
  }
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

/**
 * Enabled rules of every type this page does not manage. When the catalogue is
 * known, retired types are excluded too: they are neither editable here nor in the
 * advanced page, so counting them would promise a rule the user cannot find.
 */
export function otherRuleCount(
  configs: ConstraintConfigDto[],
  knownTypes?: ReadonlySet<string>,
): number {
  return configs.filter((config) =>
    !isManagedType(config.type)
    && config.enabled
    && (!knownTypes || knownTypes.has(config.type)),
  ).length
}

/**
 * Groups of the managed types that the sentence model cannot express (multiple
 * weekdays, a global threshold default, …). They stay in the advanced page;
 * counting them keeps the page from implying they do not exist.
 */
export function unrepresentedGroupCount(configs: ConstraintConfigDto[]): number {
  let count = 0
  for (const config of configs) {
    const type = config.type
    if (!isManagedType(type) || isSwitchRule(type)) {
      continue
    }
    if ((config.entries ?? []).length === 0 && Object.keys(config.params ?? {}).length > 0) {
      count += 1
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

/** Catalogue codes as a set, for filtering rules the builder cannot show. */
export function catalogueTypeCodes(types: ConstraintTypeDef[]): Set<string> {
  return new Set(types.map((type) => type.code))
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
