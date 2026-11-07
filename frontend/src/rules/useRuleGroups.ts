import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import {
  createRuleGroup,
  deleteRuleGroup,
  getRuleGroupImpact,
  getRuleGroups,
  updateRuleGroup,
  type RuleGroup,
  type RuleGroupDeleteResult,
  type RuleGroupImpact,
  type RuleGroupMemberRequest,
  type RuleGroupType,
} from '@/api/ruleGroups'

/** Ticket 04/05: the two global group families the rule page edits. */
export type RuleGroupKind = Extract<RuleGroupType, 'TEACHER' | 'COURSE'>

/** The handle one group family exposes to the page and the panels component. */
export interface UseRuleGroups {
  groups: Ref<RuleGroup[]>
  options: ComputedRef<Array<{ id: number; name: string; memberIds: number[] }>>
  groupNames: ComputedRef<Map<number, string>>
  loading: Ref<boolean>
  error: Ref<string | null>
  groupsOf: (objectId: number) => RuleGroup[]
  load: () => Promise<void>
  create: (name: string) => Promise<{ id: number; name: string } | null>
  update: (id: number, name: string, memberIds: number[]) => Promise<boolean>
  remove: (id: number, withRules: boolean) => Promise<RuleGroupDeleteResult | null>
  impact: (id: number) => Promise<RuleGroupImpact | null>
}

/**
 * One load feeds the management panel, the subject dropdown and the sentence
 * names, so a rename or member edit reaches every sentence without another
 * `/constraints/state` round trip.
 *
 * The composable is generic over the group type: the API shape is identical, only
 * the member key (`teacherId` / `courseId`) and the wording differ.
 */
export function useRuleGroups(type: RuleGroupKind): UseRuleGroups {
  const groups = ref<RuleGroup[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  const label = type === 'TEACHER' ? '教师' : '课程'

  const groupNames = computed(() => {
    const names = new Map<number, string>()
    for (const group of groups.value) {
      names.set(group.id, group.name)
    }
    return names
  })

  const options = computed(() => groups.value.map((group) => ({
    id: group.id,
    name: group.name,
    memberIds: group.members
      .filter((member) => member.kind === type)
      .map((member) => member.objectId),
  })))

  function groupsOf(objectId: number): RuleGroup[] {
    return groups.value.filter((group) =>
      group.members.some((member) => member.kind === type && member.objectId === objectId))
  }

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      groups.value = await getRuleGroups(type)
    } catch (e: unknown) {
      groups.value = []
      error.value = serverMessage(e, `加载${label}分组失败`)
    } finally {
      loading.value = false
    }
  }

  function membersOf(ids: number[]): RuleGroupMemberRequest[] {
    return [...new Set(ids)].map((id) => (
      type === 'TEACHER' ? { teacherId: id } : { courseId: id }
    ))
  }

  /** Used by the subject dropdown: creates an empty group and returns it to select. */
  async function create(name: string): Promise<{ id: number; name: string } | null> {
    try {
      const created = await createRuleGroup({ type, name, members: [] })
      await load()
      return { id: created.id, name: created.name }
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, `创建${label}分组失败`))
      return null
    }
  }

  async function update(
    id: number,
    name: string,
    memberIds: number[],
  ): Promise<boolean> {
    try {
      await updateRuleGroup(id, {
        type,
        name,
        members: membersOf(memberIds),
      })
      await load()
      return true
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, `保存${label}分组失败`))
      return false
    }
  }

  async function remove(
    id: number,
    withRules: boolean,
  ): Promise<RuleGroupDeleteResult | null> {
    try {
      const result = await deleteRuleGroup(id, withRules ? 'WITH_RULES' : 'DELETE')
      await load()
      return result
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, `删除${label}分组失败`))
      return null
    }
  }

  async function impact(id: number): Promise<RuleGroupImpact | null> {
    try {
      return await getRuleGroupImpact(id)
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, '加载分组影响失败'))
      return null
    }
  }

  return {
    groups,
    options,
    groupNames,
    loading,
    error,
    groupsOf,
    load,
    create,
    update,
    remove,
    impact,
  }
}
