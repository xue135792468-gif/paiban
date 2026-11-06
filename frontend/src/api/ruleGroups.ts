import { http } from '@/api/http'

/** Ticket 04: teacher / course / time groups. The rule page uses teacher groups. */
export type RuleGroupType = 'TEACHER' | 'COURSE' | 'TIME'

export interface RuleGroupMember {
  id: number
  kind: RuleGroupType
  objectId: number
  name: string
  dayOfWeek: number | null
  periodNo: number | null
}

export interface RuleGroup {
  id: number
  type: RuleGroupType
  name: string
  semesterId: number | null
  members: RuleGroupMember[]
}

export interface RuleGroupMemberRequest {
  teacherId?: number | null
  courseId?: number | null
  dayOfWeek?: number | null
  periodNo?: number | null
}

export interface RuleGroupRequest {
  type: RuleGroupType
  name: string
  semesterId?: number | null
  members: RuleGroupMemberRequest[]
}

/** One rule card that references a group; `groupKey` is the card key. */
export interface RuleGroupReference {
  configId: number | null
  semesterId: number
  semesterName: string
  type: string
  typeName: string
  groupKey: string | null
  entryIds: number[]
}

export interface RuleGroupImpact {
  id: number
  name: string
  type: RuleGroupType
  rules: RuleGroupReference[]
  message: string
}

export type RuleGroupDeleteMode = 'DELETE' | 'WITH_RULES'

export interface RuleGroupDeleteResult {
  batchIds: string[]
  deletedRules: number
}

export async function getRuleGroups(
  type: RuleGroupType,
  semesterId?: number | null,
): Promise<RuleGroup[]> {
  const { data } = await http.get<RuleGroup[]>('/rule-groups', {
    params: { type, ...(semesterId === null || semesterId === undefined ? {} : { semesterId }) },
  })
  return data ?? []
}

export async function createRuleGroup(request: RuleGroupRequest): Promise<RuleGroup> {
  const { data } = await http.post<RuleGroup>('/rule-groups', request)
  return data
}

export async function updateRuleGroup(
  id: number,
  request: RuleGroupRequest,
): Promise<RuleGroup> {
  const { data } = await http.put<RuleGroup>(`/rule-groups/${id}`, request)
  return data
}

export async function getRuleGroupImpact(id: number): Promise<RuleGroupImpact> {
  const { data } = await http.get<RuleGroupImpact>(`/rule-groups/${id}/delete-impact`)
  return data
}

export async function deleteRuleGroup(
  id: number,
  mode: RuleGroupDeleteMode,
): Promise<RuleGroupDeleteResult> {
  const { data } = await http.delete<RuleGroupDeleteResult>(`/rule-groups/${id}`, {
    params: { mode },
  })
  return data
}
