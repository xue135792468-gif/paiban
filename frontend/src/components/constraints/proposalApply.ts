import { SCALAR_SPECS } from './entryChecks'
import { plannedEntries } from './entryModel'
import type { ConstraintProposal } from './useConstraintAi'
import type { EntryDraft, TypeDraft } from './types'

/**
 * Pure comparison helpers for "添加到约束表" (ticket 14/10). They never mutate the
 * draft: the composable asks them what the proposal would collide with, shows the
 * user the difference, and only then applies a confirmed decision.
 */

export interface ProposalControls {
  hard: boolean | null
  weight: number | null
  enabled: boolean
}

export function controlsOf(proposal: ConstraintProposal): ProposalControls {
  return {
    hard: proposal.strength === 'HARD' ? true : proposal.strength === 'SOFT' ? false : null,
    // No invented weight: an inherited value stays inherited.
    weight: null,
    enabled: true,
  }
}

/** Sorted `key=value` list of one entry's parameters, numeric width normalized. */
export function canonical(params: Record<string, unknown>): string[] {
  return Object.keys(params)
    .sort()
    .map((key) => {
      const value = params[key]
      const normalized = typeof value === 'number' ? Math.trunc(value) : value
      return `${key}=${String(normalized)}`
    })
}

function sameSet(left: string[][], right: string[][]): boolean {
  if (left.length !== right.length) {
    return false
  }
  const key = (entry: string[]) => entry.join('|')
  const leftSet = new Set(left.map(key))
  return right.every((entry) => leftSet.has(key(entry)))
}

export function proposalEntrySets(proposal: ConstraintProposal): string[][] {
  return proposal.entries.map((entry) => canonical(entry.params))
}

export interface CardMatch {
  cardIndex: number
  card: EntryDraft
  sameConditions: boolean
}

/** The card whose stored conditions are exactly the proposal's conditions. */
export function findSameConditions(
  row: TypeDraft,
  proposal: ConstraintProposal,
): CardMatch | null {
  const wanted = proposalEntrySets(proposal)
  const planned = plannedEntries(row)
  for (let index = 0; index < row.entries.length; index++) {
    const actual = planned
      .filter((entry) => entry.cardIndex === index && entry.complete)
      .map((entry) => canonical(entry.params))
    if (sameSet(actual, wanted)) {
      return { cardIndex: index, card: row.entries[index], sameConditions: true }
    }
  }
  return null
}

function cardControls(card: EntryDraft, row: TypeDraft): ProposalControls {
  return {
    hard: card.hard ?? row.hard,
    weight: card.weight ?? row.weight,
    enabled: card.enabled ?? true,
  }
}

export function sameControls(
  card: EntryDraft,
  row: TypeDraft,
  proposal: ConstraintProposal,
): boolean {
  const actual = cardControls(card, row)
  const wanted = controlsOf(proposal)
  const actualHard = actual.hard ?? row.hard
  const wantedHard = wanted.hard ?? row.hard
  return actual.enabled === wanted.enabled
    && actualHard === wantedHard
    && (actualHard || actual.weight === (wanted.weight ?? row.weight))
}

/**
 * A scalar type (room type, consecutive length, morning cut-off) already configured
 * for the same object with another value: a difference to show, never a silent
 * overwrite.
 */
export function scalarConflict(
  row: TypeDraft,
  proposal: ConstraintProposal,
): { card: EntryDraft; message: string } | null {
  const spec = SCALAR_SPECS[row.code]
  if (!spec || proposal.entries.length !== 1) {
    return null
  }
  const params = proposal.entries[0].params
  const objectId = params[spec.object]
  if (objectId === null || objectId === undefined) {
    return null
  }
  const field = spec.scalars[0]
  const value = params[field]
  for (const [index, card] of row.entries.entries()) {
    const planned = plannedEntries(row).filter((entry) => entry.cardIndex === index)
    const existing = planned.find((entry) => String(entry.params[spec.object]) === String(objectId))
    if (!existing || existing.params[field] === value) {
      continue
    }
    return {
      card,
      message: `「${row.name}」同一目标已配置「${existing.params[field]}」，`
        + `新建议是「${value}」；默认保留旧值，确认后才替换。`,
    }
  }
  return null
}

export interface ThresholdScope {
  objectField: string
  valueField: string
  objectId: number | null
  value: number | null
}

export function proposalThreshold(row: TypeDraft, proposal: ConstraintProposal): ThresholdScope | null {
  const objectField = row.thresholdObjectField
  const valueField = row.thresholdValueField
  if (!objectField || !valueField || proposal.entries.length !== 1) {
    return null
  }
  const params = proposal.entries[0].params
  const objectId = params[objectField] === null || params[objectField] === undefined
    ? null : Number(params[objectField])
  const value = params[valueField] === null || params[valueField] === undefined
    ? null : Number(params[valueField])
  return { objectField, valueField, objectId, value }
}

/**
 * A threshold exception that loosens the global limit (larger number or hard → soft)
 * must be confirmed; returns the sentence to show, or null.
 */
export function thresholdRelaxation(
  row: TypeDraft,
  proposal: ConstraintProposal,
  objectName: string,
): string | null {
  const scope = proposalThreshold(row, proposal)
  const draft = row.threshold
  if (!scope || !draft || scope.objectId === null || scope.value === null
    || draft.globalValue === null) {
    return null
  }
  const globalHard = draft.globalHard ?? row.hard
  const hard = controlsOf(proposal).hard ?? row.hard
  const looser = scope.value > Number(draft.globalValue) || (globalHard && !hard)
  if (!looser) {
    return null
  }
  return `「${row.name}」：${objectName} 放宽为最多 ${scope.value} 节（${hard ? '必须' : '尽量'}），`
    + `不再按全局默认最多 ${draft.globalValue} 节（${globalHard ? '必须' : '尽量'}）执行。`
}
