<template>
  <div class="sentence-block" :data-testid="`rule-sentence-block-${index}`">
    <div class="sentence-head">
      <span class="sentence-index">第 {{ index + 1 }} 句</span>
      <span class="sentence-preview">{{ preview }}</span>
      <el-button
        v-if="removable"
        link
        type="danger"
        :data-testid="`rule-sentence-remove-${index}`"
        @click="emit('remove')"
      >
        删除这句
      </el-button>
    </div>

    <div class="row">
      <span class="label">谁 / 什么课</span>
      <div class="value">
        <el-select
          :model-value="subjectValue"
          filterable
          placeholder="选择谁 / 什么课"
          class="subject-select"
          :data-testid="tid('rule-subject')"
          @change="onSubjectChange"
        >
          <el-option-group label="人 / 班级">
            <el-option
              v-for="item in teacherSubjectOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
              :data-testid="item.testId"
            />
          </el-option-group>
          <el-option-group label="课程">
            <el-option
              v-for="item in courseSubjectOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
              :data-testid="item.testId"
            />
          </el-option-group>
        </el-select>
      </div>
    </div>

    <RuleTimeSlotEditor
      v-if="showTime"
      v-model:days="local.days"
      v-model:period-nos="local.periodNos"
      v-model:time-group-id="local.timeGroupId"
      :periods="periods"
      :time-groups="timeGroups ?? []"
      :create-time-group="createTimeGroup"
      :allow-periods="showPeriods"
      :allow-time-group="allowTimeGroup"
      :index="index"
    />

    <div class="row">
      <span class="label">要求</span>
      <div class="value">
        <el-select
          v-model="local.type"
          filterable
          clearable
          :disabled="local.subjectKind === null"
          :placeholder="local.subjectKind === null ? '先选谁 / 什么课' : '选择要求'"
          class="type-select"
          :data-testid="tid('rule-type')"
          @change="onTypeChange"
        >
          <el-option-group
            v-for="group in typeGroups"
            :key="group.group"
            :label="group.label"
          >
            <el-option
              v-for="code in group.types"
              :key="code"
              :label="RULE_SPECS[code].label"
              :value="code"
            />
          </el-option-group>
        </el-select>
      </div>
    </div>

    <div v-if="rule && rule.amount" class="row">
      <span class="label">数量</span>
      <div class="value">
        <span class="word">{{ rule.amount.label }}</span>
        <el-input-number
          v-model="local.amount"
          :min="rule.amount.min"
          :max="rule.amount.max"
          :step="1"
          step-strictly
          :data-testid="tid('rule-amount')"
        />
      </div>
    </div>

    <div
      v-if="local.type === 'CLASS_UNAVAILABLE' && local.classId === null"
      class="row"
    >
      <span class="label">例外</span>
      <div class="value">
        <el-select
          v-model="local.exceptionClassIds"
          multiple
          filterable
          clearable
          placeholder="除了这些班级（可不选）"
          class="object-select"
          :data-testid="tid('rule-exceptions')"
        >
          <el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <span class="hint">留空表示全校所有班级都不排</span>
      </div>
    </div>

    <div v-if="rule" class="row">
      <span class="label">强度</span>
      <div class="value">
        <el-radio-group
          v-model="local.strength"
          :disabled="rule.hardOnly"
          :data-testid="tid('rule-strength')"
        >
          <el-radio-button value="HARD">必须</el-radio-button>
          <el-radio-button value="SOFT">尽量</el-radio-button>
        </el-radio-group>
        <span v-if="rule.hardOnly" class="hint">这条规则只能设为必须</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { OptionItem } from '@/components/constraints/types'
import RuleTimeSlotEditor from '@/components/rules/RuleTimeSlotEditor.vue'
import {
  CLASS_GAP_MINIMIZED,
  CLASS_UNAVAILABLE,
  RULE_GROUPS,
  RULE_SPECS,
  describeRule,
  type PeriodOption,
  type RuleNames,
  type RuleType,
  type SubjectKind,
} from '@/rules/compiler'
import { promptGroupName } from '@/rules/promptGroupName'
import {
  courseSubjectOptions as courseSubjectOptionList,
  teacherSubjectOptions as teacherSubjectOptionList,
} from '@/rules/subjectOptions'
import type { FormSentence } from '@/rules/form'

const props = defineProps<{
  sentence: FormSentence
  index: number
  removable: boolean
  teachers: OptionItem[]
  classes: OptionItem[]
  courses: OptionItem[]
  days: number[]
  periods: PeriodOption[]
  teacherGroups?: { id: number; name: string; memberIds?: number[] }[]
  createTeacherGroup?: (name: string) => Promise<{ id: number; name: string } | null>
  courseGroups?: { id: number; name: string; memberIds?: number[] }[]
  createCourseGroup?: (name: string) => Promise<{ id: number; name: string } | null>
  timeGroups?: { id: number; name: string }[]
  createTimeGroup?: (name: string) => Promise<{ id: number; name: string } | null>
}>()

const emit = defineEmits<{
  remove: []
  'update:sentence': [sentence: FormSentence]
}>()

/** A local copy keeps the form editable without mutating the parent's prop. */
const local = reactive<FormSentence>({
  ...props.sentence,
  days: [...props.sentence.days],
  periodNos: [...props.sentence.periodNos],
  exceptionClassIds: [...props.sentence.exceptionClassIds],
})

watch(local, () => {
  emit('update:sentence', {
    ...local,
    days: [...local.days],
    periodNos: [...local.periodNos],
    exceptionClassIds: [...local.exceptionClassIds],
  })
}, { deep: true })

function tid(base: string): string {
  return props.index === 0 ? base : `${base}-${props.index}`
}

const rule = computed(() => (local.type === null ? null : RULE_SPECS[local.type]))

const names = computed<RuleNames>(() => ({
  teacher: (id: number) => props.teachers.find((item) => item.id === id)?.name ?? `教师${id}`,
  course: (id: number) => props.courses.find((item) => item.id === id)?.name ?? `课程${id}`,
  klass: (id: number) => props.classes.find((item) => item.id === id)?.name ?? `班级${id}`,
  teacherGroup: (id: number) => props.teacherGroups?.find((item) => item.id === id)?.name ?? '教师组',
  courseGroup: (id: number) => props.courseGroups?.find((item) => item.id === id)?.name ?? '课程组',
  timeGroup: (id: number) => props.timeGroups?.find((item) => item.id === id)?.name ?? '时间组',
}))

const preview = computed(() => describeRule(local, names.value, props.periods))

const teacherSubjectOptions = computed(() => teacherSubjectOptionList({
  teacherGroups: props.teacherGroups,
  classes: props.classes,
  teachers: props.teachers,
  canCreate: Boolean(props.createTeacherGroup),
}))

const courseSubjectOptions = computed(() => courseSubjectOptionList({
  courseGroups: props.courseGroups,
  courses: props.courses,
  canCreate: Boolean(props.createCourseGroup),
}))

const subjectValue = computed(() => {
  switch (local.subjectKind) {
    case 'SCHOOL':
      return 'school'
    case 'ALL_TEACHERS':
      return 'all-teachers'
    case 'ALL_COURSES':
      return 'all-courses'
    case 'CLASS':
      return local.classId === null ? null : `class:${local.classId}`
    case 'TEACHER':
      return local.teacherId === null ? null : `teacher:${local.teacherId}`
    case 'TEACHER_GROUP':
      return local.teacherGroupId === null ? null : `teacher-group:${local.teacherGroupId}`
    case 'COURSE':
      return local.courseId === null ? null : `course:${local.courseId}`
    case 'COURSE_GROUP':
      return local.courseGroupId === null ? null : `course-group:${local.courseGroupId}`
    default:
      return null
  }
})

/** True when the picked subject can carry this requirement. */
function acceptsSubject(kind: SubjectKind | null, type: RuleType): boolean {
  if (kind === null) {
    return false
  }
  const rule = RULE_SPECS[type]
  switch (kind) {
    case 'SCHOOL':
      return (rule.target === 'CLASS' && rule.allowSchool) || type === CLASS_GAP_MINIMIZED
    case 'ALL_TEACHERS':
      return rule.target === 'TEACHER' && rule.allowAnyTeacher
    case 'CLASS':
      return rule.target === 'CLASS'
    case 'TEACHER':
      return rule.target === 'TEACHER'
    case 'TEACHER_GROUP':
      return rule.target === 'TEACHER'
    case 'ALL_COURSES':
      return rule.target === 'COURSE' && rule.course !== 'REQUIRED'
    case 'COURSE':
    case 'COURSE_GROUP':
      return rule.target === 'COURSE'
    default:
      return false
  }
}

/** Only the requirement groups that read naturally after the picked subject. */
const typeGroups = computed(() =>
  RULE_GROUPS
    .map((group) => ({
      ...group,
      types: group.types.filter((type) => acceptsSubject(local.subjectKind, type)),
    }))
    .filter((group) => group.types.length > 0),
)

const showTime = computed(() => local.type === null || rule.value?.day === 'REQUIRED')
const showPeriods = computed(
  () => local.type === null || rule.value?.periods === 'REQUIRED',
)
/** Time groups replace the day × period grid, so only slot types may use them. */
const allowTimeGroup = computed(
  () => local.type === null || rule.value?.periods === 'REQUIRED',
)

/**
 * Applies one subject choice. Every concrete object and group reference is cleared
 * first, so switching from 王老师 to 教导主任 can never leave both in the sentence.
 */
function setSubject(
  kind: SubjectKind | null,
  ids: Partial<Pick<FormSentence,
    'classId' | 'teacherId' | 'courseId' | 'teacherGroupId' | 'courseGroupId'>> = {},
) {
  local.subjectKind = kind
  local.exceptionClassIds = []
  local.classId = ids.classId ?? null
  local.teacherId = ids.teacherId ?? null
  local.courseId = ids.courseId ?? null
  local.teacherGroupId = ids.teacherGroupId ?? null
  local.courseGroupId = ids.courseGroupId ?? null
  local.timeGroupId = null

  if (local.type && !acceptsSubject(kind, local.type)) {
    local.type = null
    local.amount = null
    local.exceptionClassIds = []
  }
}

async function onSubjectChange(value: unknown) {
  const text = typeof value === 'string' ? value : ''
  if (text === '__new_teacher_group__') {
    await createAndSelectGroup('TEACHER')
    return
  }
  if (text === '__new_course_group__') {
    await createAndSelectGroup('COURSE')
    return
  }
  if (text === 'school') {
    setSubject('SCHOOL')
  } else if (text === 'all-teachers') {
    setSubject('ALL_TEACHERS')
  } else if (text === 'all-courses') {
    setSubject('ALL_COURSES')
  } else if (text.startsWith('class:')) {
    setSubject('CLASS', { classId: Number(text.slice('class:'.length)) })
  } else if (text.startsWith('teacher-group:')) {
    setSubject('TEACHER_GROUP', {
      teacherGroupId: Number(text.slice('teacher-group:'.length)),
    })
  } else if (text.startsWith('teacher:')) {
    setSubject('TEACHER', { teacherId: Number(text.slice('teacher:'.length)) })
  } else if (text.startsWith('course-group:')) {
    setSubject('COURSE_GROUP', {
      courseGroupId: Number(text.slice('course-group:'.length)),
    })
  } else if (text.startsWith('course:')) {
    setSubject('COURSE', { courseId: Number(text.slice('course:'.length)) })
  }
}

/** 「＋ 新建教师组 / 课程组」: create the group, then select it. Cancel keeps the old subject. */
async function createAndSelectGroup(kind: 'TEACHER' | 'COURSE') {
  const create = kind === 'TEACHER' ? props.createTeacherGroup : props.createCourseGroup
  if (!create) {
    return
  }
  const name = await promptGroupName(kind === 'TEACHER' ? '教师组' : '课程组')
  if (!name) {
    return
  }
  const created = await create(name)
  if (!created) {
    return
  }
  setSubject(
    kind === 'TEACHER' ? 'TEACHER_GROUP' : 'COURSE_GROUP',
    kind === 'TEACHER'
      ? { teacherGroupId: created.id }
      : { courseGroupId: created.id },
  )
}

function onTypeChange() {
  if (!local.type) {
    return
  }
  const next = RULE_SPECS[local.type]
  if (next.target !== 'CLASS') {
    local.classId = null
  }
  if (next.target !== 'TEACHER') {
    local.teacherId = null
    local.teacherGroupId = null
  }
  if (next.course === 'ALL') {
    local.courseId = null
  }
  if (next.target !== 'COURSE') {
    local.courseGroupId = null
  }
  if (next.periods !== 'REQUIRED') {
    local.timeGroupId = null
  }
  if (next.day !== 'REQUIRED') {
    local.days = []
  }
  if (next.periods !== 'REQUIRED') {
    local.periodNos = []
  }
  local.amount = next.amount ? next.amount.defaultValue : null
  if (next.hardOnly) {
    local.strength = 'HARD'
  }
  if (local.type !== CLASS_UNAVAILABLE) {
    local.exceptionClassIds = []
  }
}
</script>

<style scoped>
.sentence-block {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
}

.sentence-block + .sentence-block {
  margin-top: 10px;
}

.sentence-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}

.sentence-index {
  font-size: 13px;
  font-weight: 600;
}

.sentence-preview {
  flex: 1 1 auto;
  color: var(--paiban-muted, #6b7280);
  font-size: 13px;
}

.row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter, #ebeef5);
}

.row:last-of-type {
  border-bottom: none;
}

.label {
  width: 80px;
  flex: 0 0 80px;
  color: var(--paiban-muted, #6b7280);
  font-size: 13px;
  line-height: 32px;
}

.value {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-height: 32px;
}

.value.column {
  flex-direction: column;
  align-items: flex-start;
}

.word {
  color: var(--paiban-ink, #1f2937);
}

.subject-select,
.type-select,
.object-select {
  width: 240px;
}

.hint {
  color: var(--el-color-warning, #e6a23c);
  font-size: 12px;
}
</style>
