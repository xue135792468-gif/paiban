<template>
  <div class="row">
    <span class="label">什么时候</span>
    <div class="value column">
      <el-select
        v-if="allowTimeGroup"
        :model-value="timeGroupValue"
        class="time-group-select"
        :data-testid="tid('rule-time-group')"
        @change="onTimeGroupChange"
      >
        <el-option label="按星期/节次" value="" />
        <el-option
          v-for="group in timeGroups"
          :key="group.id"
          :label="`时间组 · ${group.name}`"
          :value="`time-group:${group.id}`"
        />
        <el-option
          v-if="createTimeGroup"
          label="＋ 新建时间组"
          value="__new_time_group__"
        />
      </el-select>

      <span
        v-if="timeGroupId !== null && allowTimeGroup"
        class="hint"
        data-testid="rule-time-group-hint"
      >
        时间组包含的课节在分组区修改
      </span>

      <template v-if="timeGroupId === null || !allowTimeGroup">
        <el-select
          :model-value="days"
          multiple
          filterable
          collapse-tags
          placeholder="选择星期（可多选）"
          class="day-select"
          :data-testid="tid('rule-days')"
          @change="onDaysChange"
        >
          <el-option v-for="day in weekdayChoices" :key="day" :label="weekdayLabel(day)" :value="day" />
        </el-select>
        <div v-if="allowPeriods && days.length > 0" class="periods">
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
          <el-checkbox-group
            :model-value="periodNos"
            class="period-checks"
            @update:model-value="onPeriodsChange"
          >
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
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  normalizeDays,
  normalizePeriods,
  weekdayLabel,
  type PeriodOption,
} from '@/rules/compiler'
import { promptGroupName } from '@/rules/promptGroupName'

/**
 * Ticket 06: the 「什么时候」 row. A sentence uses either a time group or an
 * explicit weekday × period grid, never both, so picking one clears the other.
 */
const props = defineProps<{
  days: number[]
  periodNos: number[]
  timeGroupId: number | null
  periods: PeriodOption[]
  timeGroups: { id: number; name: string }[]
  createTimeGroup?: (name: string) => Promise<{ id: number; name: string } | null>
  /** Fixed days off only pick weekdays. */
  allowPeriods: boolean
  /** Slot types may reference a semester time group. */
  allowTimeGroup: boolean
  index: number
}>()

const emit = defineEmits<{
  'update:days': [days: number[]]
  'update:periodNos': [periodNos: number[]]
  'update:timeGroupId': [timeGroupId: number | null]
}>()

function tid(base: string): string {
  return props.index === 0 ? base : `${base}-${props.index}`
}

function periodTestId(periodNo: number): string {
  return props.index === 0 ? `rule-period-${periodNo}` : `rule-period-${periodNo}-${props.index}`
}

/** The weeks offered are the ones the semester actually has periods for. */
const weekdayChoices = computed(() =>
  normalizeDays(Array.from(new Set(props.periods.map((period) => period.dayOfWeek)))))

const timeGroupValue = computed(
  () => (props.timeGroupId === null ? '' : `time-group:${props.timeGroupId}`),
)

/** Only the periods every chosen weekday has, so no "不存在的第 9 节". */
function periodOptionsFor(days: number[]): number[] {
  if (days.length === 0) {
    return []
  }
  const sets = days.map((day) => props.periods
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
}

const periodOptions = computed(() => periodOptionsFor(props.days))

function lastPeriod(): number | null {
  const options = periodOptions.value
  return options.length > 0 ? options[options.length - 1] : null
}

function onDaysChange(value: unknown) {
  const days = normalizeDays(Array.isArray(value)
    ? value.filter((day): day is number => typeof day === 'number')
    : [])
  emit('update:days', days)
  const allowed = new Set(periodOptionsFor(days))
  const filtered = props.periodNos.filter((periodNo) => allowed.has(periodNo))
  if (filtered.length !== props.periodNos.length) {
    emit('update:periodNos', filtered)
  }
}

function onPeriodsChange(value: unknown) {
  const allowed = new Set(periodOptions.value)
  const next = normalizePeriods(Array.isArray(value)
    ? value.filter((periodNo): periodNo is number =>
      typeof periodNo === 'number' && allowed.has(periodNo))
    : [])
  emit('update:periodNos', next)
}

function selectLastPeriod() {
  const last = lastPeriod()
  if (last !== null) {
    emit('update:periodNos', [last])
  }
}

function clearPeriods() {
  emit('update:periodNos', [])
}

/** A group replaces the whole grid: days and periods are cleared together. */
function selectTimeGroup(id: number) {
  emit('update:timeGroupId', id)
  emit('update:days', [])
  emit('update:periodNos', [])
}

async function onTimeGroupChange(value: unknown) {
  const text = typeof value === 'string' ? value : ''
  if (text === '__new_time_group__') {
    await createAndSelect()
    return
  }
  if (text === '') {
    emit('update:timeGroupId', null)
    return
  }
  if (text.startsWith('time-group:')) {
    selectTimeGroup(Number(text.slice('time-group:'.length)))
  }
}

/** 「＋ 新建时间组」: create an empty group, then select it. Cancel keeps the old pick. */
async function createAndSelect() {
  if (!props.createTimeGroup) {
    return
  }
  const name = await promptGroupName('时间组')
  if (!name) {
    return
  }
  const created = await props.createTimeGroup(name)
  if (created) {
    selectTimeGroup(created.id)
  }
}
</script>

<style scoped>
.row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter, #ebeef5);
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

.time-group-select,
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
