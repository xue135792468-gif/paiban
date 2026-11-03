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
      <span class="label">要求</span>
      <div class="value">
        <el-select
          v-model="local.type"
          filterable
          clearable
          placeholder="选择要求"
          class="type-select"
          :data-testid="tid('rule-type')"
          @change="onTypeChange"
        >
          <el-option-group
            v-for="group in RULE_GROUPS"
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

    <div v-if="rule && rule.target !== 'NONE'" class="row">
      <span class="label">对谁</span>
      <div class="value">
        <template v-if="rule?.target === 'CLASS'">
          <el-radio-group
            v-if="rule?.allowSchool"
            :model-value="local.classId === null ? 'SCHOOL' : 'CLASS'"
            :data-testid="tid('rule-scope')"
            @change="onClassScope"
          >
            <el-radio-button value="SCHOOL">全校</el-radio-button>
            <el-radio-button value="CLASS">班级</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="!rule?.allowSchool || local.classId !== null"
            v-model="local.classId"
            filterable
            clearable
            placeholder="搜索班级"
            class="object-select"
            :data-testid="tid('rule-class')"
          >
            <el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </template>

        <template v-else-if="rule?.target === 'TEACHER'">
          <el-radio-group
            v-if="rule?.allowAnyTeacher"
            :model-value="local.teacherId === null ? 'ANY' : 'ONE'"
            :data-testid="tid('rule-scope')"
            @change="onTeacherScope"
          >
            <el-radio-button value="ANY">所有教师</el-radio-button>
            <el-radio-button value="ONE">指定教师</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="!rule?.allowAnyTeacher || local.teacherId !== null"
            v-model="local.teacherId"
            filterable
            clearable
            placeholder="搜索教师"
            class="object-select"
            :data-testid="tid('rule-teacher')"
          >
            <el-option v-for="item in teachers" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </template>

        <span v-else class="word">所有班级</span>
      </div>
    </div>

    <div v-if="rule && rule.course !== 'ALL'" class="row">
      <span class="label">哪些课</span>
      <div class="value">
        <el-select
          v-model="local.courseId"
          filterable
          :clearable="rule?.course === 'OPTIONAL'"
          :placeholder="rule?.coursePlaceholder"
          class="object-select"
          :data-testid="tid('rule-course')"
        >
          <el-option v-for="item in courses" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <span v-if="rule?.course === 'OPTIONAL'" class="hint">
          留空表示{{ rule?.coursePlaceholder }}
        </span>
      </div>
    </div>

    <div v-if="rule && rule.day === 'REQUIRED'" class="row">
      <span class="label">什么时候</span>
      <div class="value column">
        <el-select
          v-model="local.dayOfWeek"
          filterable
          placeholder="搜索星期"
          class="day-select"
          :data-testid="tid('rule-day')"
          @change="onDayChange"
        >
          <el-option v-for="day in days" :key="day" :label="weekdayLabel(day)" :value="day" />
        </el-select>
        <div v-if="rule?.periods === 'REQUIRED' && local.dayOfWeek !== null" class="periods">
          <div class="period-actions">
            <el-button
              size="small"
              :disabled="!hasAfternoon"
              :data-testid="tid('rule-afternoon')"
              @click="selectAfternoon"
            >
              下午
            </el-button>
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

    <div v-if="local.type === 'CLASS_UNAVAILABLE' && local.classId === null" class="row">
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
  CLASS_UNAVAILABLE,
  RULE_GROUPS,
  RULE_SPECS,
  afternoonPeriods,
  describeRule,
  normalizePeriods,
  weekdayLabel,
  type PeriodOption,
  type RuleNames,
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
  periodNos: [...props.sentence.periodNos],
  exceptionClassIds: [...props.sentence.exceptionClassIds],
})

watch(local, () => {
  emit('update:sentence', {
    ...local,
    periodNos: [...local.periodNos],
    exceptionClassIds: [...local.exceptionClassIds],
  })
}, { deep: true })

const rule = computed(() => (local.type === null ? null : RULE_SPECS[local.type]))

const names = computed<RuleNames>(() => ({
  teacher: (id: number) => props.teachers.find((item) => item.id === id)?.name ?? `教师${id}`,
  course: (id: number) => props.courses.find((item) => item.id === id)?.name ?? `课程${id}`,
  klass: (id: number) => props.classes.find((item) => item.id === id)?.name ?? `班级${id}`,
}))

const preview = computed(() => describeRule(local, names.value, props.periods))

function tid(base: string): string {
  return props.index === 0 ? base : `${base}-${props.index}`
}

function periodTestId(periodNo: number): string {
  return props.index === 0 ? `rule-period-${periodNo}` : `rule-period-${periodNo}-${props.index}`
}

/** Only the periods the chosen weekday actually has, so no "不存在的第 9 节". */
const periodOptions = computed(() => {
  if (local.dayOfWeek === null) {
    return []
  }
  return normalizePeriods(
    props.periods
      .filter((period) => period.dayOfWeek === local.dayOfWeek)
      .map((period) => period.periodNo),
  )
})

const hasAfternoon = computed(() =>
  local.dayOfWeek !== null
  && afternoonPeriods(props.periods, local.dayOfWeek).length > 0)

function lastPeriod(): number | null {
  const options = periodOptions.value
  return options.length > 0 ? options[options.length - 1] : null
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
    local.dayOfWeek = null
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

function onClassScope(scope: unknown) {
  if (scope === 'SCHOOL') {
    local.classId = null
  } else if (local.classId === null && props.classes.length > 0) {
    local.classId = props.classes[0].id
  }
  if (local.classId !== null) {
    local.exceptionClassIds = []
  }
}

function onTeacherScope(scope: unknown) {
  if (scope === 'ANY') {
    local.teacherId = null
  } else if (local.teacherId === null && props.teachers.length > 0) {
    local.teacherId = props.teachers[0].id
  }
}

function onDayChange() {
  const allowed = new Set(periodOptions.value)
  local.periodNos = local.periodNos.filter((periodNo) => allowed.has(periodNo))
}

function selectAfternoon() {
  if (local.dayOfWeek === null) {
    return
  }
  local.periodNos = afternoonPeriods(props.periods, local.dayOfWeek)
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
  width: 64px;
  flex: 0 0 64px;
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

.type-select,
.object-select {
  width: 240px;
}

.day-select {
  width: 130px;
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
