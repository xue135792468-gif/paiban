/**
 * Ticket 07: the save-time conflict reminder. Two hard rules that name the same
 * teacher, course or class and the same timetable slot, one saying "do not
 * schedule here" and the other "schedule here", leave that object with no
 * acceptable answer. The page expands the user groups and asks this module;
 * nothing here blocks the save and nothing here changes data.
 *
 * <p>Only the six negative and two positive requirement types participate. A soft
 * ("尽量") rule never conflicts: the solver is allowed to trade it away.
 */
import {
  CLASS_UNAVAILABLE,
  COURSE_UNAVAILABLE,
  MAIN_SUBJECT_MORNING,
  PE_LAST_PERIOD_AVOID,
  RULE_SPECS,
  TEACHER_DAY_OFF,
  TEACHER_NO_FIRST_PERIOD,
  TEACHER_PREFERRED_SLOT,
  TEACHER_UNAVAILABLE,
  describeSentence,
  slotKey,
  weekdayLabel,
  type PeriodOption,
  type RuleCard,
  type RuleNames,
  type RuleSentence,
  type RuleType,
} from './compiler'

type ObjectKind = 'TEACHER' | 'COURSE' | 'CLASS'

export interface RuleConflictMember {
  kind: ObjectKind
  id: number
  name: string
}

export interface RuleConflictSide {
  /** The card the sentence lives on; several sentences share one card. */
  cardKey: string
  sentence: string
  type: RuleType
}

export interface RuleConflict {
  /** The "不排" sentence. */
  first: RuleConflictSide
  /** The "排在" sentence. */
  second: RuleConflictSide
  /** The concrete members behind the groups that actually overlap. */
  members: RuleConflictMember[]
  /** "周一第1节", deduplicated and sorted by day, then period. */
  slots: string[]
}

export interface ConflictContext {
  periods: PeriodOption[]
  names: RuleNames
  teacherGroupMembers: Map<number, number[]>
  courseGroupMembers: Map<number, number[]>
  timeGroupSlots: Map<number, Array<{ dayOfWeek: number; periodNo: number }>>
  allClassIds: number[]
  allCourseIds: number[]
}

/** The structural slice of a rule group the context builder needs (keeps this file HTTP-free). */
export interface ConflictGroupSource {
  id: number
  members: Array<{
    kind: string
    objectId: number
    dayOfWeek: number | null
    periodNo: number | null
  }>
}

export interface ConflictContextSource {
  periods: PeriodOption[]
  names: RuleNames
  allClassIds: number[]
  allCourseIds: number[]
  teacherGroups: ConflictGroupSource[]
  courseGroups: ConflictGroupSource[]
  timeGroups: ConflictGroupSource[]
}

/** Turns the loaded group lists into the plain maps `findRuleConflicts` reads. */
export function buildConflictContext(source: ConflictContextSource): ConflictContext {
  const teacherGroupMembers = new Map<number, number[]>()
  for (const group of source.teacherGroups) {
    teacherGroupMembers.set(
      group.id,
      group.members.filter((member) => member.kind === 'TEACHER')
        .map((member) => member.objectId),
    )
  }
  const courseGroupMembers = new Map<number, number[]>()
  for (const group of source.courseGroups) {
    courseGroupMembers.set(
      group.id,
      group.members.filter((member) => member.kind === 'COURSE')
        .map((member) => member.objectId),
    )
  }
  const timeGroupSlots = new Map<number, Array<{ dayOfWeek: number; periodNo: number }>>()
  for (const group of source.timeGroups) {
    timeGroupSlots.set(
      group.id,
      group.members
        .filter((member) => member.kind === 'TIME'
          && member.dayOfWeek !== null && member.periodNo !== null)
        .map((member) => ({
          dayOfWeek: member.dayOfWeek as number,
          periodNo: member.periodNo as number,
        })),
    )
  }
  return {
    periods: source.periods,
    names: source.names,
    teacherGroupMembers,
    courseGroupMembers,
    timeGroupSlots,
    allClassIds: source.allClassIds,
    allCourseIds: source.allCourseIds,
  }
}

const NEGATIVE_TYPES = new Set<RuleType>([
  CLASS_UNAVAILABLE,
  TEACHER_UNAVAILABLE,
  COURSE_UNAVAILABLE,
  TEACHER_NO_FIRST_PERIOD,
  TEACHER_DAY_OFF,
  PE_LAST_PERIOD_AVOID,
])

const POSITIVE_TYPES = new Set<RuleType>([
  TEACHER_PREFERRED_SLOT,
  MAIN_SUBJECT_MORNING,
])

interface Slot {
  dayOfWeek: number
  periodNo: number
}

interface TargetObject {
  kind: ObjectKind
  id: number
}

interface SideEntry {
  cardKey: string
  sentence: RuleSentence
  member: RuleConflictMember
}

interface ConflictDraft {
  first: RuleConflictSide
  second: RuleConflictSide
  members: RuleConflictMember[]
  memberKeys: Set<string>
  slots: Map<string, Slot>
}

/**
 * Every negative/positive overlap, grouped by sentence pair. `cards` is the page's
 * current state (disabled cards and sentences are ignored) and `context` is the
 * group expansion built from the loaded groups.
 */
export function findRuleConflicts(cards: RuleCard[], context: ConflictContext): RuleConflict[] {
  const negative = collectSide(cards, context, NEGATIVE_TYPES)
  const positive = collectSide(cards, context, POSITIVE_TYPES)
  const drafts = new Map<string, ConflictDraft>()
  for (const [key, negatives] of negative) {
    const positives = positive.get(key)
    if (!positives) {
      continue
    }
    const slot = parseSlot(key)
    for (const neg of negatives) {
      for (const pos of positives) {
        const pairKey = `${neg.sentence.key}|${pos.sentence.key}`
        let draft = drafts.get(pairKey)
        if (!draft) {
          draft = {
            first: sideOf(neg, context),
            second: sideOf(pos, context),
            members: [],
            memberKeys: new Set(),
            slots: new Map(),
          }
          drafts.set(pairKey, draft)
        }
        addMember(draft, neg.member)
        addMember(draft, pos.member)
        // Keyed by time, not by object: one slot shared by five teachers is still one slot.
        draft.slots.set(`${slot.dayOfWeek}|${slot.periodNo}`, slot)
      }
    }
  }
  return Array.from(drafts.values()).map((draft) => ({
    first: draft.first,
    second: draft.second,
    members: draft.members,
    slots: Array.from(draft.slots.entries())
      .sort(([, left], [, right]) =>
        left.dayOfWeek - right.dayOfWeek || left.periodNo - right.periodNo)
      .map(([, slot]) => `${weekdayLabel(slot.dayOfWeek)}第${slot.periodNo}节`),
  }))
}

/** The dialog body: one block per conflict, at most `limit` blocks plus a count. */
export function formatConflictMessage(conflicts: RuleConflict[], limit = 5): string {
  const blocks = conflicts.slice(0, limit).map((conflict) => [
    `「${conflict.first.sentence}」`,
    '与',
    `「${conflict.second.sentence}」`,
    `受影响：${conflict.members.map((member) => member.name).join('、')}`,
    `时段：${formatSlots(conflict.slots)}`,
  ].join('\n'))
  if (conflicts.length > limit) {
    blocks.push(`等 ${conflicts.length - limit} 条`)
  }
  return blocks.join('\n\n')
}

function collectSide(
  cards: RuleCard[],
  context: ConflictContext,
  types: Set<RuleType>,
): Map<string, SideEntry[]> {
  const collected = new Map<string, SideEntry[]>()
  for (const card of cards) {
    if (!card.enabled) {
      continue
    }
    for (const sentence of card.sentences) {
      if (!sentence.enabled || !types.has(sentence.type) || !isHard(sentence)) {
        continue
      }
      const objects = objectsOf(sentence, context)
      if (objects.length === 0) {
        continue
      }
      const slots = slotsOf(sentence, context)
      if (slots.length === 0) {
        continue
      }
      for (const object of objects) {
        const member = memberOf(object, context.names)
        for (const slot of slots) {
          const key = `${object.kind}:${object.id}|${slot.dayOfWeek}|${slot.periodNo}`
          const list = collected.get(key) ?? []
          list.push({ cardKey: card.key, sentence, member })
          collected.set(key, list)
        }
      }
    }
  }
  return collected
}

/** `hardOnly` types are hard even when stored data says SOFT. */
function isHard(sentence: RuleSentence): boolean {
  return RULE_SPECS[sentence.type].hardOnly || sentence.strength === 'HARD'
}

/** The concrete teachers / courses / classes a sentence covers. */
function objectsOf(sentence: RuleSentence, context: ConflictContext): TargetObject[] {
  if (sentence.type === CLASS_UNAVAILABLE) {
    if (sentence.classId !== null) {
      return [target('CLASS', sentence.classId)]
    }
    const exceptions = new Set(sentence.exceptionClassIds)
    return context.allClassIds
      .filter((id) => !exceptions.has(id))
      .map((id) => target('CLASS', id))
  }
  const spec = RULE_SPECS[sentence.type]
  if (spec.target === 'TEACHER') {
    if (sentence.teacherId !== null) {
      return [target('TEACHER', sentence.teacherId)]
    }
    if (sentence.teacherGroupId !== null) {
      return (context.teacherGroupMembers.get(sentence.teacherGroupId) ?? [])
        .map((id) => target('TEACHER', id))
    }
    return []
  }
  if (spec.target === 'COURSE') {
    if (sentence.courseId !== null) {
      return [target('COURSE', sentence.courseId)]
    }
    if (sentence.courseGroupId !== null) {
      return (context.courseGroupMembers.get(sentence.courseGroupId) ?? [])
        .map((id) => target('COURSE', id))
    }
    // No course and no group means 全部课程 (for example 体育不在最后一节 left blank).
    return context.allCourseIds.map((id) => target('COURSE', id))
  }
  return []
}

/** The timetable coordinates a sentence covers, narrowed to this semester's periods. */
function slotsOf(sentence: RuleSentence, context: ConflictContext): Slot[] {
  const raw = rawSlotsOf(sentence, context)
  if (raw.length === 0) {
    return []
  }
  const available = new Set(
    context.periods.map((period) => slotKey(period.dayOfWeek, period.periodNo)),
  )
  return raw.filter((slot) => available.has(slotKey(slot.dayOfWeek, slot.periodNo)))
}

function rawSlotsOf(sentence: RuleSentence, context: ConflictContext): Slot[] {
  switch (sentence.type) {
    case TEACHER_NO_FIRST_PERIOD:
      return firstPeriods(context.periods)
    case TEACHER_DAY_OFF: {
      const days = new Set(sentence.days)
      return context.periods
        .filter((period) => days.has(period.dayOfWeek))
        .map((period) => slotOf(period))
    }
    case PE_LAST_PERIOD_AVOID:
      return lastPeriods(context.periods)
    case MAIN_SUBJECT_MORNING: {
      const limit = sentence.amount
      if (limit === null) {
        return []
      }
      return context.periods
        .filter((period) => period.periodNo <= limit)
        .map((period) => slotOf(period))
    }
    default: {
      if (sentence.timeGroupId !== null) {
        return context.timeGroupSlots.get(sentence.timeGroupId) ?? []
      }
      return sentence.days.flatMap((day) =>
        sentence.periodNos.map((periodNo) => ({ dayOfWeek: day, periodNo })))
    }
  }
}

function firstPeriods(periods: PeriodOption[]): Slot[] {
  const first = new Map<number, number>()
  for (const period of periods) {
    const current = first.get(period.dayOfWeek)
    if (current === undefined || period.periodNo < current) {
      first.set(period.dayOfWeek, period.periodNo)
    }
  }
  return Array.from(first.entries())
    .sort(([left], [right]) => left - right)
    .map(([dayOfWeek, periodNo]) => ({ dayOfWeek, periodNo }))
}

function lastPeriods(periods: PeriodOption[]): Slot[] {
  const last = new Map<number, number>()
  for (const period of periods) {
    const current = last.get(period.dayOfWeek)
    if (current === undefined || period.periodNo > current) {
      last.set(period.dayOfWeek, period.periodNo)
    }
  }
  return Array.from(last.entries())
    .sort(([left], [right]) => left - right)
    .map(([dayOfWeek, periodNo]) => ({ dayOfWeek, periodNo }))
}

function target(kind: ObjectKind, id: number): TargetObject {
  return { kind, id }
}

function slotOf(period: PeriodOption): Slot {
  return { dayOfWeek: period.dayOfWeek, periodNo: period.periodNo }
}

function memberOf(object: TargetObject, names: RuleNames): RuleConflictMember {
  let name: string
  if (object.kind === 'TEACHER') {
    name = names.teacher(object.id)
  } else if (object.kind === 'COURSE') {
    name = names.course(object.id)
  } else {
    name = names.klass(object.id)
  }
  return { kind: object.kind, id: object.id, name }
}

function sideOf(entry: SideEntry, context: ConflictContext): RuleConflictSide {
  return {
    cardKey: entry.cardKey,
    sentence: describeSentence(entry.sentence, context.names, context.periods),
    type: entry.sentence.type,
  }
}

function parseSlot(key: string): Slot {
  const [, day, periodNo] = key.split('|')
  return { dayOfWeek: Number(day), periodNo: Number(periodNo) }
}

function addMember(draft: ConflictDraft, member: RuleConflictMember): void {
  const key = `${member.kind}:${member.id}`
  if (draft.memberKeys.has(key)) {
    return
  }
  draft.memberKeys.add(key)
  draft.members.push(member)
}

/** "周五第5节、周五第6节" reads better folded into "周五第5、6节". */
function formatSlots(slots: string[]): string {
  const order: string[] = []
  const periods = new Map<string, number[]>()
  for (const slot of slots) {
    const match = /^(.+?)第(\d+)节$/.exec(slot)
    if (!match) {
      continue
    }
    const day = match[1]
    const periodNo = Number(match[2])
    const list = periods.get(day)
    if (list) {
      list.push(periodNo)
    } else {
      order.push(day)
      periods.set(day, [periodNo])
    }
  }
  if (order.length === 0) {
    return slots.join('、')
  }
  return order.map((day) => `${day}第${(periods.get(day) as number[]).join('、')}节`).join('；')
}
