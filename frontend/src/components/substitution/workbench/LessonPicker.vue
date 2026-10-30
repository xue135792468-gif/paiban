<template>
  <section class="panel cur" data-testid="wizard-panel-1">
    <div class="phead">
      <span class="idx">1</span>
      <h2>选哪节课</h2>
      <span class="hint">列头是实际日期，只能调整今天及以后的课</span>
    </div>
    <div class="pbody">
      <div class="toolbar picker-card">
        <label class="field">
          <span>教师</span>
          <el-select
            :model-value="teacherId"
            filterable
            placeholder="搜索教师姓名"
            style="width: 100%"
            data-testid="teacher-select"
            @update:model-value="onTeacherChange"
          >
            <el-option v-for="option in teachers" :key="option.id" :label="option.name" :value="option.id" />
          </el-select>
        </label>
        <label class="field">
          <span>日期</span>
          <span class="date-wrap" data-testid="lesson-date">
            <el-date-picker
              :model-value="date"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled-date="pickerDisabledDate"
              placeholder="选择日期（浏览该周）"
              style="width: 100%"
              @update:model-value="onDateChange"
            />
          </span>
        </label>
      </div>

      <div v-if="!teacherId" class="empty">先选一位教师，再看他这一周的课表。</div>
      <div v-else-if="loading" class="empty">课表加载中…</div>
      <div v-else-if="timetableError" class="empty" data-testid="timetable-error">
        课表加载失败：{{ timetableError }}
        <button type="button" class="retry" @click="emit('retry')">重试</button>
      </div>
      <div v-else-if="entries.length === 0" class="empty">该教师本学期没有排课，或所选周没有课。</div>
      <template v-else>
        <div v-if="!narrow" class="wgrid" :class="{ dragging }" data-testid="workbench-timetable">
          <div class="gh">节次</div>
          <div v-for="day in days" :key="`head-${day.date}`" class="gh">
            <b>{{ day.label }}</b>{{ day.monthDay }}
          </div>
          <template v-for="row in rows" :key="`row-${row}`">
            <div class="rp">第 {{ row }} 节</div>
            <div
              v-for="day in days"
              :key="`cell-${day.date}-${row}`"
              class="cell"
              :class="{ empty: !cellAt(day.index, row) }"
              :data-drop-cell="true"
              :data-cell-key="`${day.date}|${row}`"
              :data-cell-date="day.date"
              :data-cell-entry="cellAt(day.index, row)?.id ?? ''"
              :data-testid="`workbench-cell-${day.date}-${row}`"
            >
              <button
                v-if="cellAt(day.index, row)"
                type="button"
                class="lesson"
                :class="{
                  chosen: cellAt(day.index, row)!.id === selectedEntryId,
                  'is-past': isPast(day.date),
                }"
                :disabled="isPast(day.date)"
                :aria-disabled="isPast(day.date) ? 'true' : 'false'"
                :title="isPast(day.date) ? '已过去的日期不能调整' : undefined"
                data-testid="pick-lesson"
                :aria-pressed="cellAt(day.index, row)!.id === selectedEntryId ? 'true' : 'false'"
                @pointerdown="isPast(day.date) ? undefined : onPointerDown($event, cellAt(day.index, row)!)"
                @click="isPast(day.date) ? undefined : onLessonClick(cellAt(day.index, row)!)"
              >
                <span class="c">
                  {{ cellAt(day.index, row)!.courseName }} · {{ cellAt(day.index, row)!.teacherName }}
                  <span v-if="cellAt(day.index, row)!.substituted" class="tag">已调整</span>
                </span>
                <span class="m">
                  {{ cellAt(day.index, row)!.className }} ·
                  {{ cellAt(day.index, row)!.roomName || '未排教室' }}
                </span>
              </button>
            </div>
          </template>
        </div>

        <div v-else class="mlist" data-testid="workbench-timetable">
          <!-- ≤520px：列表只保留点击选课；不挂 pointerdown、不做投放格，
               touch-action 恢复默认，避免拖拽手势吃掉列表的原生滚动。 -->
          <button
            v-for="item in mobileLessons"
            :key="`m-${item.entry.id}`"
            type="button"
            class="lesson"
            :class="{
              chosen: item.entry.id === selectedEntryId,
              'is-past': isPast(item.date),
            }"
            :disabled="isPast(item.date)"
            :aria-disabled="isPast(item.date) ? 'true' : 'false'"
            :title="isPast(item.date) ? '已过去的日期不能调整' : undefined"
            data-testid="pick-lesson"
            :aria-pressed="item.entry.id === selectedEntryId ? 'true' : 'false'"
            @click="isPast(item.date) ? undefined : onLessonClick(item.entry)"
          >
            <span class="c">
              {{ item.day.label }} {{ item.day.monthDay }} · 第{{ item.entry.periodNo }}节 ·
              {{ item.entry.courseName }} · {{ item.entry.teacherName }}
              <span v-if="item.entry.substituted" class="tag">已调整</span>
            </span>
            <span class="m">{{ item.entry.className }} · {{ item.entry.roomName || '未排教室' }}</span>
          </button>
        </div>
      </template>

      <div v-if="dragInvalidReason" class="callout" data-testid="swap-drop-invalid" role="alert">
        <div class="cw"><span class="ic">!</span><span>{{ dragInvalidReason }}</span></div>
        <span class="cap">没有发出任何请求，也没有改动课表。</span>
      </div>
    </div>

    <div v-if="dragging && ghost && dragSource" class="ghost" :style="{ left: `${ghost.x}px`, top: `${ghost.y}px` }">
      {{ dragSource.courseName }} → 放开以预选调课
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { isoOf, weekDates, weekdayLabel } from './swapTargetRules'
import { isPastDate, today } from '../types'
import type { Entry } from '../types'

const props = withDefaults(
  defineProps<{
    teachers: Array<{ id: number; name: string }>
    teacherId: number | null
    date: string
    minDate?: string
    disabledDate?: (d: Date) => boolean
    entries: Entry[]
    loading: boolean
    timetableError: string
    weekStart: string
    selectedEntryId: number | null
    dragInvalidReason: string
  }>(),
  {
    minDate: '',
    disabledDate: undefined,
  },
)

const emit = defineEmits<{
  'update:teacherId': [value: number | null]
  'update:date': [value: string]
  pick: [entry: Entry]
  drop: [payload: { source: Entry; target: Entry | null; targetDate: string }]
  retry: []
  'clear-drag-invalid': []
}>()

function isPast(dateIso: string): boolean {
  if (props.minDate) return dateIso < props.minDate
  return isPastDate(dateIso)
}

function pickerDisabledDate(d: Date): boolean {
  if (props.disabledDate) return props.disabledDate(d)
  return isoOf(d) < (props.minDate || today())
}

const days = computed(() =>
  weekDates(props.weekStart).map((date, index) => ({
    date,
    index: index + 1,
    label: weekdayLabel(date),
    monthDay: date.slice(5).replace('-', '/'),
  })),
)

const rows = computed(() =>
  [...new Set(props.entries.map((entry) => entry.periodNo))]
    .filter((periodNo) => periodNo > 0)
    .sort((a, b) => a - b),
)

const cellsByKey = computed(() => {
  const map = new Map<string, Entry>()
  for (const entry of props.entries) {
    map.set(`${entry.dayOfWeek}|${entry.periodNo}`, entry)
  }
  return map
})

function cellAt(dayOfWeek: number, periodNo: number): Entry | null {
  return cellsByKey.value.get(`${dayOfWeek}|${periodNo}`) ?? null
}

const mobileLessons = computed(() =>
  days.value.flatMap((day) =>
    props.entries
      .filter((entry) => entry.dayOfWeek === day.index)
      .sort((a, b) => a.periodNo - b.periodNo)
      .map((entry) => ({ date: day.date, day, entry })),
  ),
)

// ---------------------------------------------------------------- 响应式
// 520px 以下改用按天列表；网格与列表二选一渲染（不是靠 CSS 隐藏），
// 让 e2e 的 toHaveCount 有确定的语义。
const narrow = ref(false)
let media: MediaQueryList | null = null

function syncNarrow() {
  narrow.value = media?.matches ?? false
}

onMounted(() => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  media = window.matchMedia('(max-width: 520px)')
  syncNarrow()
  media.addEventListener('change', syncNarrow)
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', syncNarrow)
})

// ------------------------------------------------------------ 指针拖拽
// 与票 06 同一节拍：pointerdown → 位移超过阈值才算拖拽 → pointerup 命中
// [data-drop-cell]；只表达意图，判定与请求都由父级（纯函数 + 表单路径）负责。
const dragging = ref(false)
const ghost = ref<{ x: number; y: number } | null>(null)
const dragSource = ref<Entry | null>(null)
let startX = 0
let startY = 0
let pressEntry: Entry | null = null
let suppressClick = false

function onTeacherChange(value: unknown) {
  emit('update:teacherId', typeof value === 'number' ? value : null)
}

function onDateChange(value: unknown) {
  if (typeof value === 'string' && value) emit('update:date', value)
}

function onLessonClick(entry: Entry) {
  if (suppressClick) {
    suppressClick = false
    return
  }
  emit('pick', entry)
}

function onPointerDown(event: PointerEvent, entry: Entry) {
  if (event.button !== 0) return
  pressEntry = entry
  startX = event.clientX
  startY = event.clientY
  window.addEventListener('pointermove', onPointerMove, { passive: false })
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', cancelDrag)
}

function onPointerMove(event: PointerEvent) {
  if (!pressEntry) return
  if (!dragging.value) {
    if (Math.hypot(event.clientX - startX, event.clientY - startY) < 4) return
    dragging.value = true
    dragSource.value = pressEntry
    emit('clear-drag-invalid')
  }
  event.preventDefault()
  ghost.value = { x: event.clientX, y: event.clientY }
}

function onPointerUp(event: PointerEvent) {
  const wasDragging = dragging.value
  const source = pressEntry
  cleanupDrag()
  if (!wasDragging || !source) return
  // 拖拽结束后浏览器仍可能派发一次 click（回到源卡时）；抑制它，
  // 否则 pick 会清掉刚显示的无效投放原因。
  suppressClick = true
  window.setTimeout(() => {
    suppressClick = false
  }, 0)

  const element = document.elementFromPoint(event.clientX, event.clientY) as HTMLElement | null
  const cell = element?.closest('[data-drop-cell]') as HTMLElement | null
  if (!cell) return
  const targetDate = cell.dataset.cellDate ?? ''
  const entryId = Number(cell.dataset.cellEntry || 0)
  const target = entryId > 0 ? props.entries.find((item) => item.id === entryId) ?? null : null
  emit('drop', { source, target, targetDate })
}

function cancelDrag() {
  cleanupDrag()
}

function cleanupDrag() {
  pressEntry = null
  dragging.value = false
  ghost.value = null
  dragSource.value = null
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', cancelDrag)
}

onBeforeUnmount(cleanupDrag)
</script>

<style scoped>
.panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  min-width: 0;
  overflow: hidden;
}
.panel.cur {
  border-left: 3px solid var(--brand);
}
.phead {
  align-items: center;
  border-bottom: 1px solid var(--line);
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
  padding: 16px 24px;
}
.idx {
  align-items: center;
  background: var(--brand-soft);
  border-radius: 50%;
  color: var(--brand);
  display: grid;
  flex: none;
  font-size: 12px;
  height: 22px;
  place-items: center;
  width: 22px;
}
.phead h2 {
  font-size: 15px;
  font-weight: 500;
  margin: 0;
}
.hint {
  color: var(--muted);
  font-size: 12px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.pbody {
  display: flex;
  flex-direction: column;
  gap: 24px;
  min-width: 0;
  padding: 24px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.field {
  display: flex;
  flex-direction: column;
  flex: 0 1 220px;
  gap: 5px;
  min-width: 0;
}
.field > span {
  color: var(--muted);
  font-size: 12px;
}
.empty {
  color: var(--muted);
  padding: 24px 0;
  text-align: center;
}
.retry {
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 8px;
  color: var(--text);
  cursor: pointer;
  font: inherit;
  font-size: 12.5px;
  margin-left: 8px;
  padding: 2px 12px;
}
.retry:hover {
  border-color: var(--brand);
  color: var(--brand);
}
.wgrid {
  background: var(--inset);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  display: grid;
  gap: 8px;
  grid-template-columns: 56px repeat(5, minmax(0, 1fr));
  padding: 10px;
}
.wgrid.dragging {
  user-select: none;
}
.wgrid.dragging .cell {
  outline: 1px dashed var(--line-2);
  outline-offset: -1px;
}
.gh {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.4;
  text-align: center;
}
.gh b {
  color: var(--text);
  display: block;
  font-size: 13px;
  font-weight: 500;
}
.rp {
  align-items: center;
  color: var(--muted);
  display: flex;
  font-size: 12px;
  justify-content: center;
  text-align: center;
}
.cell {
  min-height: 62px;
  min-width: 0;
}
.cell.empty {
  background: transparent;
  border: 1px dashed var(--line-2);
  border-radius: 8px;
}
.lesson {
  background: var(--surface);
  border: 1px solid var(--line-2);
  border-radius: 8px;
  color: var(--text);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  font: inherit;
  gap: 1px;
  height: 100%;
  justify-content: center;
  min-height: 62px;
  padding: 10px;
  text-align: left;
  width: 100%;
}
/* 只有网格课卡参与指针拖拽；列表项保持默认 touch-action，竖向滚动不被吃掉。 */
.wgrid .lesson {
  touch-action: none;
}
.lesson:hover {
  border-color: var(--brand);
}
.lesson.chosen {
  background: var(--brand-soft);
  border-color: var(--brand);
  box-shadow: inset 0 0 0 1px var(--brand);
}
.lesson.is-past {
  cursor: not-allowed;
  opacity: 0.5;
}
.lesson.is-past:hover {
  border-color: var(--line-2);
}
.lesson .c {
  font-size: 13px;
  font-weight: 500;
}
.lesson .m {
  color: var(--muted);
  font-size: 12px;
}
.tag {
  background: var(--warn-soft);
  border: 1px solid color-mix(in srgb, var(--warn) 40%, transparent);
  border-radius: 999px;
  color: var(--warn);
  display: inline-block;
  font-size: 11px;
  font-weight: 400;
  line-height: 16px;
  padding: 0 6px;
}
.mlist {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mlist .lesson {
  min-height: 0;
}
.callout {
  background: var(--warn-soft);
  border: 1px solid color-mix(in srgb, var(--warn) 38%, transparent);
  border-radius: var(--radius-sm);
  color: var(--warn);
  display: flex;
  flex-direction: column;
  font-size: 13px;
  gap: 8px;
  padding: 12px 14px;
}
.callout .cw {
  align-items: flex-start;
  display: flex;
  gap: 8px;
}
.callout .ic {
  align-items: center;
  background: var(--warn);
  border-radius: 50%;
  color: var(--on-brand);
  display: grid;
  flex: none;
  font-size: 12px;
  font-weight: 500;
  height: 18px;
  margin-top: 2px;
  place-items: center;
  width: 18px;
}
.callout .cap {
  color: var(--warn);
  font-size: 12px;
  opacity: 0.85;
}
.ghost {
  background: var(--brand);
  border-radius: 6px;
  color: var(--on-brand);
  font-size: 12px;
  padding: 4px 10px;
  pointer-events: none;
  position: fixed;
  transform: translate(-50%, -50%);
  z-index: 2000;
}
@media (max-width: 900px) {
  .phead {
    padding: 12px 16px;
  }
  .pbody {
    padding: 16px;
  }
}
@media (max-width: 520px) {
  .toolbar .field {
    flex: 1 1 100%;
  }
  .phead {
    padding: 12px 14px;
  }
  .pbody {
    padding: 14px;
  }
}
</style>
