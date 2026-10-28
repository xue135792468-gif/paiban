<template>
  <el-card shadow="never" class="timetable-card" data-testid="workbench-timetable">
    <template #header>
      <div class="card-head">
        <span class="card-title">{{ teacherName }} 的周课表（实际日期）</span>
        <span class="hint">列头是实际日期；只有所选日期的课可以发起代课</span>
      </div>
    </template>
    <div v-loading="loading">
      <div v-if="rows.length === 0" class="empty">该教师本学期没有排课，或所选周没有课。</div>
      <div v-else class="grid" :style="{ gridTemplateColumns: gridColumns }">
        <div class="corner">节次</div>
        <div
          v-for="day in days"
          :key="`head-${day}`"
          class="day-head"
          :class="{ selected: dateOf(day) === selectedDate }"
        >
          <div>{{ dayName(day) }}</div>
          <div class="day-date">{{ dateOf(day)?.slice(5) }}</div>
        </div>
        <template v-for="row in rows" :key="`row-${row}`">
          <div class="row-head">第{{ row }}节</div>
          <div
            v-for="day in days"
            :key="`cell-${day}-${row}`"
            class="cell"
            :class="{ selected: dateOf(day) === selectedDate }"
          >
            <template v-if="cellAt(day, row)">
              <div class="lesson">
                <div class="lesson-course">{{ cellAt(day, row)!.courseName }}</div>
                <div class="lesson-meta">
                  {{ cellAt(day, row)!.teacherName }}
                  <span v-if="cellAt(day, row)!.className">· {{ cellAt(day, row)!.className }}</span>
                  <span v-if="cellAt(day, row)!.roomName">· {{ cellAt(day, row)!.roomName }}</span>
                </div>
                <el-tag v-if="cellAt(day, row)!.substituted" size="small" type="info">代</el-tag>
                <el-button
                  v-if="dateOf(day) === selectedDate"
                  size="small"
                  type="primary"
                  plain
                  class="pick"
                  data-testid="pick-lesson"
                  @click="emit('pick', cellAt(day, row)!)"
                >
                  指定代课
                </el-button>
              </div>
            </template>
          </div>
        </template>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Entry } from '../types'

const props = defineProps<{
  entries: Entry[]
  /** 所选日期所在周的周一（用于列头日期）。 */
  weekStart: string
  selectedDate: string
  teacherName: string
  loading: boolean
}>()

const emit = defineEmits<{ pick: [entry: Entry] }>()

const days = [1, 2, 3, 4, 5]
const gridColumns = computed(() => `72px repeat(${days.length}, minmax(150px, 1fr))`)
const rows = computed(() =>
  [...new Set(props.entries.map((entry) => entry.periodNo))].sort((a, b) => a - b),
)

function dateOf(day: number): string | null {
  const start = new Date(`${props.weekStart}T00:00:00`)
  start.setDate(start.getDate() + day - 1)
  if (Number.isNaN(start.getTime())) return null
  const month = String(start.getMonth() + 1).padStart(2, '0')
  const dayNo = String(start.getDate()).padStart(2, '0')
  return `${start.getFullYear()}-${month}-${dayNo}`
}

function dayName(day: number) {
  return ['', '周一', '周二', '周三', '周四', '周五'][day] ?? `周${day}`
}

function cellAt(day: number, periodNo: number): Entry | null {
  return props.entries.find((entry) => entry.dayOfWeek === day && entry.periodNo === periodNo) ?? null
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.card-title {
  font-weight: 600;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
}
.empty {
  color: var(--paiban-muted);
  padding: 24px 0;
  text-align: center;
}
.grid {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.corner,
.row-head {
  align-items: center;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  display: flex;
  font-weight: 600;
  justify-content: center;
  min-height: 44px;
}
.day-head {
  border-radius: 6px;
  font-weight: 600;
  padding: 4px;
  text-align: center;
}
.day-head.selected {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.day-date {
  color: var(--paiban-muted);
  font-size: 12px;
  font-weight: 400;
}
.cell {
  background: var(--el-fill-color-extra-light);
  border-radius: 6px;
  min-height: 44px;
  padding: 4px;
}
.cell.selected {
  background: var(--el-color-primary-light-9);
}
.lesson {
  display: flex;
  flex-direction: column;
  gap: 2px;
  height: 100%;
}
.lesson-course {
  font-weight: 600;
}
.lesson-meta {
  color: var(--paiban-muted);
  font-size: 12px;
}
.pick {
  align-self: flex-start;
  margin-top: 4px;
}
</style>
