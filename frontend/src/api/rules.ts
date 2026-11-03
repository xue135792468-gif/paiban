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
