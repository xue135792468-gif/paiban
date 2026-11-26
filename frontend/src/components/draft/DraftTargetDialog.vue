<template>
  <el-dialog
    :model-value="modelValue"
    title="选择位置"
    width="680px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="hint">
      选择目标节次；「本班已占用」可点击尝试对调，教师有课、禁排时段与已锁定节次不可选（悬停可看详情）。
    </p>
    <div v-for="day in days" :key="day.day" class="day-row">
      <div class="day-name">{{ dayName(day.day) }}</div>
      <div class="period-list">
        <span
          v-for="period in day.periods"
          :key="period.id"
          class="chip-wrap"
          :title="stateOf(period).tooltip"
        >
          <button
            type="button"
            class="period-chip"
            :class="stateOf(period).status"
            :data-period-id="period.id"
            :data-status="stateOf(period).status"
            :title="stateOf(period).tooltip"
            :disabled="stateOf(period).disabled"
            @click="pick(period)"
          >
            <span>第{{ period.periodNo }}节</span>
            <span class="chip-note">{{ stateOf(period).label }}</span>
          </button>
        </span>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { TargetPeriodState } from './targetAvailability'
import type { DraftPeriod } from './types'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    periods: DraftPeriod[]
    currentPeriodId?: number | null
    lockedPeriodIds?: number[]
    unavailablePeriodIds?: number[]
    periodStates?: Record<number, TargetPeriodState> | null
  }>(),
  {
    currentPeriodId: null,
    lockedPeriodIds: () => [],
    unavailablePeriodIds: () => [],
    periodStates: null,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'select', periodId: number): void
}>()

const lockedIds = computed(() => new Set(props.lockedPeriodIds))
const unavailableIds = computed(() => new Set(props.unavailablePeriodIds))

const days = computed(() => {
  const map = new Map<number, DraftPeriod[]>()
  props.periods.forEach((period) => {
    const list = map.get(period.dayOfWeek) ?? []
    list.push(period)
    map.set(period.dayOfWeek, list)
  })
  return [...map.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([day, periods]) => ({
      day,
      periods: [...periods].sort((a, b) => a.periodNo - b.periodNo),
    }))
})

function dayName(day: number) {
  return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][day] ?? `周${day}`
}

function stateOf(period: DraftPeriod): TargetPeriodState {
  const explicit = props.periodStates?.[period.id]
  if (explicit) return explicit
  if (period.id === props.currentPeriodId) {
    return { status: 'current', disabled: true, label: '当前位置', tooltip: '当前所在位置' }
  }
  if (lockedIds.value.has(period.id)) {
    return { status: 'locked', disabled: true, label: '已锁定', tooltip: '该时段课节已锁定' }
  }
  if (unavailableIds.value.has(period.id)) {
    return { status: 'unavailable', disabled: true, label: '不可用', tooltip: '该时段不可用' }
  }
  return {
    status: 'available',
    disabled: false,
    label: period.label || '可放置',
    tooltip: `可移动到第${period.periodNo}节`,
  }
}

function pick(period: DraftPeriod) {
  if (stateOf(period).disabled) return
  emit('select', period.id)
  emit('update:modelValue', false)
}
</script>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--paiban-muted);
  font-size: 13px;
}
.day-row {
  display: flex;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid #eef0f5;
}
.day-name {
  width: 48px;
  font-weight: 600;
  font-size: 13px;
  padding-top: 4px;
}
.period-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex: 1;
}
.chip-wrap {
  display: inline-flex;
}
.period-chip {
  border: 1px solid #dcdfe6;
  background: #fff;
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  gap: 6px;
  align-items: center;
}
.period-chip:hover:not(:disabled) {
  border-color: #409eff;
}
.period-chip.swappable {
  border-color: #e6a23c;
  background: #fdf6ec;
}
.period-chip.swappable .chip-note {
  color: #b88230;
}
.period-chip.current {
  border-color: #409eff;
  color: #409eff;
  cursor: default;
}
.period-chip:disabled {
  cursor: not-allowed;
  opacity: 0.6;
  background: #f5f7fa;
}
.period-chip.teacher-busy .chip-note,
.period-chip.blocked .chip-note {
  color: #b43b3b;
}
.chip-note {
  font-size: 11px;
  color: var(--paiban-muted);
}
</style>
