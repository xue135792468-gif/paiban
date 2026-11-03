<template>
  <el-card shadow="never" class="rule-form" data-testid="rule-form">
    <h3 class="form-title">{{ initial ? '编辑规则' : '新建规则' }}</h3>

    <div class="row">
      <span class="label">对谁</span>
      <div class="value">
        <el-radio-group v-model="scopeValue" data-testid="rule-scope">
          <el-radio-button value="SCHOOL">全校</el-radio-button>
          <el-radio-button value="TEACHER">教师</el-radio-button>
        </el-radio-group>
        <el-select
          v-if="scopeValue === 'TEACHER'"
          v-model="teacherId"
          filterable
          clearable
          placeholder="选择教师"
          class="teacher-select"
          data-testid="rule-teacher"
        >
          <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
        </el-select>
        <span class="word">的 课</span>
      </div>
    </div>

    <div class="row">
      <span class="label">哪些课</span>
      <span class="value fixed">全部课程</span>
    </div>

    <div class="row">
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
        <div v-if="dayOfWeek !== null" class="periods">
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

    <div class="row">
      <span class="label">要求</span>
      <div class="value">
        <span class="word">不排</span>
        <el-radio-group v-model="strength" :disabled="scopeValue === 'SCHOOL'" data-testid="rule-strength">
          <el-radio-button value="HARD">必须</el-radio-button>
          <el-radio-button value="SOFT">尽量</el-radio-button>
        </el-radio-group>
        <span v-if="scopeValue === 'SCHOOL'" class="hint">全校不上课只能设为必须</span>
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
  TEACHER_UNAVAILABLE,
  afternoonPeriods,
  describeRule,
  normalizePeriods,
  weekdayLabel,
  type PeriodOption,
  type RuleCard,
  type RuleInput,
  type RuleStrength,
} from '@/rules/compiler'

const props = defineProps<{
  initial: RuleCard | null
  teachers: OptionItem[]
  days: number[]
  periods: PeriodOption[]
}>()

const emit = defineEmits<{
  submit: [input: RuleInput]
  cancel: []
}>()

const scopeValue = ref<'SCHOOL' | 'TEACHER'>(
  props.initial?.type === 'TEACHER_UNAVAILABLE' ? 'TEACHER' : 'SCHOOL',
)
const teacherId = ref<number | null>(props.initial?.teacherId ?? null)
const dayOfWeek = ref<number | null>(props.initial?.dayOfWeek ?? null)
const periodNos = ref<number[]>([...(props.initial?.periodNos ?? [])])
const strength = ref<RuleStrength>(props.initial?.strength ?? 'HARD')

const teacherNames = computed(
  () => new Map(props.teachers.map((teacher) => [teacher.id, teacher.name])),
)

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

const preview = computed(() => {
  const type = scopeValue.value === 'SCHOOL' ? CLASS_UNAVAILABLE : TEACHER_UNAVAILABLE
  if (dayOfWeek.value === null) {
    const scope =
      scopeValue.value === 'SCHOOL'
        ? '全校'
        : teacherId.value === null
          ? '教师'
          : (teacherNames.value.get(teacherId.value) ?? `教师${teacherId.value}`)
    return `${scope} 的 课 在 （请选择星期） 不排`
  }
  return describeRule(
    {
      type,
      teacherId: teacherId.value,
      dayOfWeek: dayOfWeek.value,
      periodNos: periodNos.value,
    },
    (id) => teacherNames.value.get(id) ?? `教师${id}`,
    props.periods,
  )
})

const missing = computed(() => {
  const parts: string[] = []
  if (scopeValue.value === 'TEACHER' && teacherId.value === null) {
    parts.push('选择教师')
  }
  if (dayOfWeek.value === null) {
    parts.push('选择星期')
  }
  if (periodNos.value.length === 0) {
    parts.push('选择第几节')
  }
  return parts.join('、')
})

watch(scopeValue, (value) => {
  if (value === 'SCHOOL') {
    teacherId.value = null
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
  if (missing.value !== '' || dayOfWeek.value === null) {
    return
  }
  const type = scopeValue.value === 'SCHOOL' ? CLASS_UNAVAILABLE : TEACHER_UNAVAILABLE
  const input: RuleInput = {
    type,
    teacherId: scopeValue.value === 'TEACHER' ? teacherId.value : null,
    dayOfWeek: dayOfWeek.value,
    periodNos: normalizePeriods(periodNos.value),
    strength: type === CLASS_UNAVAILABLE ? 'HARD' : strength.value,
  }
  emit('submit', input)
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

.fixed {
  color: var(--paiban-ink, #1f2937);
}

.word {
  color: var(--paiban-ink, #1f2937);
}

.teacher-select,
.day-select {
  width: 200px;
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
