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
      </div>
      <div
        v-for="day in days"
        :key="`c-${day}-${row.periodNo}`"
        class="cell"
        :data-period-id="periodIdByCell[`${day}-${row.periodNo}`]"
        :class="cellClass(day, row.periodNo)"
        :data-swap-state="swapStateAt(day, row.periodNo)?.status"
        :title="cellTitle(day, row.periodNo)"
        :aria-disabled="blockedReason(day, row.periodNo) ? 'true' : undefined"
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
            :data-lesson-id="lesson.id"
            :data-locked="lockedIds.has(lesson.id) ? 'true' : undefined"
            :data-selected="selectionMode && selectedIds.has(lesson.id) ? 'true' : undefined"
            :class="{
              dragging: draggedLessonId === lesson.id,
              highlight: highlightedIds.has(lesson.id),
              conflict: conflictIds.has(lesson.id) || Boolean(blockedReason(day, row.periodNo)),
              warning: warningIds.has(lesson.id),
              locked: lockedIds.has(lesson.id),
              selected: selectionMode && selectedIds.has(lesson.id),
              'swap-swappable': swapStatusOf(lesson) === 'swappable',
              'swap-blocked': swapStatusOf(lesson) === 'blocked',
              'swap-foreign': swapStatusOf(lesson) === 'foreign',
              'swap-source': swapStatusOf(lesson) === 'source',
              'swap-checking': swapStatusOf(lesson) === 'checking',
            }"
            :style="{ background: courseColor(courses, lesson.courseId) }"
            :title="swapReasonOf(lesson)"
            tabindex="0"
            :aria-label="lessonAria(lesson, day, row.periodNo)"
            :aria-pressed="selectionMode ? selectedIds.has(lesson.id) : undefined"
            :draggable="draggable && !selectionMode"
            @dragstart="onLessonDragStart(lesson, $event)"
            @dragend="emit('drag-end')"
            @click="onLessonClick(lesson, $event)"
            @keydown.enter.prevent="onLessonKey(lesson)"
            @keydown.space.prevent="onLessonKey(lesson)"
          >
            <div class="lesson-course">
              {{ lesson.courseName }}
              <span v-if="lockedIds.has(lesson.id)" class="lock-mark">锁</span>
            </div>
            <div class="lesson-meta">{{ lessonMeta(lesson) }}</div>
          </div>
        </template>
        <div v-else-if="blockedReason(day, row.periodNo)" class="no-class">不上课</div>
        <div v-else class="empty-cell">—</div>
        <div v-if="blockedReason(day, row.periodNo) && lessonsAt(day, row.periodNo).length" class="blocked-conflict">
          占用了不上课时段，可将课程移走
        </div>
      </div>
    </template>
  </div>
  <el-empty v-if="days.length === 0" description="该学期还没有配置作息表" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { courseColor } from './colors'
import type {
  DraftCourse,
  DraftLesson,
  DraftPeriod,
  MoveTargetState,
  SwapTargetState,
} from './types'

const props = withDefaults(
  defineProps<{
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
    /** Supplied only for the selected administrative class, never a teacher view. */
    blockedPeriods?: Record<number, string>
    /** Lessons pinned by the server; shown with a marker and excluded from drag writes. */
    lockedLessonIds?: number[]
    /** Multi-select mode: clicking a card selects it instead of starting a drag. */
    selectionMode?: boolean
    selectedLessonIds?: number[]
    /**
     * Teacher-view swap mode: one state per lesson id. Non-null turns the grid into
     * green (swappable) / grey (blocked) target mode; drag targets are not used then.
     */
    swapTargets?: Record<number, SwapTargetState> | null
    /** Teacher view shows the administrative class on every card. */
    showClassName?: boolean
  }>(),
  {
    draggedLessonId: null,
    targets: null,
    highlightLessonIds: () => [],
    conflictLessonIds: () => [],
    warningLessonIds: () => [],
    blockedPeriods: () => ({}),
    lockedLessonIds: () => [],
    selectionMode: false,
    selectedLessonIds: () => [],
    swapTargets: null,
    showClassName: false,
  },
)

const emit = defineEmits<{
  (e: 'drag-start', lesson: DraftLesson): void
  (e: 'drag-end'): void
  (e: 'drop-cell', payload: {
    day: number
    periodNo: number
    periodId: number
    /** Lessons this grid actually renders in the target cell (the user-visible target). */
    lessonIds: number[]
  }): void
  (e: 'cell-click', payload: { day: number; periodNo: number }): void
  (e: 'lesson-activate', lesson: DraftLesson): void
  (e: 'toggle-select', lesson: DraftLesson): void
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

const lockedIds = computed(() => new Set(props.lockedLessonIds ?? []))

const selectedIds = computed(() => new Set(props.selectedLessonIds ?? []))

const periodRows = computed(() => {
  const map = new Map<number, { periodNo: number; time: string }>()
  props.periods.forEach((period) => {
    if (!map.has(period.periodNo)) {
      const start = period.startTime?.slice(0, 5) ?? ''
      const end = period.endTime?.slice(0, 5) ?? ''
      map.set(period.periodNo, {
        periodNo: period.periodNo,
        time: start && end ? `${start}-${end}` : '',
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

function lessonAria(lesson: DraftLesson, day: number, periodNo: number) {
  const when = `${dayName(day)}第${periodNo}节`
  const locked = lockedIds.value.has(lesson.id) ? '，已锁定' : ''
  const className = props.showClassName && lesson.className ? `，${lesson.className}` : ''
  const swap = swapReasonOf(lesson)
  const swapText = swap ? `，${swap}` : ''
  return `${lesson.courseName ?? '课程'}，${lesson.teacherName ?? '教师'}${className}，${when}${locked}${swapText}`
}

function lessonsAt(day: number, periodNo: number) {
  return lessonsByCell.value[`${day}-${periodNo}`] ?? []
}

function blockedReason(day: number, periodNo: number): string {
  const periodId = periodIdByCell.value[`${day}-${periodNo}`]
  return periodId == null ? '' : props.blockedPeriods[periodId] ?? ''
}

function swapStatusOf(lesson: DraftLesson) {
  return props.swapTargets?.[lesson.id]?.status
}

function swapReasonOf(lesson: DraftLesson): string | undefined {
  return props.swapTargets?.[lesson.id]?.reason
}

/** A green target wins over anything else in the cell; a concrete block wins over "foreign". */
const SWAP_PRIORITY = ['swappable', 'checking', 'blocked', 'source', 'foreign'] as const

function swapStateAt(day: number, periodNo: number): SwapTargetState | null {
  if (props.swapTargets == null) return null
  const lessons = lessonsAt(day, periodNo)
  if (lessons.length === 0) return { status: 'blocked', reason: '没有可换的课' }
  const states: SwapTargetState[] = []
  lessons.forEach((lesson) => {
    const state = props.swapTargets?.[lesson.id]
    if (state) states.push(state)
  })
  for (const status of SWAP_PRIORITY) {
    const found = states.find((state) => state.status === status)
    if (found) return found
  }
  return { status: 'blocked', reason: '没有可换的课' }
}

function cellTitle(day: number, periodNo: number): string | undefined {
  if (props.swapTargets != null) {
    const state = swapStateAt(day, periodNo)
    if (state) return state.reason
  }
  return blockedReason(day, periodNo) || undefined
}

function lessonMeta(lesson: DraftLesson) {
  if (!props.showClassName || !lesson.className) return lesson.teacherName ?? ''
  return `${lesson.className} · ${lesson.teacherName ?? ''}`
}

function cellClass(day: number, periodNo: number) {
  if (props.swapTargets != null) {
    const state = swapStateAt(day, periodNo)
    switch (state?.status) {
      case 'swappable':
        return 'swap-swappable'
      case 'checking':
        return 'swap-checking'
      case 'source':
        return 'swap-source'
      default:
        return 'swap-blocked'
    }
  }
  if (blockedReason(day, periodNo)) {
    return props.draggedLessonId == null ? 'blocked-slot' : 'blocked-slot invalid-target'
  }
  if (props.draggedLessonId == null) return ''
  const periodId = periodIdByCell.value[`${day}-${periodNo}`]
  if (!periodId) return ''
  if (props.targets == null) return 'checking'
  const state = props.targets[periodId]
  if (!state) return 'invalid-target'
  return state.available ? 'valid-target' : 'invalid-target'
}

function onLessonClick(lesson: DraftLesson, event: MouseEvent) {
  // Ordinary mode keeps the old behaviour: the click bubbles to the cell and
  // selects the lesson there. Selection mode owns the click and never drags.
  if (!props.selectionMode) return
  event.stopPropagation()
  emit('toggle-select', lesson)
}

function onLessonDragStart(lesson: DraftLesson, event: DragEvent) {
  if (props.selectionMode) {
    event.preventDefault()
    return
  }
  emit('drag-start', lesson)
}

function onLessonKey(lesson: DraftLesson) {
  if (props.selectionMode) emit('toggle-select', lesson)
  else emit('lesson-activate', lesson)
}

function onDrop(day: number, periodNo: number) {
  if (blockedReason(day, periodNo)) return
  const periodId = periodIdByCell.value[`${day}-${periodNo}`]
  if (!periodId) return
  emit('drop-cell', {
    day,
    periodNo,
    periodId,
    lessonIds: lessonsAt(day, periodNo).map((lesson) => lesson.id),
  })
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
.cell {
  min-height: 72px;
  padding: 4px;
  transition: background 0.12s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: stretch;
}
.cell.blocked-slot {
  background: repeating-linear-gradient(135deg, #f1f3f5, #f1f3f5 8px, #f8f9fa 8px, #f8f9fa 16px);
}
.no-class {
  margin: auto;
  color: #60666f;
  font-size: 12px;
}
.blocked-conflict {
  color: #b42318;
  font-size: 11px;
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
.cell.swap-swappable {
  background: #e8f7ee;
  outline: 2px solid #6fcf97 inset;
  cursor: pointer;
}
.cell.swap-blocked {
  background: #f4f5f7;
  outline: 1px dashed #c0c4cc inset;
  cursor: not-allowed;
}
.cell.swap-checking {
  background: #f7f9ff;
}
.cell.swap-source {
  background: #eef5ff;
  outline: 2px solid #409eff inset;
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
.lesson.locked {
  border: 1px dashed #5c6b8a;
}
/* Inset ring keeps the locked dashed border and the conflict outline visible. */
.lesson.selected {
  box-shadow: inset 0 0 0 3px #409eff;
}
/* Swap mode keeps the course colour but rings the semantic state. */
.lesson.swap-swappable {
  box-shadow: inset 0 0 0 2px #2e9e5b;
}
.lesson.swap-source {
  box-shadow: inset 0 0 0 2px #409eff;
}
.lesson.swap-blocked,
.lesson.swap-foreign {
  opacity: 0.72;
}
.lesson:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: 2px;
}
.lock-mark {
  margin-left: 6px;
  padding: 0 4px;
  border-radius: 4px;
  background: rgba(92, 107, 138, 0.16);
  color: #40506e;
  font-size: 11px;
  font-weight: 600;
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
