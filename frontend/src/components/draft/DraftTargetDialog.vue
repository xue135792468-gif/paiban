<template>
  <el-dialog
    :model-value="modelValue"
    title="调整位置"
    width="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="hint">选择目标节次；灰色是当前所在位置，锁定或不可用的节次不能作为落点。</p>
    <div v-for="day in days" :key="day.day" class="day-row">
      <div class="day-name">{{ dayName(day.day) }}</div>
      <div class="period-list">
        <button
          v-for="period in day.periods"
          :key="period.id"
          type="button"
          class="period-chip"
          :class="{ current: period.id === currentPeriodId }"
          :data-period-id="period.id"
          :disabled="period.id !== currentPeriodId && isBlocked(period.id)"
          @click="pick(period.id)"
        >
          <span>第{{ period.periodNo }}节</span>
          <span v-if="period.id === currentPeriodId" class="chip-note">当前位置</span>
          <span v-else-if="lockedIds.has(period.id)" class="chip-note">已锁定</span>
          <span v-else-if="unavailableIds.has(period.id)" class="chip-note">不可用</span>
          <span v-else class="chip-note">{{ period.label }}</span>
        </button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { DraftPeriod } from './types'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    periods: DraftPeriod[]
    currentPeriodId?: number | null
    lockedPeriodIds?: number[]
    unavailablePeriodIds?: number[]
  }>(),
  {
    currentPeriodId: null,
    lockedPeriodIds: () => [],
    unavailablePeriodIds: () => [],
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

function isBlocked(periodId: number) {
  return lockedIds.value.has(periodId) || unavailableIds.value.has(periodId)
}

function pick(periodId: number) {
  if (periodId === props.currentPeriodId || isBlocked(periodId)) return
  emit('select', periodId)
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
.period-chip.current {
  border-color: #409eff;
  color: #409eff;
  cursor: default;
}
.period-chip:disabled {
  cursor: not-allowed;
  opacity: 0.55;
  background: #f5f7fa;
}
.chip-note {
  font-size: 11px;
  color: var(--paiban-muted);
}
</style>
