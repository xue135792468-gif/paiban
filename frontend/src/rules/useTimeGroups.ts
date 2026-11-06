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
} from '@/api/ruleGroups'

/** One timetable coordinate a time group may contain. */
export interface TimeGroupSlot {
  dayOfWeek: number
  periodNo: number
}

/** The handle the rule page and the schedule list share for the semester's time groups. */
export interface UseTimeGroups {
  groups: Ref<RuleGroup[]>
  options: ComputedRef<Array<{ id: number; name: string }>>
  groupNames: ComputedRef<Map<number, string>>
  loading: Ref<boolean>
  error: Ref<string | null>
  load: () => Promise<void>
  create: (name: string, slots: TimeGroupSlot[]) => Promise<{ id: number; name: string } | null>
  update: (id: number, name: string, slots: TimeGroupSlot[]) => Promise<boolean>
  remove: (id: number, withRules: boolean) => Promise<RuleGroupDeleteResult | null>
  impact: (id: number) => Promise<RuleGroupImpact | null>
}

/**
 * Ticket 06: time groups belong to one semester and their members are timetable
 * coordinates (day of week + period number), not period ids. The composable mirrors
 * `useRuleGroups`, but takes the semester as a dependency because the API needs it.
 */
export function useTimeGroups(semesterId: Ref<number | null>): UseTimeGroups {
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

  const options = computed(() => groups.value.map((group) => ({
    id: group.id,
    name: group.name,
  })))

  async function load(): Promise<void> {
    if (semesterId.value === null) {
      groups.value = []
      error.value = null
      return
    }
    loading.value = true
    error.value = null
    try {
      groups.value = await getRuleGroups('TIME', semesterId.value)
    } catch (e: unknown) {
      groups.value = []
      error.value = serverMessage(e, '加载时间分组失败')
    } finally {
      loading.value = false
    }
  }

  function membersOf(slots: TimeGroupSlot[]): RuleGroupMemberRequest[] {
    return slots.map((slot) => ({
      dayOfWeek: slot.dayOfWeek,
      periodNo: slot.periodNo,
    }))
  }

  /** Empty slots are allowed: the rule dropdown creates the group before editing it. */
  async function create(
    name: string,
    slots: TimeGroupSlot[],
  ): Promise<{ id: number; name: string } | null> {
    if (semesterId.value === null) {
      return null
    }
    try {
      const created = await createRuleGroup({
        type: 'TIME',
        name,
        semesterId: semesterId.value,
        members: membersOf(slots),
      })
      await load()
      return { id: created.id, name: created.name }
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, '创建时间分组失败'))
      return null
    }
  }

  async function update(
    id: number,
    name: string,
    slots: TimeGroupSlot[],
  ): Promise<boolean> {
    if (semesterId.value === null) {
      return false
    }
    try {
      await updateRuleGroup(id, {
        type: 'TIME',
        name,
        semesterId: semesterId.value,
        members: membersOf(slots),
      })
      await load()
      return true
    } catch (e: unknown) {
      ElMessage.error(serverMessage(e, '保存时间分组失败'))
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
      ElMessage.error(serverMessage(e, '删除时间分组失败'))
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
    load,
    create,
    update,
    remove,
    impact,
  }
}
