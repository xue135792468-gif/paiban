<template>
  <div
    class="grid"
    :class="{ compact }"
    :style="{ gridTemplateColumns: `96px repeat(${days.length}, minmax(${columnWidth}px, 1fr))` }"
  >
    <div class="corner">节次</div>
    <div v-for="day in days" :key="`h-${day}`" class="day-head">{{ dayName(day) }}</div>

    <template v-for="row in periodRows" :key="`row-${row.periodNo}`">
      <div class="row-head">
        <div class="row-title">第{{ row.periodNo }}节</div>
        <div v-if="row.time" class="row-time">{{ row.time }}</div>
        <div v-if="sectionLabel(row.section)" class="row-section">{{ sectionLabel(row.section) }}</div>
      </div>
      <div
        v-for="day in days"
        :key="`c-${day}-${row.periodNo}`"
        class="cell"
        :class="cellClass(day, row.periodNo)"
        @dragover.prevent
        @dragenter.prevent
        @drop.prevent="onDrop(day, row.periodNo)"
        @click="emit('cell-click', { day, periodNo: row.periodNo })"
      >
        <template v-if="lessonsAt(day, row.periodNo).length">
          <div
            v-for="lesson in lessonsAt(day, row.periodNo)"
            :key="lesson.id"
            class="lesson"
            :class="{
              dragging: draggedLessonId === lesson.id,
              highlight: highlightedIds.has(lesson.id),
              conflict: conflictIds.has(lesson.id),
              warning: warningIds.has(lesson.id),
            }"
            :style="{ background: courseColor(courses, lesson.courseId) }"
            :draggable="draggable"
            @dragstart="emit('drag-start', lesson)"
            @dragend="emit('drag-end')"
          >
            <div class="lesson-course">{{ lesson.courseName }}</div>
            <div class="lesson-meta">
              {{ lesson.teacherName }}
              <span v-if="lesson.roomName"> · {{ lesson.roomName }}</span>
            </div>
          </div>
        </template>
        <div v-else class="empty-cell">—</div>
      </div>
    </template>
  </div>
  <el-empty v-if="days.length === 0" description="该学期还没有配置作息表" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { courseColor } from './colors'
import type { DraftCourse, DraftLesson, DraftPeriod, MoveTargetState } from './types'

const props = defineProps<{
  periods: DraftPeriod[]
  lessons: DraftLesson[]
  courses: DraftCourse[]
  draggable: boolean
  draggedLessonId?: number | null
  /** null while the move-targets request is in flight; empty map means "no target". */
  targets?: Record<number, MoveTargetState> | null
  /** Lessons the preview wants to call out, for example the two affected cells. */
  highlightLessonIds?: number[]
  /** Lessons that must be flagged as a hard conflict, for example duplicate import rows. */
  conflictLessonIds?: number[]
  /** Lessons worth a look but not a conflict, for example a placeholder teacher name. */
  warningLessonIds?: number[]
  /** Narrower columns for the side-by-side preview. */
  compact?: boolean
}>()

const emit = defineEmits<{
  (e: 'drag-start', lesson: DraftLesson): void
  (e: 'drag-end'): void
  (e: 'drop-cell', payload: { day: number; periodNo: number; periodId: number }): void
  (e: 'cell-click', payload: { day: number; periodNo: number }): void
}>()

const days = computed(() => {
  const set = new Set<number>()
  props.periods.forEach((period) => set.add(period.dayOfWeek))
  return [...set].sort((a, b) => a - b)
})

const columnWidth = computed(() => (props.compact ? 110 : 150))

const highlightedIds = computed(() => new Set(props.highlightLessonIds ?? []))

const conflictIds = computed(() => new Set(props.conflictLessonIds ?? []))

const warningIds = computed(() => new Set(props.warningLessonIds ?? []))

const periodRows = computed(() => {
  const map = new Map<number, { periodNo: number; time: string; section: string }>()
  props.periods.forEach((period) => {
    if (!map.has(period.periodNo)) {
      const start = period.startTime?.slice(0, 5) ?? ''
      const end = period.endTime?.slice(0, 5) ?? ''
      map.set(period.periodNo, {
        periodNo: period.periodNo,
        time: start && end ? `${start}-${end}` : '',
        section: period.section,
      })
    }
  })
  return [...map.values()].sort((a, b) => a.periodNo - b.periodNo)
})

const periodIdByCell = computed(() => {
  const map: Record<string, number> = {}
  props.periods.forEach((period) => {
    map[`${period.dayOfWeek}-${period.periodNo}`] = period.id
  })
  return map
})

const lessonsByCell = computed(() => {
  const map: Record<string, DraftLesson[]> = {}
  props.lessons.forEach((lesson) => {
    if (lesson.dayOfWeek != null && lesson.periodNo != null) {
      const key = `${lesson.dayOfWeek}-${lesson.periodNo}`
      ;(map[key] ??= []).push(lesson)
    }
  })
  return map
})

function dayName(day: number) {
  return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][day] ?? `周${day}`
}

const sectionLabels: Record<string, string> = { MORNING: '上午', AFTERNOON: '下午', EVENING: '晚自习' }

function sectionLabel(section: string) {
  return sectionLabels[section] ?? ''
}

function lessonsAt(day: number, periodNo: number) {
  return lessonsByCell.value[`${day}-${periodNo}`] ?? []
}

function cellClass(day: number, periodNo: number) {
  if (props.draggedLessonId == null) return ''
  const periodId = periodIdByCell.value[`${day}-${periodNo}`]
  if (!periodId) return ''
  if (props.targets == null) return 'checking'
  const state = props.targets[periodId]
  if (!state) return 'invalid-target'
  return state.available ? 'valid-target' : 'invalid-target'
}

function onDrop(day: number, periodNo: number) {
  const periodId = periodIdByCell.value[`${day}-${periodNo}`]
  if (!periodId) return
  emit('drop-cell', { day, periodNo, periodId })
}
</script>

<style scoped>
.grid {
  display: grid;
  border-top: 1px solid #eef0f5;
  border-left: 1px solid #eef0f5;
  min-width: 900px;
}
.grid.compact {
  min-width: 640px;
}
.corner,
.day-head,
.row-head,
.cell {
  border-right: 1px solid #eef0f5;
  border-bottom: 1px solid #eef0f5;
}
.corner,
.day-head {
  background: #f8fafc;
  padding: 10px 8px;
  font-weight: 600;
  text-align: center;
  font-size: 13px;
}
.row-head {
  padding: 8px;
  background: #fbfcfe;
  display: flex;
  flex-direction: column;
  gap: 2px;
  justify-content: center;
}
.row-title {
  font-weight: 600;
  font-size: 13px;
}
.row-time {
  font-size: 11px;
  color: var(--paiban-muted);
}
.row-section {
  font-size: 11px;
  color: #9aa1b1;
}
.cell {
  min-height: 72px;
  padding: 4px;
  transition: background 0.12s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: stretch;
}
.cell.valid-target {
  background: #e8f7ee;
  outline: 2px solid #6fcf97 inset;
}
.cell.invalid-target {
  background: #fdecec;
  outline: 2px solid #eb5757 inset;
  cursor: not-allowed;
}
.cell.checking {
  background: #f7f9ff;
}
.empty-cell {
  color: #cbd2e0;
  margin: auto;
  font-size: 12px;
}
.lesson {
  flex: 1;
  border-radius: 8px;
  padding: 6px 8px;
  cursor: grab;
  border: 1px solid rgba(0, 0, 0, 0.06);
  color: #2b3245;
  overflow: hidden;
}
.lesson.dragging {
  opacity: 0.45;
}
.lesson.highlight {
  outline: 2px solid #409eff;
  outline-offset: -2px;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.25);
}
.lesson.conflict {
  outline: 2px solid #eb5757;
  outline-offset: -2px;
  box-shadow: 0 0 0 2px rgba(235, 87, 87, 0.25);
}
.lesson.warning {
  outline: 2px solid #e6a23c;
  outline-offset: -2px;
  box-shadow: 0 0 0 2px rgba(230, 162, 60, 0.25);
}
.lesson-course {
  font-weight: 600;
  font-size: 13px;
}
.lesson-meta {
  font-size: 11px;
  margin-top: 3px;
  color: #4b5563;
}
</style>
