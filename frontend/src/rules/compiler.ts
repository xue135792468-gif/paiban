/**
 * The sentence builder's compiler: it turns requirements such as
 * "数学 每天最多 1 节" or "全校 周五第 7 节 不排，除了 九（2）班" into the
 * constraint rows the rest of the system already evaluates. There is no second
 * evaluator here — the output is exactly `constraint_config` / `constraint_entry`,
 * so the solver, draft validation and substitution keep reading
 * one snapshot.
 *
 * <p>A <em>card</em> is the page's unit: one to three sentences, one shared enable
 * switch and one shared delete. A sentence is one requirement and owns one persisted
 * group. Its {@code groupKey} is {@code "<cardId>:<index>"}; the single-sentence
 * groups written by ticket 08 ({@code pt-<uuid>}, no colon) still read as one card
 * each and are upgraded on edit.
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
export type RuleTargetGroup = 'SCHOOL' | 'CLASS' | 'TEACHER' | 'COURSE'

/**
 * The subject a sentence starts with. Form-only: it tells the editor which object
 * the user picked before a requirement type exists, and is inferred on load.
 */
export type SubjectKind =
  | 'SCHOOL'
  | 'ALL_TEACHERS'
  | 'CLASS'
  | 'TEACHER'
  | 'TEACHER_GROUP'
  | 'ALL_COURSES'
  | 'COURSE'
  | 'COURSE_GROUP'

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
export const PE_LAST_PERIOD_AVOID: RuleType = 'PE_LAST_PERIOD_AVOID'
export const MAIN_SUBJECT_MORNING: RuleType = 'MAIN_SUBJECT_MORNING'
export const TEACHER_NO_FIRST_PERIOD: RuleType = 'TEACHER_NO_FIRST_PERIOD'
export const TEACHER_DAY_OFF: RuleType = 'TEACHER_DAY_OFF'
export const TEACHER_PREFERRED_SLOT: RuleType = 'TEACHER_PREFERRED_SLOT'

/** The 16 types the sentence builder creates; the two system conflicts stay advanced-only. */
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
const MAX_SENTENCES = 3

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
  CLASS_UNAVAILABLE: spec(CLASS_UNAVAILABLE, 'TIME', '不上课', 'CLASS', {
    allowSchool: true, day: 'REQUIRED', periods: 'REQUIRED', hardOnly: true,
  }),
  TEACHER_UNAVAILABLE: spec(TEACHER_UNAVAILABLE, 'TIME', '不上课', 'TEACHER', {
    day: 'REQUIRED', periods: 'REQUIRED',
  }),
  COURSE_UNAVAILABLE: spec(COURSE_UNAVAILABLE, 'TIME', '不排课', 'COURSE', {
    course: 'REQUIRED', day: 'REQUIRED', periods: 'REQUIRED',
  }),
  COURSE_MAX_PER_DAY: spec(COURSE_MAX_PER_DAY, 'QUANTITY', '每天最多 N 节', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '全部课程',
    amount: { field: 'maxPerDay', label: '每天最多节数', min: 1, max: 8, defaultValue: 2 }, defaultWeight: 50,
  }),
  TEACHER_MAX_PER_DAY: spec(TEACHER_MAX_PER_DAY, 'QUANTITY', '每天最多 N 节', 'TEACHER', {
    allowAnyTeacher: true,
    amount: { field: 'maxPerDay', label: '每天最多节数', min: 1, max: 12, defaultValue: 4 }, defaultWeight: 60,
  }),
  CLASS_MAX_PER_DAY: spec(CLASS_MAX_PER_DAY, 'QUANTITY', '每天最多 N 节', 'CLASS', {
    allowSchool: true,
    amount: { field: 'maxPerDay', label: '每天最多节数', min: 1, max: 12, defaultValue: 8 }, defaultWeight: 50,
  }),
  SAME_COURSE_ONCE_PER_DAY: spec(SAME_COURSE_ONCE_PER_DAY, 'QUANTITY', '每天最多 1 节', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '同一门课程',
  }),
  CONSECUTIVE_LESSONS: spec(CONSECUTIVE_LESSONS, 'SHAPE', '连堂 N 节', 'COURSE', {
    course: 'REQUIRED', amount: { field: 'minConsecutive', label: '连排节数', min: 2, max: 4, defaultValue: 2 }, defaultWeight: 60,
  }),
  COURSE_SPREAD: spec(COURSE_SPREAD, 'SHAPE', '分散到不同天', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '全部课程', defaultWeight: 50,
  }),
  CLASS_GAP_MINIMIZED: spec(CLASS_GAP_MINIMIZED, 'SHAPE', '不空堂', 'NONE', {
    defaultWeight: 30,
  }),
  TEACHER_MAX_CONSECUTIVE: spec(TEACHER_MAX_CONSECUTIVE, 'SHAPE', '连续不超过 N 节', 'TEACHER', {
    allowAnyTeacher: true,
    amount: { field: 'maxConsecutive', label: '最多连续节数', min: 1, max: 8, defaultValue: 3 }, defaultWeight: 45,
  }),
  PE_LAST_PERIOD_AVOID: spec(PE_LAST_PERIOD_AVOID, 'PRESET', '不在最后一节', 'COURSE', {
    course: 'OPTIONAL', coursePlaceholder: '体育', defaultWeight: 35,
  }),
  MAIN_SUBJECT_MORNING: spec(MAIN_SUBJECT_MORNING, 'PRESET', '排在上午', 'COURSE', {
    course: 'REQUIRED',
    amount: { field: 'beforePeriodNo', label: '上午截止节次', min: 2, max: 8, defaultValue: 4 }, defaultWeight: 55,
  }),
  TEACHER_NO_FIRST_PERIOD: spec(TEACHER_NO_FIRST_PERIOD, 'PRESET', '不排第一节', 'TEACHER', {
    defaultWeight: 35,
  }),
  TEACHER_DAY_OFF: spec(TEACHER_DAY_OFF, 'PRESET', '固定无课日', 'TEACHER', {
    day: 'REQUIRED', defaultWeight: 30,
  }),
  TEACHER_PREFERRED_SLOT: spec(TEACHER_PREFERRED_SLOT, 'PRESET', '偏好时段', 'TEACHER', {
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
      PE_LAST_PERIOD_AVOID, MAIN_SUBJECT_MORNING,
      TEACHER_NO_FIRST_PERIOD, TEACHER_DAY_OFF, TEACHER_PREFERRED_SLOT,
    ],
  },
]

const WEEKDAYS = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** One `period` row as the rule form needs it. */
export interface PeriodOption {
  dayOfWeek: number
  periodNo: number
  /** Wall-clock start (`08:00:00` or `08:00`); drives the 上午/下午 folding. */
  startTime?: string | null
}

/** The values the form edits, without the card's storage identity. */
export interface RuleInput {
  /** Null while the form has not picked a requirement yet. */
  type: RuleType | null
  classId?: number | null
  teacherId?: number | null
  courseId?: number | null
  /** Ticket 04: a group reference wins over the concrete object. */
  teacherGroupId?: number | null
  courseGroupId?: number | null
  timeGroupId?: number | null
  days?: number[]
  periodNos?: number[]
  amount?: number | null
  strength?: RuleStrength
  /** School-scope CLASS_UNAVAILABLE sentences: the classes left out. */
  exceptionClassIds?: number[]
}

/** One requirement sentence: one card line, one persisted group. */
export interface RuleSentence {
  /** Merge identity: `group:<groupKey>` for a stored row, `entry:<id>` for a legacy row. */
  key: string
  groupKey: string | null
  type: RuleType
  classId: number | null
  teacherId: number | null
  courseId: number | null
  days: number[]
  periodNos: number[]
  amount: number | null
  strength: RuleStrength
  enabled: boolean
  entryIds: Record<string, number>
  exceptionClassIds: number[]
  /** Reserved for tickets 04/05/06: a sentence may reference a user group instead. */
  teacherGroupId: number | null
  courseGroupId: number | null
  timeGroupId: number | null
  /**
   * Ticket 04: server-side reasons the stored target can no longer be used
   * (`教师已停用：王老师`). Absent means every reference still resolves.
   */
  invalidReferences?: string[]
}

/** One editable rule card: one to three sentences sharing a switch and a delete. */
export interface RuleCard {
  key: string
  cardId: string
  enabled: boolean
  sentences: RuleSentence[]
}

export interface RuleNames {
  teacher: (id: number) => string
  course: (id: number) => string
  klass: (id: number) => string
  /** Ticket 04/05: display names of the user groups. */
  teacherGroup?: (id: number) => string
  courseGroup?: (id: number) => string
  /** Reserved for ticket 06 (time groups). */
  timeGroup?: (id: number) => string
}

export interface MergeOptions {
  /**
   * Type codes whose configuration is stored in `entries`. The page passes the
   * catalogue's `entryBacked` flags so other rules survive a save unchanged; when
   * omitted, the shape of the saved config is used instead.
   */
  entryBackedTypes?: ReadonlySet<string>
  /** Every class of the semester; needed to expand and read "除了某班". */
  classIds?: number[]
}

export interface StateOptions {
  allClassIds?: number[]
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

export function normalizeDays(days: number[]): number[] {
  return Array.from(new Set(days)).sort((left, right) => left - right)
}

export function slotKey(dayOfWeek: number, periodNo: number): string {
  return `${dayOfWeek}-${periodNo}`
}

/** Minutes after midnight for `08:00:00` / `08:00`; null when absent or unparsable. */
function minuteOfDay(value: string | null | undefined): number | null {
  if (!value) {
    return null
  }
  const [hours, minutes] = value.split(':')
  const hour = Number(hours)
  const minute = Number(minutes ?? 0)
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) {
    return null
  }
  return hour * 60 + minute
}

function sameSlots(left: number[], right: number[]): boolean {
  return left.length > 0
    && left.length === right.length
    && left.every((value, index) => value === right[index])
}

/**
 * "上午" / "下午" / "全天" when the picked periods are exactly that slice of the
 * day, otherwise null. Missing start times (or a day without periods) never fold:
 * the sentence then spells out 第 x、y 节.
 */
function foldedTimeOfDay(
  dayOfWeek: number,
  sorted: number[],
  periods?: PeriodOption[] | null,
): '上午' | '下午' | '全天' | null {
  const dayPeriods = (periods ?? []).filter((period) => period.dayOfWeek === dayOfWeek)
  if (dayPeriods.length === 0) {
    return null
  }
  const timed = dayPeriods.map((period) => ({
    periodNo: period.periodNo,
    minutes: minuteOfDay(period.startTime),
  }))
  if (timed.some((period) => period.minutes === null)) {
    return null
  }
  const all = normalizePeriods(timed.map((period) => period.periodNo))
  const morning = normalizePeriods(
    timed.filter((period) => (period.minutes as number) < 12 * 60).map((period) => period.periodNo),
  )
  const afternoon = normalizePeriods(
    timed.filter((period) => (period.minutes as number) >= 12 * 60).map((period) => period.periodNo),
  )
  if (sameSlots(sorted, morning)) {
    return '上午'
  }
  if (sameSlots(sorted, afternoon)) {
    return '下午'
  }
  if (sameSlots(sorted, all)) {
    return '全天'
  }
  return null
}

/**
 * "周一、周三下午" when the picked periods cover that whole slice of every picked
 * day, "周一、周三第 5、6 节" otherwise; no periods reads as the day list alone
 * (fixed days off), no days reads as such.
 */
export function timeLabel(
  days: number[],
  periodNos: number[],
  periods?: PeriodOption[] | null,
): string {
  const sortedDays = normalizeDays(days)
  if (sortedDays.length === 0) {
    return '（请选择星期）'
  }
  const label = sortedDays.map(weekdayLabel).join('、')
  const sorted = normalizePeriods(periodNos)
  if (sorted.length === 0) {
    return label
  }
  const folded = sortedDays.map((day) => foldedTimeOfDay(day, sorted, periods))
  const shared = folded[0]
  if (shared !== null && folded.every((value) => value === shared)) {
    return `${label}${shared}`
  }
  return `${label}第 ${sorted.join('、')} 节`
}

type RuleShape = Pick<
  RuleSentence,
  'classId' | 'teacherId' | 'courseId' | 'days' | 'periodNos' | 'amount'
> & {
  type: RuleType | null
  exceptionClassIds?: number[]
  /** Ticket 04: group references; optional so older callers need no change. */
  teacherGroupId?: number | null
  courseGroupId?: number | null
  timeGroupId?: number | null
  /** Present on form sentences only; lets the sentence grow before a type is picked. */
  subjectKind?: SubjectKind | null
}

function teacherGroupName(id: number, names: RuleNames): string {
  return names.teacherGroup?.(id) ?? '教师组'
}

function courseGroupName(id: number, names: RuleNames): string {
  return names.courseGroup?.(id) ?? '课程组'
}

function teacherSubject(parts: RuleShape, names: RuleNames): string {
  if (parts.teacherGroupId !== null && parts.teacherGroupId !== undefined) {
    return teacherGroupName(parts.teacherGroupId, names)
  }
  if (parts.teacherId !== null && parts.teacherId !== undefined) {
    return names.teacher(parts.teacherId)
  }
  return parts.type && RULE_SPECS[parts.type].allowAnyTeacher
    ? '所有教师'
    : '（请选择教师）'
}

function classSubject(parts: RuleShape, names: RuleNames): string {
  if (parts.classId !== null && parts.classId !== undefined) {
    return names.klass(parts.classId)
  }
  return parts.type && RULE_SPECS[parts.type].allowSchool
    ? '全校'
    : '（请选择班级）'
}

function courseSubject(parts: RuleShape, names: RuleNames): string {
  if (parts.courseGroupId !== null && parts.courseGroupId !== undefined) {
    return courseGroupName(parts.courseGroupId, names)
  }
  if (parts.courseId !== null && parts.courseId !== undefined) {
    return names.course(parts.courseId)
  }
  if (!parts.type) {
    return '（请选择课程）'
  }
  const rule = RULE_SPECS[parts.type]
  return rule.course === 'REQUIRED' ? '（请选择课程）' : rule.coursePlaceholder
}

/** The subject a sentence without a type can still show, from the form's own pick. */
function pendingSubject(parts: RuleShape, names: RuleNames): string | null {
  switch (parts.subjectKind) {
    case 'SCHOOL':
      return '全校'
    case 'ALL_TEACHERS':
      return '所有教师'
    case 'ALL_COURSES':
      return '全部课程'
    case 'CLASS':
      return parts.classId === null || parts.classId === undefined
        ? null : names.klass(parts.classId)
    case 'TEACHER':
      return parts.teacherId === null || parts.teacherId === undefined
        ? null : names.teacher(parts.teacherId)
    case 'TEACHER_GROUP':
      return parts.teacherGroupId === null || parts.teacherGroupId === undefined
        ? null : teacherGroupName(parts.teacherGroupId, names)
    case 'COURSE':
      return parts.courseId === null || parts.courseId === undefined
        ? null : names.course(parts.courseId)
    case 'COURSE_GROUP':
      return parts.courseGroupId === null || parts.courseGroupId === undefined
        ? null : courseGroupName(parts.courseGroupId, names)
    default:
      break
  }
  if (parts.teacherGroupId !== null && parts.teacherGroupId !== undefined) {
    return teacherGroupName(parts.teacherGroupId, names)
  }
  if (parts.teacherId !== null && parts.teacherId !== undefined) {
    return names.teacher(parts.teacherId)
  }
  if (parts.classId !== null && parts.classId !== undefined) {
    return names.klass(parts.classId)
  }
  if (parts.courseGroupId !== null && parts.courseGroupId !== undefined) {
    return courseGroupName(parts.courseGroupId, names)
  }
  if (parts.courseId !== null && parts.courseId !== undefined) {
    return names.course(parts.courseId)
  }
  return null
}

/**
 * One sentence in speaking order: subject → time → requirement.
 * "王老师 周五下午 不上课" / "体育 不在最后一节" / "数学 每天最多 1 节".
 */
export function describeRule(
  parts: RuleShape,
  names: RuleNames,
  periods?: PeriodOption[] | null,
): string {
  const days = parts.days ?? []
  const timeGroupId = parts.timeGroupId ?? null
  // A time group is the stored form of the time slice, so its name wins over any
  // weekday/period folding. The row carries no day/period params at all.
  const timeGroupName = timeGroupId === null
    ? null
    : names.timeGroup?.(timeGroupId) ?? '时间组'
  const time = timeGroupName ?? timeLabel(days, parts.periodNos ?? [], periods)
  if (!parts.type) {
    const subject = pendingSubject(parts, names)
    const pieces: string[] = []
    if (subject) {
      pieces.push(subject)
    }
    if (days.length > 0 || timeGroupName !== null) {
      pieces.push(time)
    }
    return pieces.length === 0
      ? '（请先选谁 / 什么课）'
      : `${pieces.join(' ')} （请选择要求）`
  }
  const type = parts.type
  const spec = RULE_SPECS[type]
  const teacher = teacherSubject(parts, names)
  const klass = classSubject(parts, names)
  const course = courseSubject(parts, names)
  const amount = parts.amount === null || parts.amount === undefined ? 'N' : String(parts.amount)
  const sortedDays = normalizeDays(days)
  const day = sortedDays.length === 0
    ? '（请选择星期）' : sortedDays.map(weekdayLabel).join('、')
  const exceptions = parts.exceptionClassIds?.length
    ? `，除了 ${parts.exceptionClassIds.map((id) => names.klass(id)).join('、')}`
    : ''

  switch (type) {
    case CLASS_UNAVAILABLE:
      return `${klass} ${time} 不上课${exceptions}`
    case TEACHER_UNAVAILABLE:
      return `${teacher} ${time} 不上课`
    case COURSE_UNAVAILABLE:
      return `${course} 不排在 ${time}`
    case COURSE_MAX_PER_DAY:
      return `${course} 每天最多 ${amount} 节`
    case TEACHER_MAX_PER_DAY:
      return `${teacher} 每天最多 ${amount} 节`
    case CLASS_MAX_PER_DAY:
      return `${klass} 每天最多 ${amount} 节`
    case SAME_COURSE_ONCE_PER_DAY:
      return `${course} 每天最多 1 节`
    case CONSECUTIVE_LESSONS:
      return `${course} 连堂 ${amount} 节`
    case COURSE_SPREAD:
      return `${course} 分散到不同天`
    case CLASS_GAP_MINIMIZED:
      return '班级 不空堂'
    case TEACHER_MAX_CONSECUTIVE:
      return `${teacher} 连续不超过 ${amount} 节`
    case PE_LAST_PERIOD_AVOID:
      return `${course} 不在最后一节`
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

export function describeSentence(
  sentence: RuleSentence,
  names: RuleNames,
  periods?: PeriodOption[] | null,
): string {
  return describeRule(sentence, names, periods)
}

/** One card's sentences, joined for a compact card line. */
export function describeCard(
  card: RuleCard,
  names: RuleNames,
  periods?: PeriodOption[] | null,
): string {
  return card.sentences
    .map((sentence) => describeSentence(sentence, names, periods))
    .join('；')
}

/** The slots the requirement still needs before it can be saved. */
export function missingParts(card: RuleShape): string[] {
  if (!card.type) {
    return ['选择要求']
  }
  const spec = RULE_SPECS[card.type]
  const missing: string[] = []
  if (spec.target === 'CLASS' && !spec.allowSchool && (card.classId ?? null) === null) {
    missing.push('选择班级')
  }
  if (spec.target === 'TEACHER' && !spec.allowAnyTeacher
    && (card.teacherId ?? null) === null && (card.teacherGroupId ?? null) === null) {
    missing.push('选择教师')
  }
  if (spec.course === 'REQUIRED'
    && (card.courseId ?? null) === null && (card.courseGroupId ?? null) === null) {
    missing.push('选择课程')
  }
  // Only a day × period grid can be replaced by a time group; fixed days off
  // still need their weekdays.
  const hasTimeGroup = spec.periods === 'REQUIRED'
    && (card.timeGroupId ?? null) !== null
  if (spec.day === 'REQUIRED' && (card.days ?? []).length === 0 && !hasTimeGroup) {
    missing.push('选择星期')
  }
  if (spec.periods === 'REQUIRED' && (card.periodNos ?? []).length === 0 && !hasTimeGroup) {
    missing.push('选择第几节')
  }
  if (spec.amount && (card.amount === null || card.amount === undefined
    || card.amount < spec.amount.min || card.amount > spec.amount.max)) {
    missing.push(`填写${spec.amount.label}`)
  }
  return missing
}

/**
 * Everything that keeps one sentence from being saved, including an exception that
 * would leave no class behind. Empty string means "complete".
 */
export function sentenceProblem(sentence: RuleShape, allClassIds?: number[]): string {
  const missing = missingParts(sentence)
  if (missing.length > 0) {
    return `还差：${missing.join('、')}`
  }
  const exceptions = sentence.exceptionClassIds ?? []
  if (sentence.type === CLASS_UNAVAILABLE && (sentence.classId ?? null) === null
    && exceptions.length > 0) {
    if (!allClassIds || allClassIds.length === 0) {
      return '还没有班级，无法展开「除了」'
    }
    const remaining = allClassIds.filter((id) => !exceptions.includes(id))
    if (remaining.length === 0) {
      return '「除了」不能覆盖全部班级'
    }
  }
  return ''
}

/** The classes one sentence expands to when saved. */
export function sentenceClasses(
  sentence: RuleSentence,
  allClassIds?: number[],
): Array<number | null> {
  if (sentence.type === CLASS_UNAVAILABLE && sentence.classId === null
    && sentence.exceptionClassIds.length > 0) {
    const ids = allClassIds ?? []
    if (ids.length === 0) {
      throw new Error('缺少班级名单，无法展开「除了」')
    }
    return ids.filter((id) => !sentence.exceptionClassIds.includes(id))
  }
  return [sentence.classId]
}

export function newGroupKey(): string {
  const uuid =
    globalThis.crypto?.randomUUID?.() ??
    `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
  return `pt-${uuid}`
}

/** A new card id; sentence groups append `:<index>` to it. */
export function newCardId(): string {
  return newGroupKey()
}

export function newCardKey(): string {
  return `new:${newCardId()}`
}

/** True when the stored group key is the new card form (`<cardId>:<index>`). */
function groupIndex(groupKey: string | null): number | null {
  const match = groupKey ? /^(.*):(\d+)$/.exec(groupKey) : null
  return match ? Number(match[2]) : null
}

function cardIdOf(groupKey: string | null): string | null {
  if (!groupKey) {
    return null
  }
  const match = /^(.*):(\d+)$/.exec(groupKey)
  return match ? match[1] : groupKey
}

function sentenceFromInput(input: RuleInput, groupKey: string | null): RuleSentence {
  if (!input.type) {
    throw new Error('规则缺少：选择要求')
  }
  const rule = RULE_SPECS[input.type]
  const isSchoolUnavailable = input.type === CLASS_UNAVAILABLE && (input.classId ?? null) === null
  const teacherGroupId = rule.target === 'TEACHER' ? toInteger(input.teacherGroupId) : null
  const courseGroupId = rule.course === 'ALL' ? null : toInteger(input.courseGroupId)
  // A time group only replaces a day × period grid, so fixed days off (which only
  // pick weekdays) never keep one.
  const timeGroupId = rule.periods === 'REQUIRED' ? toInteger(input.timeGroupId) : null
  return {
    key: groupKey ? `group:${groupKey}` : `new:${newGroupKey()}`,
    groupKey,
    type: input.type,
    classId: rule.target === 'CLASS' ? toInteger(input.classId) : null,
    teacherId: rule.target === 'TEACHER' && teacherGroupId === null
      ? toInteger(input.teacherId) : null,
    courseId: rule.course !== 'ALL' && courseGroupId === null
      ? toInteger(input.courseId) : null,
    days: rule.day === 'REQUIRED' && timeGroupId === null
      ? normalizeDays(input.days ?? []) : [],
    periodNos: rule.periods === 'REQUIRED' && timeGroupId === null
      ? normalizePeriods(input.periodNos ?? []) : [],
    amount: rule.amount ? toInteger(input.amount) ?? rule.amount.defaultValue : null,
    strength: rule.hardOnly ? 'HARD' : input.strength ?? 'HARD',
    enabled: true,
    entryIds: {},
    exceptionClassIds: isSchoolUnavailable
      ? Array.from(new Set(input.exceptionClassIds ?? [])).sort((left, right) => left - right)
      : [],
    teacherGroupId,
    courseGroupId,
    timeGroupId,
  }
}

function cardOfSentences(cardId: string, sentences: RuleSentence[]): RuleCard {
  return {
    key: `card:${cardId}`,
    cardId,
    enabled: sentences.some((sentence) => sentence.enabled),
    sentences,
  }
}

/** One card holding the given sentences; every sentence gets `<cardId>:<index>`. */
export function cardFromInputs(inputs: RuleInput[]): RuleCard {
  const cardId = newCardId()
  const limited = inputs.slice(0, MAX_SENTENCES)
  return cardOfSentences(
    cardId,
    limited.map((input, index) => sentenceFromInput(input, `${cardId}:${index}`)),
  )
}

export function cardFromInput(input: RuleInput): RuleCard {
  return cardFromInputs([input])
}

/** One sentence of a form submission; its key names the stored sentence being edited. */
export interface CardSentenceInput {
  key: string | null
  input: RuleInput
}

/**
 * Applies a form submission to a card. Existing sentences keep their card id and,
 * when the requirement type does not change, the row ids of their slots; other
 * sentences of the card are untouched. A legacy `pt-<uuid>` card upgrades to
 * `<cardId>:<index>` on its first edit.
 */
export function applyCardInputs(
  initial: RuleCard | null,
  payload: CardSentenceInput[],
): RuleCard {
  if (!initial) {
    return cardFromInputs(payload.map((item) => item.input))
  }
  const byKey = new Map(initial.sentences.map((sentence) => [sentence.key, sentence]))
  const used = new Set<number>()
  const reserved = new Set<number>()
  initial.sentences.forEach((sentence) => {
    const index = groupIndex(sentence.groupKey)
    if (index !== null) {
      reserved.add(index)
    }
  })
  const nextFree = () => {
    let index = 0
    while (used.has(index) || reserved.has(index)) {
      index += 1
    }
    return index
  }
  const sentences = payload.slice(0, MAX_SENTENCES).map((item) => {
    const existing = item.key ? byKey.get(item.key) : undefined
    if (!existing) {
      const index = nextFree()
      used.add(index)
      return sentenceFromInput(item.input, `${initial.cardId}:${index}`)
    }
    const index = groupIndex(existing.groupKey) ?? nextFree()
    used.add(index)
    const typeChanged = existing.type !== item.input.type
    const groupKey = `${initial.cardId}:${index}`
    return {
      ...sentenceFromInput(item.input, groupKey),
      key: `group:${groupKey}`,
      enabled: existing.enabled,
      entryIds: typeChanged ? {} : existing.entryIds,
    }
  })
  return { ...initial, sentences }
}

/** A copy with a fresh card id: same sentences, no stored row ids. */
export function duplicateCard(card: RuleCard): RuleCard {
  const cardId = newCardId()
  return cardOfSentences(
    cardId,
    card.sentences.map((sentence, index) => ({
      ...sentence,
      key: `group:${cardId}:${index}`,
      groupKey: `${cardId}:${index}`,
      entryIds: {},
      days: [...sentence.days],
      periodNos: [...sentence.periodNos],
      exceptionClassIds: [...sentence.exceptionClassIds],
    })),
  )
}

/** The shared switch: on/off applies to every sentence of the card. */
export function toggleCard(card: RuleCard, enabled: boolean): RuleCard {
  return {
    ...card,
    enabled,
    sentences: card.sentences.map((sentence) => ({ ...sentence, enabled })),
  }
}

/** One sentence compiled on its own. Used by tests and by callers that only need a payload. */
export function compileRule(input: RuleInput, options: MergeOptions = {}): ConstraintConfigRequest {
  const card = cardFromInput(input)
  const sentence = card.sentences[0]
  const missing = missingParts(sentence)
  if (missing.includes('选择第几节')) {
    throw new Error('规则至少要选择一个节次')
  }
  if (missing.length > 0) {
    throw new Error(`规则缺少：${missing.join('、')}`)
  }
  return configFromSentence(sentence, options)
}

function configFromSentence(
  sentence: RuleSentence,
  options: MergeOptions,
): ConstraintConfigRequest {
  const spec = RULE_SPECS[sentence.type]
  if (isSwitchRule(sentence.type)) {
    return {
      type: sentence.type,
      params: {},
      weight: spec.defaultWeight,
      hard: sentence.strength === 'HARD',
      enabled: sentence.enabled,
    }
  }
  return {
    type: sentence.type,
    entries: sentenceEntries(sentence, options),
    weight: spec.defaultWeight,
    hard: true,
    enabled: sentence.enabled,
  }
}

/** The save request for one card alone, merged onto nothing (the impact preview). */
export function cardConfigs(
  card: RuleCard,
  options: MergeOptions = {},
): ConstraintConfigRequest[] {
  return configsFromCards([], [card], options)
}

/** The save request for the whole semester: other types are carried over untouched. */
export function configsFromCards(
  existing: ConstraintConfigDto[],
  cards: RuleCard[],
  options: MergeOptions = {},
): ConstraintConfigRequest[] {
  const existingByType = new Map<string, ConstraintConfigDto>()
  existing.forEach((config) => existingByType.set(config.type, config))
  const sentences = cards.flatMap((card) => card.sentences)

  const result: ConstraintConfigRequest[] = []
  const handled = new Set<string>()
  for (const config of existing) {
    const type = config.type
    if (!isManagedType(type)) {
      result.push(preserve(config, options))
      continue
    }
    handled.add(type)
    const typeSentences = sentences.filter((sentence) => sentence.type === type)
    if (isSwitchRule(type)) {
      const sentence = typeSentences[0]
      if (sentence) {
        result.push(configFromSentence(sentence, options))
      }
      continue
    }
    const byKey = new Map(typeSentences.map((sentence) => [sentence.key, sentence]))
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
    const groups = groupEntries(config)
    const representable = new Set(
      groups
        .filter((group) => sentenceFromGroup(type, group, config, options))
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
      const sentence = byKey.get(identity)
      if (sentence) {
        entries.push(...sentenceEntries(sentence, options))
      }
    })
    for (const sentence of typeSentences) {
      if (!emitted.has(sentence.key)) {
        entries.push(...sentenceEntries(sentence, options))
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
      enabled: typeSentences.some((sentence) => sentence.enabled)
        || (config.enabled !== false && entries.some((entry) => entry.enabled !== false)),
    })
  }

  for (const type of RULE_TYPES) {
    if (handled.has(type) || existingByType.has(type)) {
      continue
    }
    const typeSentences = sentences.filter((sentence) => sentence.type === type)
    if (typeSentences.length === 0) {
      continue
    }
    if (isSwitchRule(type)) {
      result.push(configFromSentence(typeSentences[0], options))
      continue
    }
    const entries = typeSentences.flatMap((sentence) => sentenceEntries(sentence, options))
    if (entries.length === 0) {
      continue
    }
    result.push({
      type,
      entries,
      weight: RULE_SPECS[type].defaultWeight,
      hard: true,
      enabled: typeSentences.some((sentence) => sentence.enabled),
    })
  }
  return result
}

function sentenceEntries(sentence: RuleSentence, options: MergeOptions): SaveEntry[] {
  const spec = RULE_SPECS[sentence.type]
  const hard = spec.hardOnly || sentence.strength === 'HARD'
  if (spec.periods === 'REQUIRED') {
    if (sentence.timeGroupId !== null) {
      // A time group replaces the day × period grid: one row carries the group id.
      return sentenceClasses(sentence, options.classIds).map((classId) => {
        const params = objectParams(sentence)
        if (classId !== null) {
          params.classId = classId
        }
        params.timeGroupId = sentence.timeGroupId
        return {
          id: sentence.entryIds[SINGLE_ENTRY_KEY] ?? null,
          params,
          enabled: sentence.enabled,
          hard,
          weight: null,
          groupKey: sentence.groupKey,
        }
      })
    }
    const days = normalizeDays(sentence.days)
    const classes = sentenceClasses(sentence, options.classIds)
    const exceptionSentence = sentence.exceptionClassIds.length > 0
    return classes.flatMap((classId) =>
      days.flatMap((day) =>
        normalizePeriods(sentence.periodNos).map((periodNo) => {
          const params = objectParams(sentence)
          if (classId !== null) {
            params.classId = classId
          }
          params.dayOfWeek = day
          params.periodNo = periodNo
          const key = exceptionSentence && classId !== null
            ? `${classId}:${slotKey(day, periodNo)}`
            : slotKey(day, periodNo)
          return {
            id: sentence.entryIds[key] ?? null,
            params,
            enabled: sentence.enabled,
            hard,
            weight: null,
            groupKey: sentence.groupKey,
          }
        }),
      ),
    )
  }
  if (spec.day === 'REQUIRED') {
    // Fixed days off (TEACHER_DAY_OFF) persist as one row per weekday, same group.
    return normalizeDays(sentence.days).map((day) => ({
      id: sentence.entryIds[`day-${day}`] ?? null,
      params: { ...objectParams(sentence), dayOfWeek: day },
      enabled: sentence.enabled,
      hard,
      weight: null,
      groupKey: sentence.groupKey,
    }))
  }
  return [{
    id: sentence.entryIds[SINGLE_ENTRY_KEY] ?? null,
    params: objectParams(sentence),
    enabled: sentence.enabled,
    hard,
    weight: null,
    groupKey: sentence.groupKey,
  }]
}

/** The object and amount parameters a sentence writes, without any weekday. */
function objectParams(sentence: RuleSentence): Record<string, unknown> {
  const spec = RULE_SPECS[sentence.type]
  const params: Record<string, unknown> = {}
  if (spec.target === 'CLASS' && sentence.classId !== null) {
    params.classId = sentence.classId
  }
  if (spec.target === 'TEACHER') {
    if (sentence.teacherGroupId !== null) {
      params.teacherGroupId = sentence.teacherGroupId
    } else if (sentence.teacherId !== null) {
      params.teacherId = sentence.teacherId
    }
  }
  if (spec.target === 'COURSE') {
    if (sentence.courseGroupId !== null) {
      params.courseGroupId = sentence.courseGroupId
    } else if (sentence.courseId !== null) {
      params.courseId = sentence.courseId
    }
  }
  if (spec.amount && sentence.amount !== null) {
    params[spec.amount.field] = sentence.amount
  }
  return params
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
 * The sentence model covers one scope, a set of weekdays and a set of periods
 * (or one single-value requirement). A stored group that says anything else is
 * not a card; it stays in the advanced page and is preserved on save.
 */
function sentenceFromGroup(
  type: RuleType,
  group: EntryGroup,
  config: ConstraintConfigDto,
  options: MergeOptions,
): RuleSentence | null {
  const spec = RULE_SPECS[type]
  if (isSwitchRule(type)) {
    return null
  }
  if (spec.periods === 'REQUIRED') {
    return slotSentence(type, group, config, options)
  }
  if (spec.day === 'REQUIRED') {
    return dayOffSentence(type, group, config)
  }
  if (group.entries.length !== 1) {
    return null
  }
  const entry = group.entries[0]
  const params = entry.params ?? {}
  return singleSentence(type, params, entry, config, group)
}

function slotSentence(
  type: RuleType,
  group: EntryGroup,
  config: ConstraintConfigDto,
  options: MergeOptions,
): RuleSentence | null {
  const spec = RULE_SPECS[type]
  const days = new Set<number>()
  const periods = new Set<number>()
  const objects = new Set<number | null>()
  const slotsByObject = new Map<number | null, Set<string>>()
  const teacherGroups = new Set<number>()
  const courseGroups = new Set<number>()
  const timeGroups = new Set<number>()
  for (const entry of group.entries) {
    const params = entry.params ?? {}
    if (!allowedKeys(params, spec)) {
      return null
    }
    const teacherGroupId = toInteger(params.teacherGroupId)
    const courseGroupId = toInteger(params.courseGroupId)
    const timeGroupId = toInteger(params.timeGroupId)
    const object = objectValue(spec, params)
    // A concrete object and a group reference cannot live in the same row: the
    // server would reject it too, so the sentence is not representable.
    if (object !== null && (teacherGroupId !== null || courseGroupId !== null)) {
      return null
    }
    if (teacherGroupId !== null) {
      teacherGroups.add(teacherGroupId)
    }
    if (courseGroupId !== null) {
      courseGroups.add(courseGroupId)
    }
    if (timeGroupId !== null) {
      if (toInteger(params.dayOfWeek) !== null || toInteger(params.periodNo) !== null) {
        return null
      }
      timeGroups.add(timeGroupId)
    } else {
      const day = toInteger(params.dayOfWeek)
      const periodNo = toInteger(params.periodNo)
      if (day === null || periodNo === null) {
        return null
      }
      days.add(day)
      periods.add(periodNo)
      const slots = slotsByObject.get(object) ?? new Set<string>()
      slots.add(slotKey(day, periodNo))
      slotsByObject.set(object, slots)
    }
    objects.add(object)
  }
  if (teacherGroups.size > 1 || courseGroups.size > 1 || timeGroups.size > 1) {
    return null
  }
  const sortedDays = normalizeDays(Array.from(days))
  const sortedPeriods = normalizePeriods(Array.from(periods))
  const timeGroupId = timeGroups.size === 1 ? Array.from(timeGroups)[0] : null
  if (timeGroupId === null && (sortedDays.length === 0 || sortedPeriods.length === 0)) {
    return null
  }
  const exception = exceptionScope(
    spec, objects, slotsByObject, sortedDays, sortedPeriods, options,
  )
  if (exception === 'INVALID') {
    return null
  }
  let classId: number | null = null
  let exceptionClassIds: number[] = []
  const teacherGroupId = teacherGroups.size === 1 ? Array.from(teacherGroups)[0] : null
  const courseGroupId = courseGroups.size === 1 ? Array.from(courseGroups)[0] : null
  if (exception) {
    exceptionClassIds = exception
  } else {
    if (objects.size !== 1) {
      return null
    }
    const object = Array.from(objects)[0] ?? null
    const hasGroup = teacherGroupId !== null || courseGroupId !== null
    if (objectRequired(spec) && object === null && !hasGroup) {
      return null
    }
    classId = spec.target === 'CLASS' ? object : null
  }
  // A multi-day sentence compiles to the full day × period grid; a group that
  // skips some combinations would be misread, so it stays unrepresented.
  if (timeGroupId === null && !coversGrid(slotsByObject, sortedDays, sortedPeriods)) {
    return null
  }
  const entryIds: Record<string, number> = {}
  const exceptionSentence = exceptionClassIds.length > 0
  group.entries.forEach((entry) => {
    if (entry.id === null) {
      return
    }
    if (timeGroupId !== null) {
      entryIds[SINGLE_ENTRY_KEY] = entry.id
      return
    }
    const day = toInteger(entry.params?.dayOfWeek)
    const periodNo = toInteger(entry.params?.periodNo)
    const object = objectValue(spec, entry.params ?? {})
    if (day === null || periodNo === null) {
      return
    }
    const key = exceptionSentence && object !== null
      ? `${object}:${slotKey(day, periodNo)}`
      : slotKey(day, periodNo)
    entryIds[key] = entry.id
  })
  return {
    key: group.identity,
    groupKey: group.groupKey,
    type,
    classId,
    teacherId: spec.target === 'TEACHER' && teacherGroupId === null
      ? firstObject(objects) : null,
    courseId: spec.target === 'COURSE' && courseGroupId === null
      ? firstObject(objects) : null,
    days: sortedDays,
    periodNos: sortedPeriods,
    amount: null,
    strength: strengthOf(group.entries, config, spec),
    enabled: group.entries.every((entry) => entry.enabled !== false) && config.enabled !== false,
    entryIds,
    exceptionClassIds,
    teacherGroupId,
    courseGroupId,
    timeGroupId,
    ...invalidField(group.entries),
  }
}

/**
 * Fixed days off (TEACHER_DAY_OFF) persist as one row per weekday with the same
 * group key; reading them back joins the days into one sentence.
 */
function dayOffSentence(
  type: RuleType,
  group: EntryGroup,
  config: ConstraintConfigDto,
): RuleSentence | null {
  const spec = RULE_SPECS[type]
  const objects = new Set<number | null>()
  const days = new Set<number>()
  const entryIds: Record<string, number> = {}
  const teacherGroups = new Set<number>()
  const courseGroups = new Set<number>()
  const timeGroups = new Set<number>()
  let amount: number | null = null
  for (const entry of group.entries) {
    const params = entry.params ?? {}
    if (!allowedKeys(params, spec)) {
      return null
    }
    const teacherGroupId = toInteger(params.teacherGroupId)
    const courseGroupId = toInteger(params.courseGroupId)
    const timeGroupId = toInteger(params.timeGroupId)
    const object = objectValue(spec, params)
    if (object !== null && (teacherGroupId !== null || courseGroupId !== null)) {
      return null
    }
    if (teacherGroupId !== null) teacherGroups.add(teacherGroupId)
    if (courseGroupId !== null) courseGroups.add(courseGroupId)
    if (timeGroupId !== null) timeGroups.add(timeGroupId)
    const day = toInteger(params.dayOfWeek)
    if (day === null || days.has(day)) {
      return null
    }
    objects.add(object)
    if (spec.amount) {
      const value = toInteger(params[spec.amount.field])
      if (value === null || value < spec.amount.min || value > spec.amount.max) {
        return null
      }
      if (amount !== null && amount !== value) {
        return null
      }
      amount = value
    }
    days.add(day)
    if (entry.id !== null) {
      entryIds[`day-${day}`] = entry.id
    }
  }
  if (days.size === 0 || objects.size !== 1) {
    return null
  }
  if (teacherGroups.size > 1 || courseGroups.size > 1 || timeGroups.size > 1) {
    return null
  }
  const teacherGroupId = teacherGroups.size === 1 ? Array.from(teacherGroups)[0] : null
  const courseGroupId = courseGroups.size === 1 ? Array.from(courseGroups)[0] : null
  const timeGroupId = timeGroups.size === 1 ? Array.from(timeGroups)[0] : null
  const object = Array.from(objects)[0] ?? null
  const hasGroup = teacherGroupId !== null || courseGroupId !== null
  if (objectRequired(spec) && object === null && !hasGroup) {
    return null
  }
  return {
    key: group.identity,
    groupKey: group.groupKey,
    type,
    classId: spec.target === 'CLASS' ? object : null,
    teacherId: spec.target === 'TEACHER' && teacherGroupId === null ? object : null,
    courseId: spec.target === 'COURSE' && courseGroupId === null ? object : null,
    days: normalizeDays(Array.from(days)),
    periodNos: [],
    amount,
    strength: strengthOf(group.entries, config, spec),
    enabled: group.entries.every((entry) => entry.enabled !== false) && config.enabled !== false,
    entryIds,
    exceptionClassIds: [],
    teacherGroupId,
    courseGroupId,
    timeGroupId,
    ...invalidField(group.entries),
  }
}

/** The reasons of a stored group, or nothing when every reference resolves. The field
 * is only present when broken, so existing card comparisons stay unchanged.
 */
function invalidField(entries: ConstraintEntryDto[]): { invalidReferences?: string[] } {
  const reasons = [...new Set(entries.flatMap((entry) => entry.invalidReferences ?? []))]
  return reasons.length > 0 ? { invalidReferences: reasons } : {}
}

function firstObject(objects: Set<number | null>): number | null {
  return Array.from(objects)[0] ?? null
}

function gridSlots(days: number[], periods: number[]): string[] {
  return days.flatMap((day) => periods.map((periodNo) => slotKey(day, periodNo)))
}

function matchesGrid(slots: Set<string>, days: number[], periods: number[]): boolean {
  const grid = gridSlots(days, periods)
  return slots.size === grid.length && grid.every((slot) => slots.has(slot))
}

function coversGrid(
  slotsByObject: Map<number | null, Set<string>>,
  days: number[],
  periods: number[],
): boolean {
  return Array.from(slotsByObject.values())
    .every((slots) => matchesGrid(slots, days, periods))
}

/**
 * A school-scope CLASS_UNAVAILABLE group stored as one row per class is an
 * exception sentence: every class except the ones named gets the same day/period
 * grid. Anything else (mixed scopes, different grids) is not representable here.
 */
function exceptionScope(
  spec: RuleSpec,
  objects: Set<number | null>,
  slotsByObject: Map<number | null, Set<string>>,
  days: number[],
  periods: number[],
  options: MergeOptions,
): number[] | null | 'INVALID' {
  const allClasses = options.classIds ?? []
  if (spec.type !== CLASS_UNAVAILABLE || spec.target !== 'CLASS' || allClasses.length === 0) {
    return null
  }
  if (objects.has(null) || objects.size < 2) {
    return null
  }
  const presented = Array.from(objects)
    .filter((value): value is number => value !== null)
  const remaining = allClasses.filter((id) => !presented.includes(id))
  if (remaining.length === 0) {
    return null
  }
  const signatures = new Set(
    Array.from(slotsByObject.values())
      .map((slots) => Array.from(slots).sort().join(',')),
  )
  if (signatures.size !== 1) {
    return 'INVALID'
  }
  if (!coversGrid(slotsByObject, days, periods)) {
    return 'INVALID'
  }
  return remaining
}

function singleSentence(
  type: RuleType,
  params: Record<string, unknown>,
  entry: ConstraintEntryDto,
  config: ConstraintConfigDto,
  group: EntryGroup,
): RuleSentence | null {
  const spec = RULE_SPECS[type]
  if (!allowedKeys(params, spec)) {
    return null
  }
  const teacherGroupId = toInteger(params.teacherGroupId)
  const courseGroupId = toInteger(params.courseGroupId)
  const timeGroupId = toInteger(params.timeGroupId)
  const object = objectValue(spec, params)
  if (object !== null && (teacherGroupId !== null || courseGroupId !== null)) {
    return null
  }
  const hasGroup = teacherGroupId !== null || courseGroupId !== null
  if (objectRequired(spec) && object === null && !hasGroup) {
    return null
  }
  let amount: number | null = null
  if (spec.amount) {
    amount = toInteger(params[spec.amount.field])
    if (amount === null || amount < spec.amount.min || amount > spec.amount.max) {
      return null
    }
  }
  return {
    key: group.identity,
    groupKey: group.groupKey,
    type,
    classId: spec.target === 'CLASS' ? object : null,
    teacherId: spec.target === 'TEACHER' && teacherGroupId === null ? object : null,
    courseId: spec.target === 'COURSE' && courseGroupId === null ? object : null,
    days: [],
    periodNos: [],
    amount,
    strength: strengthOf([entry], config, spec),
    enabled: entry.enabled !== false && config.enabled !== false,
    entryIds: entry.id === null ? {} : { [SINGLE_ENTRY_KEY]: entry.id },
    exceptionClassIds: [],
    teacherGroupId,
    courseGroupId,
    timeGroupId,
    ...invalidField([entry]),
  }
}

/** True when the stored parameter map is exactly what this requirement edits. */
function allowedKeys(
  params: Record<string, unknown>,
  spec: RuleSpec,
): boolean {
  const allowed = new Set<string>()
  if (spec.target !== 'NONE') {
    allowed.add(objectField(spec))
  }
  if (spec.target === 'TEACHER') {
    allowed.add('teacherGroupId')
  }
  if (spec.target === 'COURSE') {
    allowed.add('courseGroupId')
  }
  if (spec.periods === 'REQUIRED') {
    allowed.add('timeGroupId')
  }
  if (spec.amount) {
    allowed.add(spec.amount.field)
  }
  if (spec.day === 'REQUIRED') {
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
export function cardsFromState(
  configs: ConstraintConfigDto[],
  options: StateOptions = {},
): RuleCard[] {
  const sentencesByCard = new Map<string, RuleSentence[]>()
  const append = (sentence: RuleSentence, cardId: string) => {
    const list = sentencesByCard.get(cardId) ?? []
    list.push(sentence)
    sentencesByCard.set(cardId, list)
  }
  const mergedOptions: MergeOptions = { classIds: options.allClassIds }
  for (const config of configs) {
    if (!isManagedType(config.type)) {
      continue
    }
    if (isSwitchRule(config.type)) {
      const cardId = `switch:${config.type}`
      append({
        key: `switch:${config.type}`,
        groupKey: null,
        type: config.type,
        classId: null,
        teacherId: null,
        courseId: null,
        days: [],
        periodNos: [],
        amount: null,
        strength: RULE_SPECS[config.type].hardOnly || config.hard ? 'HARD' : 'SOFT',
        enabled: config.enabled !== false,
        entryIds: {},
        exceptionClassIds: [],
        teacherGroupId: null,
        courseGroupId: null,
        timeGroupId: null,
        ...(config.invalidReferences?.length
          ? { invalidReferences: [...config.invalidReferences] }
          : {}),
      }, cardId)
      continue
    }
    for (const group of groupEntries(config)) {
      const sentence = sentenceFromGroup(config.type, group, config, mergedOptions)
      if (!sentence) {
        continue
      }
      const cardId = cardIdOf(sentence.groupKey) ?? sentence.key
      append(sentence, cardId)
    }
  }
  return Array.from(sentencesByCard.entries())
    .map(([cardId, sentences]) => {
      const ordered = [...sentences].sort((left, right) =>
        (groupIndex(left.groupKey) ?? 0) - (groupIndex(right.groupKey) ?? 0))
      return cardOfSentences(cardId, ordered)
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
export function unrepresentedGroupCount(
  configs: ConstraintConfigDto[],
  options: StateOptions = {},
): number {
  let count = 0
  for (const config of configs) {
    const type = config.type
    if (!isManagedType(type) || isSwitchRule(type)) {
      continue
    }
    if ((config.entries ?? []).length === 0 && Object.keys(config.params ?? {}).length > 0) {
      count += 1
    }
    const merged: MergeOptions = { classIds: options.allClassIds }
    count += groupEntries(config).filter(
      (group) => !sentenceFromGroup(type, group, config, merged),
    ).length
  }
  return count
}

/** The "对谁" bucket a card belongs to, used by the list's grouping. */
export function cardTarget(card: RuleCard): RuleTargetGroup {
  const targets = new Set<RuleTargetGroup>()
  for (const sentence of card.sentences) {
    const rule = RULE_SPECS[sentence.type]
    if (rule.target === 'TEACHER') {
      targets.add('TEACHER')
    } else if (rule.target === 'CLASS') {
      targets.add(sentence.classId === null ? 'SCHOOL' : 'CLASS')
    } else if (rule.target === 'COURSE') {
      targets.add('COURSE')
    } else {
      targets.add('SCHOOL')
    }
  }
  if (targets.has('TEACHER')) return 'TEACHER'
  if (targets.has('CLASS')) return 'CLASS'
  if (targets.has('COURSE')) return 'COURSE'
  return 'SCHOOL'
}

export const TARGET_LABELS: Record<RuleTargetGroup, string> = {
  SCHOOL: '全校',
  CLASS: '班级',
  TEACHER: '教师',
  COURSE: '课程',
}

/** The list's free-text filter: object names, sentence wording and the bucket name. */
export function cardMatches(
  card: RuleCard,
  query: string,
  names: RuleNames,
  periods?: PeriodOption[] | null,
): boolean {
  const text = query.trim().toLowerCase()
  if (text === '') {
    return true
  }
  const haystack = [
    TARGET_LABELS[cardTarget(card)],
    ...card.sentences.map((sentence) => describeSentence(sentence, names, periods)),
  ].join(' ').toLowerCase()
  return haystack.includes(text)
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
