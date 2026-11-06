/**
 * Ticket 03b: turns the model's natural-language proposals into the rule page's
 * cards. The output is exactly what the page writes by hand — `RuleCard`s that
 * `configsFromCards` compiles into `constraint_entry` — so a suggestion and a typed
 * rule end up as the same rows.
 *
 * <p>Everything here is pure: the page owns HTTP, the panel owns generation.
 */
import type { ConstraintConfigDto, ConstraintEntryDto } from '@/components/constraints/types'
import type { ConstraintProposal, ProposalEntry } from '@/components/constraints/useConstraintAi'
import {
  RULE_SPECS,
  RULE_TYPES,
  cardsFromState,
  isSwitchRule,
  newCardId,
  type RuleCard,
  type RuleType,
} from './compiler'

/** The decision the save records for one added proposal (mirrors the server DTO). */
export interface ProposalDecision {
  proposalId: string
  action: 'ADDED'
  type: string
  reason: string
  entries: Record<string, unknown>[]
}

/** One proposal that could not become a card, with the reason shown to the user. */
export interface ProposalRejection {
  proposalId: string
  typeName: string
  reason: string
}

export interface ProposalConversion {
  /** New, unsaved cards: fresh `cardId`/`groupKey`, no stored row ids. */
  cards: RuleCard[]
  /** One entry per successful proposal, even when it produced several cards. */
  decisions: ProposalDecision[]
  rejected: ProposalRejection[]
}

const RULE_TYPE_SET = new Set<string>(RULE_TYPES)

type ObjectField = 'teacherId' | 'courseId' | 'classId'

function objectFieldOf(type: RuleType): ObjectField | null {
  switch (RULE_SPECS[type].target) {
    case 'TEACHER':
      return 'teacherId'
    case 'COURSE':
      return 'courseId'
    case 'CLASS':
      return 'classId'
    default:
      return null
  }
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

function withoutClassId(params: Record<string, unknown>): Record<string, unknown> {
  const copy = { ...params }
  delete copy.classId
  return copy
}

/**
 * One group = one sentence = one card. Entries inside a group share a single stored
 * group key, so the compiler reads the whole group as one requirement.
 */
interface EntryGroup {
  entries: ProposalEntry[]
}

function reject(proposal: ConstraintProposal, reason: string): ProposalRejection {
  return { proposalId: proposal.proposalId, typeName: proposal.typeName, reason }
}

/**
 * "全校 … 除了某班" is representable only when every entry names a class or none
 * does. A mixed set would silently widen a class rule to the whole school.
 */
function classGroups(
  proposal: ConstraintProposal,
  allClassIds: number[],
): EntryGroup[] | ProposalRejection {
  const ids = proposal.entries.map((entry) => toInteger(entry.params.classId))
  const named = ids.filter((id): id is number => id !== null)
  if (named.length === 0) {
    return [{ entries: proposal.entries }]
  }
  if (named.length !== ids.length) {
    return reject(proposal, '这条建议同时包含全校和指定班级，无法安全表达')
  }
  const distinct = new Set(named)
  if (distinct.size === 1) {
    return [{ entries: proposal.entries }]
  }
  const coversEveryClass = allClassIds.length > 0
    && distinct.size === allClassIds.length
    && allClassIds.every((id) => distinct.has(id))
  if (!coversEveryClass) {
    // A partial class list is a school rule "every class except these"; leaving the
    // class ids in place lets the compiler infer the exception.
    return [{ entries: proposal.entries }]
  }
  // Covering every class is the same requirement as the school-wide sentence.
  return [{
    entries: proposal.entries.map((entry) => ({
      ...entry,
      params: withoutClassId(entry.params),
    })),
  }]
}

function groupsOf(proposal: ConstraintProposal, allClassIds: number[]): EntryGroup[] | ProposalRejection {
  const type = proposal.type as RuleType
  const spec = RULE_SPECS[type]
  if (isSwitchRule(type)) {
    return [{ entries: [] }]
  }
  if (proposal.entries.length === 0) {
    return reject(proposal, `「${proposal.typeName}」这条建议没有可转换的条件`)
  }
  if (type === 'CLASS_UNAVAILABLE') {
    return classGroups(proposal, allClassIds)
  }
  if (spec.periods === 'REQUIRED' || spec.day === 'REQUIRED') {
    const field = objectFieldOf(type)
    const groups = new Map<string, EntryGroup>()
    for (const entry of proposal.entries) {
      const value = field ? toInteger(entry.params[field]) : null
      const identity = value === null ? 'none' : String(value)
      const group = groups.get(identity)
      if (group) {
        group.entries.push(entry)
      } else {
        groups.set(identity, { entries: [entry] })
      }
    }
    return Array.from(groups.values())
  }
  // Quantity/shape/preset requirements are one value each: one card per entry.
  return proposal.entries.map((entry) => ({ entries: [entry] }))
}

function configOf(
  proposal: ConstraintProposal,
  group: EntryGroup,
  groupIndex: number,
): ConstraintConfigDto {
  const type = proposal.type as RuleType
  const groupKey = `${proposal.proposalId}:${groupIndex}`
  const entries: ConstraintEntryDto[] = group.entries.map((entry) => ({
    id: null,
    params: { ...entry.params },
    enabled: entry.enabled ?? true,
    hard: entry.hard ?? null,
    weight: entry.weight ?? null,
    groupKey,
  }))
  return {
    type,
    params: {},
    weight: proposal.effectiveWeight ?? RULE_SPECS[type].defaultWeight,
    hard: proposal.effectiveHard,
    enabled: true,
    entries,
  }
}

/** A fresh, unsaved copy: new card id, new sentence groups, no stored row ids. */
function adoptCard(card: RuleCard): RuleCard {
  const cardId = newCardId()
  return {
    key: `card:${cardId}`,
    cardId,
    enabled: card.enabled,
    sentences: card.sentences.map((sentence, index) => ({
      ...sentence,
      key: `group:${cardId}:${index}`,
      groupKey: `${cardId}:${index}`,
      entryIds: {},
      days: [...sentence.days],
      periodNos: [...sentence.periodNos],
      exceptionClassIds: [...sentence.exceptionClassIds],
    })),
  }
}

/**
 * Converts the selected proposals into unsaved rule cards. Proposals that cannot be
 * expressed by the sentence model are reported with the concrete reason instead of
 * being dropped silently.
 */
export function cardsFromProposal(
  proposals: ConstraintProposal[],
  options: { allClassIds: number[] },
): ProposalConversion {
  const cards: RuleCard[] = []
  const decisions: ProposalDecision[] = []
  const rejected: ProposalRejection[] = []

  for (const proposal of proposals) {
    if (proposal.status !== 'READY') {
      rejected.push(reject(proposal, proposal.reason || '建议未就绪'))
      continue
    }
    if (!RULE_TYPE_SET.has(proposal.type)) {
      rejected.push(reject(proposal, `「${proposal.typeName}」暂不支持在规则页添加`))
      continue
    }
    const groups = groupsOf(proposal, options.allClassIds)
    if (!Array.isArray(groups)) {
      rejected.push(groups)
      continue
    }
    const proposalCards: RuleCard[] = []
    groups.forEach((group, index) => {
      const read = cardsFromState([configOf(proposal, group, index)], {
        allClassIds: options.allClassIds,
      })
      if (read.length === 0) {
        rejected.push(reject(proposal, `「${proposal.typeName}」这条建议在规则页无法表达`))
        return
      }
      proposalCards.push(...read.map(adoptCard))
    })
    if (proposalCards.length === 0) {
      continue
    }
    cards.push(...proposalCards)
    decisions.push({
      proposalId: proposal.proposalId,
      action: 'ADDED',
      type: proposal.type,
      reason: proposal.reason || proposal.explanation || '',
      // The original rows, not the rewritten card groups: the audit names what the
      // model suggested and the user confirmed.
      entries: proposal.entries.map((entry) => ({ ...entry.params })),
    })
  }

  return { cards, decisions, rejected }
}
