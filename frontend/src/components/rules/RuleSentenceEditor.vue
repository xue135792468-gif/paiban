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
            <el-option label="全校" value="school" />
            <el-option label="所有教师" value="all-teachers" />
            <el-option
              v-for="item in classes"
              :key="`class:${item.id}`"
              :label="`班级 · ${item.name}`"
              :value="`class:${item.id}`"
            />
            <el-option
              v-for="item in teachers"
              :key="`teacher:${item.id}`"
              :label="`教师 · ${item.name}`"
              :value="`teacher:${item.id}`"
            />
          </el-option-group>
          <el-option-group label="课程">
            <el-option label="全部课程" value="all-courses" />
            <el-option
              v-for="item in courses"
              :key="`course:${item.id}`"
              :label="`课程 · ${item.name}`"
              :value="`course:${item.id}`"
            />
          </el-option-group>
        </el-select>
      </div>
    </div>

    <div v-if="showTime" class="row">
      <span class="label">什么时候</span>
      <div class="value column">
        <el-select
          v-model="local.days"
          multiple
          filterable
          collapse-tags
          placeholder="选择星期（可多选）"
          class="day-select"
          :data-testid="tid('rule-days')"
          @change="onDaysChange"
        >
          <el-option v-for="day in days" :key="day" :label="weekdayLabel(day)" :value="day" />
        </el-select>
        <div v-if="showPeriods && local.days.length > 0" class="periods">
          <div class="period-actions">
            <el-button
              size="small"
              :disabled="lastPeriod() === null"
              :data-testid="tid('rule-last-period')"
              @click="selectLastPeriod"
            >
              最后一节
            </el-button>
            <el-button size="small" :data-testid="tid('rule-clear-periods')" @click="clearPeriods">
              清空
            </el-button>
          </div>
          <el-checkbox-group v-model="local.periodNos" class="period-checks">
            <el-checkbox
              v-for="periodNo in periodOptions"
              :key="periodNo"
              :value="periodNo"
              :data-testid="periodTestId(periodNo)"
            >
              第 {{ periodNo }} 节
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </div>
    </div>

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
import {
  CLASS_GAP_MINIMIZED,
  CLASS_UNAVAILABLE,
  RULE_GROUPS,
  RULE_SPECS,
  describeRule,
  normalizeDays,
  normalizePeriods,
  weekdayLabel,
  type PeriodOption,
  type RuleNames,
  type RuleType,
  type SubjectKind,
} from '@/rules/compiler'
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

function periodTestId(periodNo: number): string {
  return props.index === 0 ? `rule-period-${periodNo}` : `rule-period-${periodNo}-${props.index}`
}

const rule = computed(() => (local.type === null ? null : RULE_SPECS[local.type]))

const names = computed<RuleNames>(() => ({
  teacher: (id: number) => props.teachers.find((item) => item.id === id)?.name ?? `教师${id}`,
  course: (id: number) => props.courses.find((item) => item.id === id)?.name ?? `课程${id}`,
  klass: (id: number) => props.classes.find((item) => item.id === id)?.name ?? `班级${id}`,
}))

const preview = computed(() => describeRule(local, names.value, props.periods))

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
    case 'COURSE':
      return local.courseId === null ? null : `course:${local.courseId}`
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
    case 'ALL_COURSES':
      return rule.target === 'COURSE' && rule.course !== 'REQUIRED'
    case 'COURSE':
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

/** Only the periods every chosen weekday has, so no "不存在的第 9 节". */
const periodOptions = computed(() => {
  if (local.days.length === 0) {
    return []
  }
  const sets = local.days.map((day) => props.periods
    .filter((period) => period.dayOfWeek === day)
    .map((period) => period.periodNo))
  if (sets.some((set) => set.length === 0)) {
    return []
  }
  const common = sets.slice(1).reduce(
    (left, right) => left.filter((periodNo) => right.includes(periodNo)),
    [...sets[0]],
  )
  return normalizePeriods(common)
})

function lastPeriod(): number | null {
  const options = periodOptions.value
  return options.length > 0 ? options[options.length - 1] : null
}

function clearUnrelatedIds(kind: SubjectKind) {
  if (kind === 'SCHOOL' || kind === 'ALL_TEACHERS') {
    local.classId = null
    local.teacherId = null
    local.courseId = null
  } else if (kind === 'ALL_COURSES') {
    local.courseId = null
    local.teacherId = null
    local.classId = null
  } else if (kind === 'CLASS') {
    local.teacherId = null
    local.courseId = null
  } else if (kind === 'TEACHER') {
    local.classId = null
    local.courseId = null
  } else {
    local.classId = null
    local.teacherId = null
  }
}

function onSubjectChange(value: unknown) {
  const text = typeof value === 'string' ? value : ''
  let kind: SubjectKind | null = null
  if (text === 'school') {
    kind = 'SCHOOL'
  } else if (text === 'all-teachers') {
    kind = 'ALL_TEACHERS'
  } else if (text === 'all-courses') {
    kind = 'ALL_COURSES'
  } else if (text.startsWith('class:')) {
    kind = 'CLASS'
  } else if (text.startsWith('teacher:')) {
    kind = 'TEACHER'
  } else if (text.startsWith('course:')) {
    kind = 'COURSE'
  }

  local.subjectKind = kind
  local.exceptionClassIds = []
  if (kind !== null) {
    clearUnrelatedIds(kind)
  }
  if (kind === 'CLASS') {
    local.classId = Number(text.slice('class:'.length))
  } else if (kind === 'TEACHER') {
    local.teacherId = Number(text.slice('teacher:'.length))
  } else if (kind === 'COURSE') {
    local.courseId = Number(text.slice('course:'.length))
  }

  if (local.type && !acceptsSubject(kind, local.type)) {
    local.type = null
    local.amount = null
    local.exceptionClassIds = []
  }
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
  }
  if (next.course === 'ALL') {
    local.courseId = null
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

function onDaysChange() {
  local.days = normalizeDays(local.days)
  const allowed = new Set(periodOptions.value)
  local.periodNos = local.periodNos.filter((periodNo) => allowed.has(periodNo))
}

function selectLastPeriod() {
  const last = lastPeriod()
  if (last !== null) {
    local.periodNos = [last]
  }
}

function clearPeriods() {
  local.periodNos = []
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

.day-select {
  width: 240px;
}

.periods {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-wrap: wrap;
}

.period-actions {
  display: flex;
  gap: 6px;
}

.period-checks {
  display: flex;
  gap: 2px 12px;
  flex-wrap: wrap;
}

.hint {
  color: var(--el-color-warning, #e6a23c);
  font-size: 12px;
}
</style>
