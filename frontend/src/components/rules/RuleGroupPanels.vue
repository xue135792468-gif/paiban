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
  </div>
</template>

<script setup lang="ts">
import RuleGroupSection from '@/components/rules/RuleGroupSection.vue'
import type { OptionItem } from '@/components/constraints/types'
import type { UseRuleGroups } from '@/rules/useRuleGroups'

/**
 * Ticket 05: the two global group panels share the page's composable handles, so a
 * group created here immediately shows up in the subject dropdown and sentence names
 * without a second load.
 */
const props = defineProps<{
  teachers: OptionItem[]
  courses: OptionItem[]
  teacherGroups: UseRuleGroups
  courseGroups: UseRuleGroups
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
</script>
