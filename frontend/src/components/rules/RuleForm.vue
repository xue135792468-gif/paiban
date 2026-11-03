<template>
  <el-card shadow="never" class="rule-form" data-testid="rule-form">
    <h3 class="form-title">{{ initial ? '编辑规则' : '新建规则' }}</h3>

    <div class="row">
      <span class="label">要求</span>
      <div class="value">
        <el-select
          v-model="type"
          filterable
          class="type-select"
          data-testid="rule-type"
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

    <div v-if="spec.target !== 'NONE'" class="row">
      <span class="label">对谁</span>
      <div class="value">
        <template v-if="spec.target === 'CLASS'">
          <el-radio-group
            v-if="spec.allowSchool"
            v-model="classScope"
            data-testid="rule-scope"
          >
            <el-radio-button value="SCHOOL">全校</el-radio-button>
            <el-radio-button value="CLASS">班级</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="!spec.allowSchool || classScope === 'CLASS'"
            v-model="classId"
            filterable
            clearable
            placeholder="选择班级"
            class="object-select"
            data-testid="rule-class"
          >
            <el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </template>

        <template v-else-if="spec.target === 'TEACHER'">
          <el-radio-group
            v-if="spec.allowAnyTeacher"
            v-model="teacherScope"
            data-testid="rule-scope"
          >
            <el-radio-button value="ANY">所有教师</el-radio-button>
            <el-radio-button value="ONE">指定教师</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="!spec.allowAnyTeacher || teacherScope === 'ONE'"
            v-model="teacherId"
            filterable
            clearable
            placeholder="选择教师"
            class="object-select"
            data-testid="rule-teacher"
          >
            <el-option v-for="item in teachers" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </template>

        <span v-else class="word">所有班级</span>
      </div>
    </div>

    <div v-if="spec.course !== 'ALL'" class="row">
      <span class="label">哪些课</span>
      <div class="value">
        <el-select
          v-model="courseId"
          filterable
          :clearable="spec.course === 'OPTIONAL'"
          :placeholder="spec.coursePlaceholder"
          class="object-select"
          data-testid="rule-course"
        >
          <el-option v-for="item in courses" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <span v-if="spec.course === 'OPTIONAL'" class="hint">留空表示{{ spec.coursePlaceholder }}</span>
      </div>
    </div>

    <div v-if="spec.day === 'REQUIRED'" class="row">
      <span class="label">什么时候</span>
      <div class="value column">
        <el-select
          v-model="dayOfWeek"
          placeholder="选择星期"
          class="day-select"
          data-testid="rule-day"
        >
          <el-option v-for="day in days" :key="day" :label="weekdayLabel(day)" :value="day" />
        </el-select>
        <div v-if="spec.periods === 'REQUIRED' && dayOfWeek !== null" class="periods">
          <div class="period-actions">
            <el-button size="small" :disabled="!hasAfternoon" data-testid="rule-afternoon" @click="selectAfternoon">
              下午
            </el-button>
            <el-button size="small" data-testid="rule-clear-periods" @click="clearPeriods">
              清空
            </el-button>
          </div>
          <el-checkbox-group v-model="periodNos" class="period-checks">
            <el-checkbox
              v-for="periodNo in periodOptions"
              :key="periodNo"
              :value="periodNo"
              :data-testid="`rule-period-${periodNo}`"
            >
              第 {{ periodNo }} 节
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </div>
    </div>

    <div v-if="spec.amount" class="row">
      <span class="label">数量</span>
      <div class="value">
        <span class="word">{{ spec.amount.label }}</span>
        <el-input-number
          v-model="amount"
          :min="spec.amount.min"
          :max="spec.amount.max"
          :step="1"
          step-strictly
          data-testid="rule-amount"
        />
      </div>
    </div>

    <div class="row">
      <span class="label">强度</span>
      <div class="value">
        <el-radio-group v-model="strength" :disabled="spec.hardOnly" data-testid="rule-strength">
          <el-radio-button value="HARD">必须</el-radio-button>
          <el-radio-button value="SOFT">尽量</el-radio-button>
        </el-radio-group>
        <span v-if="spec.hardOnly" class="hint">这条规则只能设为必须</span>
      </div>
    </div>

    <div class="preview" data-testid="rule-preview">预览：{{ preview }}</div>
    <p v-if="missing" class="missing" data-testid="rule-missing">还差：{{ missing }}</p>

    <div class="actions">
      <el-button data-testid="rule-cancel" @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :disabled="missing !== ''" data-testid="rule-submit" @click="submit">
        完成
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { OptionItem } from '@/components/constraints/types'
import {
  CLASS_UNAVAILABLE,
  RULE_GROUPS,
  RULE_SPECS,
  afternoonPeriods,
  describeRule,
  missingParts,
  normalizePeriods,
  weekdayLabel,
  type PeriodOption,
  type RuleCard,
  type RuleInput,
  type RuleNames,
  type RuleStrength,
  type RuleType,
} from '@/rules/compiler'

const props = defineProps<{
  initial: RuleCard | null
  teachers: OptionItem[]
  classes: OptionItem[]
  courses: OptionItem[]
  days: number[]
  periods: PeriodOption[]
}>()

const emit = defineEmits<{
  submit: [input: RuleInput]
  cancel: []
}>()

const type = ref<RuleType>(props.initial?.type ?? CLASS_UNAVAILABLE)
const classId = ref<number | null>(props.initial?.classId ?? null)
const teacherId = ref<number | null>(props.initial?.teacherId ?? null)
const courseId = ref<number | null>(props.initial?.courseId ?? null)
const dayOfWeek = ref<number | null>(props.initial?.dayOfWeek ?? null)
const periodNos = ref<number[]>([...(props.initial?.periodNos ?? [])])
const amount = ref<number | null>(props.initial?.amount ?? null)
const strength = ref<RuleStrength>(props.initial?.strength ?? 'HARD')

const spec = computed(() => RULE_SPECS[type.value])

const classScope = computed<'SCHOOL' | 'CLASS'>({
  get: () => (classId.value === null ? 'SCHOOL' : 'CLASS'),
  set: (value) => {
    if (value === 'SCHOOL') {
      classId.value = null
    }
  },
})

const teacherScope = computed<'ANY' | 'ONE'>({
  get: () => (teacherId.value === null ? 'ANY' : 'ONE'),
  set: (value) => {
    if (value === 'ANY') {
      teacherId.value = null
    }
  },
})

const names = computed<RuleNames>(() => ({
  teacher: (id: number) =>
    props.teachers.find((item) => item.id === id)?.name ?? `教师${id}`,
  course: (id: number) =>
    props.courses.find((item) => item.id === id)?.name ?? `课程${id}`,
  klass: (id: number) =>
    props.classes.find((item) => item.id === id)?.name ?? `班级${id}`,
}))

/** Only the periods the chosen weekday actually has, so no "不存在的第 9 节". */
const periodOptions = computed(() => {
  if (dayOfWeek.value === null) {
    return []
  }
  return normalizePeriods(
    props.periods
      .filter((period) => period.dayOfWeek === dayOfWeek.value)
      .map((period) => period.periodNo),
  )
})

const hasAfternoon = computed(
  () => dayOfWeek.value !== null && afternoonPeriods(props.periods, dayOfWeek.value).length > 0,
)

const previewShape = computed(() => ({
  type: type.value,
  classId: classId.value,
  teacherId: teacherId.value,
  courseId: courseId.value,
  dayOfWeek: dayOfWeek.value,
  periodNos: periodNos.value,
  amount: amount.value,
}))

const preview = computed(() => describeRule(previewShape.value, names.value, props.periods))

const missing = computed(() => missingParts(previewShape.value).join('、'))

watch(type, (next, previous) => {
  const nextSpec = RULE_SPECS[next]
  const previousSpec = RULE_SPECS[previous]
  if (nextSpec.target !== 'CLASS') {
    classId.value = null
  }
  if (nextSpec.target !== 'TEACHER') {
    teacherId.value = null
  }
  if (nextSpec.course === 'ALL') {
    courseId.value = null
  }
  if (nextSpec.day !== 'REQUIRED') {
    dayOfWeek.value = null
  }
  if (nextSpec.periods !== 'REQUIRED') {
    periodNos.value = []
  }
  if (nextSpec.amount && previousSpec.amount?.field !== nextSpec.amount.field) {
    amount.value = nextSpec.amount.defaultValue
  }
  if (!nextSpec.amount) {
    amount.value = null
  }
  if (nextSpec.hardOnly) {
    strength.value = 'HARD'
  }
})

watch(dayOfWeek, () => {
  const allowed = new Set(periodOptions.value)
  periodNos.value = periodNos.value.filter((periodNo) => allowed.has(periodNo))
})

function selectAfternoon() {
  if (dayOfWeek.value === null) {
    return
  }
  periodNos.value = afternoonPeriods(props.periods, dayOfWeek.value)
}

function clearPeriods() {
  periodNos.value = []
}

function submit() {
  if (missing.value !== '') {
    return
  }
  emit('submit', {
    type: type.value,
    classId: classId.value,
    teacherId: teacherId.value,
    courseId: courseId.value,
    dayOfWeek: dayOfWeek.value,
    periodNos: normalizePeriods(periodNos.value),
    amount: amount.value,
    strength: spec.value.hardOnly ? 'HARD' : strength.value,
  })
}
</script>

<style scoped>
.rule-form {
  border-radius: var(--paiban-radius, 8px);
  margin-bottom: 14px;
}

.form-title {
  margin: 0 0 12px;
  font-size: 16px;
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

.hint,
.missing {
  color: var(--el-color-warning, #e6a23c);
  font-size: 12px;
}

.preview {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--paiban-ink, #1f2937);
  font-size: 14px;
}

.missing {
  margin: 8px 0 0;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
</style>
