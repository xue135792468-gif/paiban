<template>
  <div class="rule-group-panels">
    <RuleGroupSection
      group-type="TEACHER"
      title="教师分组"
      member-label="老师"
      :groups="teacherGroups.groups.value"
      :options="teachers"
      :loading="teacherGroups.loading.value"
      :create-group="createTeacherGroup"
      :update-group="teacherGroups.update"
      :delete-group="teacherGroups.remove"
      :load-impact="teacherGroups.impact"
      @changed="emit('changed', $event)"
    />
    <RuleGroupSection
      group-type="COURSE"
      title="课程分组"
      member-label="课程"
      hint="一门课可以属于多个组；改组成员后引用该组的规则自动生效"
      :groups="courseGroups.groups.value"
      :options="courses"
      :loading="courseGroups.loading.value"
      :create-group="createCourseGroup"
      :update-group="courseGroups.update"
      :delete-group="courseGroups.remove"
      :load-impact="courseGroups.impact"
      @changed="emit('changed', $event)"
    />
    <RuleTimeGroupSection
      v-if="semesterId !== null"
      :groups="timeGroups.groups.value"
      :periods="periods"
      :loading="timeGroups.loading.value"
      :semester-id="semesterId"
      :create-group="createWithSlots"
      :update-group="timeGroups.update"
      :delete-group="timeGroups.remove"
      :load-impact="timeGroups.impact"
      @changed="emit('changed', $event)"
    />
    <el-card
      v-else
      shadow="never"
      class="rule-time-groups"
      data-testid="rule-time-group-no-semester"
    >
      <p class="no-semester">选择学期后可以管理时间分组</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import RuleGroupSection from '@/components/rules/RuleGroupSection.vue'
import RuleTimeGroupSection from '@/components/rules/RuleTimeGroupSection.vue'
import type { OptionItem } from '@/components/constraints/types'
import type { UseRuleGroups } from '@/rules/useRuleGroups'
import type { UseTimeGroups } from '@/rules/useTimeGroups'
import type { PeriodOption } from '@/rules/compiler'

/**
 * Ticket 05/06: the group panels share the page's composable handles, so a group
 * created here immediately shows up in the rule form and sentence names.
 */
const props = defineProps<{
  teachers: OptionItem[]
  courses: OptionItem[]
  teacherGroups: UseRuleGroups
  courseGroups: UseRuleGroups
  timeGroups: UseTimeGroups
  semesterId: number | null
  periods: PeriodOption[]
}>()

const emit = defineEmits<{
  changed: [payload: { deletedRules: number }]
}>()

/** The panel creates the group first, then writes the picked members. */
async function createWithMembers(
  handle: UseRuleGroups,
  name: string,
  memberIds: number[],
): Promise<boolean> {
  const created = await handle.create(name)
  if (!created) {
    return false
  }
  if (memberIds.length === 0) {
    return true
  }
  return handle.update(created.id, name, memberIds)
}

function createTeacherGroup(name: string, memberIds: number[]): Promise<boolean> {
  return createWithMembers(props.teacherGroups, name, memberIds)
}

function createCourseGroup(name: string, memberIds: number[]): Promise<boolean> {
  return createWithMembers(props.courseGroups, name, memberIds)
}

/** Time groups carry coordinates, so the create call passes them straight through. */
async function createWithSlots(
  name: string,
  slots: Array<{ dayOfWeek: number; periodNo: number }>,
): Promise<boolean> {
  const created = await props.timeGroups.create(name, slots)
  return Boolean(created)
}
</script>

<style scoped>
.rule-time-groups {
  margin-bottom: 14px;
  border-radius: var(--paiban-radius, 8px);
}

.no-semester {
  margin: 0;
  color: var(--paiban-muted, #6b7280);
  font-size: 13px;
}
</style>
