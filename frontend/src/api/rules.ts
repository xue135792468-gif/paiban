import { http } from '@/api/http'
import type { ConstraintConfigRequest } from '@/components/constraints/types'

export interface RuleImpactObject {
  id: number
  name: string
}

export interface RuleImpactSlot {
  dayOfWeek: number
  periodNo: number
}

export interface RuleImpactConflict {
  lessonId?: number | null
  type: string
  message: string
}

/**
 * `POST /api/rules/impact`: what a rule card touches. The server merges the card
 * onto the saved configuration and runs the shared constraint checker; the page
 * only renders the answer.
 */
export interface RuleImpact {
  classes: RuleImpactObject[]
  teachers: RuleImpactObject[]
  slots: RuleImpactSlot[]
  lessonCount: number
  conflicts: RuleImpactConflict[]
  errors: string[]
  warnings: string[]
}

export async function fetchRuleImpact(
  semesterId: number,
  configs: ConstraintConfigRequest[],
): Promise<RuleImpact> {
  const { data } = await http.post<RuleImpact>('/rules/impact', { semesterId, configs })
  return data
}

/** One copied card the target semester cannot resolve; `groupKey` is the new card key. */
export interface CopyRuleIssue {
  type: string
  typeName: string
  groupKey: string
  reason: string
}

/** Ticket 08: every copied card, plus the unusable ones and why. */
export interface CopyRulesResult {
  copiedCards: number
  disabledCards: number
  issues: CopyRuleIssue[]
}

export interface CopyRulesRequest {
  sourceSemesterId: number
  targetSemesterId: number
  baseVersion?: number
}

/**
 * Replaces the target semester's non-system rules with a past semester's whole set.
 * The two system baselines stay with the target; the server returns which copied
 * cards had to be paused because a reference no longer resolves.
 */
export async function copyRules(request: CopyRulesRequest): Promise<CopyRulesResult> {
  const { data } = await http.post<CopyRulesResult>('/constraints/copy-rules', request)
  return data
}
