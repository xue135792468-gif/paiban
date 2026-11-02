<template>
  <el-dialog
    :model-value="modelValue"
    title="联动方案"
    width="720px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="status === 'NONE'"
      title="当前范围和上限内没有可用方案"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else-if="status === 'BUDGET_EXHAUSTED'"
      :title="message || '搜索未完成，以下方案尚未穷尽'"
      type="warning"
      :closable="false"
      show-icon
    />
    <p v-else-if="message" class="status-line">{{ message }}</p>

    <div v-if="loading" v-loading="true" class="loading-block" />
    <div v-else class="plan-list">
      <div
        v-for="(plan, index) in plans"
        :key="plan.token"
        class="plan-card"
        :data-testid="`cycle-plan-${index}`"
      >
        <div class="plan-head">
          <el-tag size="small" :type="plan.kind === 'DIRECT' ? 'success' : 'primary'">
            {{ plan.kind === 'DIRECT' ? '直接交换' : '联动换位' }}
          </el-tag>
          <span class="plan-stats">
            移动 {{ plan.movedLessons }} 节 · 涉及 {{ plan.affectedTeachers }} 位教师 ·
            {{ plan.affectedDays }} 天 · 软分变化 {{ signed(plan.softPenaltyDelta) }}
          </span>
        </div>
        <ul class="move-list">
          <li v-for="move in plan.moves" :key="move.lessonId">
            {{ lessonLabel(move.lessonId) }}：{{ periodLabel(move.fromPeriodId) }} →
            {{ periodLabel(move.toPeriodId) }}
          </li>
        </ul>
        <el-button
          type="primary"
          size="small"
          :loading="applying"
          :disabled="applying"
          @click="emit('apply', plan)"
        >
          应用这个方案
        </el-button>
      </div>
      <div v-if="plans.length === 0" class="empty-plans">没有可展示的方案</div>
    </div>

    <template #footer>
      <el-button :disabled="applying" @click="cancel">取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CyclePlan, DraftLesson, DraftPeriod } from './types'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    status?: 'FOUND' | 'NONE' | 'BUDGET_EXHAUSTED' | null
    message?: string
    plans: CyclePlan[]
    lessons?: DraftLesson[]
    periods?: DraftPeriod[]
    loading?: boolean
    applying?: boolean
  }>(),
  {
    status: null,
    message: '',
    lessons: () => [],
    periods: () => [],
    loading: false,
    applying: false,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'apply', plan: CyclePlan): void
  (e: 'cancel'): void
}>()

const lessonById = computed(() => new Map(props.lessons.map((lesson) => [lesson.id, lesson])))
const periodById = computed(() => new Map(props.periods.map((period) => [period.id, period])))

function signed(value: number) {
  return value > 0 ? `+${value}` : `${value}`
}

function lessonLabel(lessonId: number) {
  const lesson = lessonById.value.get(lessonId)
  if (!lesson) return `课节 #${lessonId}`
  return `${lesson.courseName ?? '课程'}（${lesson.teacherName ?? '教师'}）`
}

function periodLabel(periodId: number) {
  const period = periodById.value.get(periodId)
  if (!period) return `时段 #${periodId}`
  return `${dayName(period.dayOfWeek)}第${period.periodNo}节`
}

function dayName(day: number) {
  return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][day] ?? `周${day}`
}

function cancel() {
  emit('cancel')
  emit('update:modelValue', false)
}
</script>

<style scoped>
.status-line {
  margin: 0 0 10px;
  color: var(--paiban-muted);
  font-size: 13px;
}
.loading-block {
  min-height: 120px;
}
.plan-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.plan-card {
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 12px;
}
.plan-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.plan-stats {
  font-size: 12px;
  color: var(--paiban-muted);
}
.move-list {
  margin: 0 0 10px;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
}
.empty-plans {
  color: var(--paiban-muted);
  font-size: 13px;
  text-align: center;
  padding: 20px 0;
}
</style>
