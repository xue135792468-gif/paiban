import { computed, ref } from 'vue'
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
} from '@/api/ruleGroups'

/**
 * Ticket 04: the rule page's teacher groups. One load feeds the management panel,
 * the subject dropdown and the sentence names, so a rename or member edit reaches
 * every sentence without another `/constraints/state` round trip.
 */
export function useRuleGroups() {
  const groups = ref<RuleGroup[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  const groupNames = computed(() => {
    const names = new Map<number, string>()
    for (const group of groups.value) {
      names.set(group.id, group.name)
    }
    return names
  })

  const teacherGroups = computed(() => groups.value.map((group) => ({
    id: group.id,
    name: group.name,
  })))

  function groupsOfTeacher(teacherId: number): RuleGroup[] {
    return groups.value.filter((group) =>
      group.members.some((member) => member.kind === 'TEACHER' && member.objectId === teacherId))
  }

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      groups.value = await getRuleGroups('TEACHER')
    } catch (e: unknown) {
      groups.value = []
      error.value = serverMessage(e, '加载教师分组失败')
    } finally {
      loading.value = false
    }
  }

  function membersOf(teacherIds: number[]): RuleGroupMemberRequest[] {
    return [...new Set(teacherIds)].map((teacherId) => ({ teacherId }))
  }

  /** Used by the subject dropdown: creates an empty group and returns it to select. */
  async function createTeacher(name: string): Promise<{ id: number; name: string } | null> {
    try {
      const created = await createRuleGroup({ type: 'TEACHER', name, members: [] })
      await load()
      return { id: created.id, name: created.name }
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, '创建教师分组失败'))
      return null
    }
  }

  async function update(
    id: number,
    name: string,
    teacherIds: number[],
  ): Promise<boolean> {
    try {
      await updateRuleGroup(id, {
        type: 'TEACHER',
        name,
        members: membersOf(teacherIds),
      })
      await load()
      return true
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, '保存教师分组失败'))
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
      ElMessage.error(serverMessage(e, '删除教师分组失败'))
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
    teacherGroups,
    groupNames,
    loading,
    error,
    groupsOfTeacher,
    load,
    createTeacher,
    update,
    remove,
    impact,
  }
}
