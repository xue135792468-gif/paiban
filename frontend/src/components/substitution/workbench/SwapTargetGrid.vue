<template>
  <div class="target-grid" data-testid="swap-target-grid" :class="{ dragging }">
    <div class="grid-head">
      <span class="grid-title">
        行政班周课表 · {{ entry.className || '本班' }}（拖拽设置调课目标）
      </span>
      <span class="hint">
        把来源课拖到同班「当天该槽有课」的格子；空格、窗口外与来源课本身不可投放，
        目标槽位与表单入口同源于后端 swap-targets
      </span>
    </div>

    <div v-loading="anyLoading" class="grid" :style="{ gridTemplateColumns: gridColumns }">
      <div class="corner">节次</div>
      <div
        v-for="day in gridDays"
        :key="`head-${day}`"
        class="day-head"
        :class="{ 'source-day': day === lessonDate }"
      >
        <div>{{ weekdayLabel(day) }}</div>
        <div class="day-date">{{ day.slice(5) }}</div>
      </div>
      <template v-for="row in rows" :key="`row-${row}`">
        <div class="row-head">第{{ row }}节</div>
        <div
          v-for="day in gridDays"
          :key="`cell-${day}-${row}`"
          class="cell"
          :class="cellClass(day, row)"
          :data-swap-cell="true"
          :data-cell-key="`${day}|${row}`"
          :data-cell-state="verdictOf(day, row).state"
          :data-drop-valid="verdictOf(day, row).state === 'valid' ? 'true' : 'false'"
          :title="verdictOf(day, row).reason"
          :data-testid="verdictOf(day, row).state === 'source' ? 'swap-drag-source' : `swap-cell-${day}-${row}`"
          @pointerdown="onCellPointerDown($event, day, row)"
        >
          <!-- 来源课：可拖拽；命中槽位：可投放；其余格子给出不可用原因 -->
          <template v-if="verdictOf(day, row).state === 'source'">
            <div class="lesson-course">{{ entry.courseName }}</div>
            <div class="lesson-meta">{{ entry.teacherName }}</div>
            <el-tag size="small" type="warning" class="source-tag">来源课 · 可拖动</el-tag>
          </template>
          <template v-else-if="verdictOf(day, row).state === 'valid'">
            <div class="lesson-course">{{ verdictOf(day, row).slot?.courseName }}</div>
            <div class="lesson-meta">{{ verdictOf(day, row).slot?.teacherName }}</div>
          </template>
          <template v-else-if="verdictOf(day, row).state === 'before-source' || verdictOf(day, row).state === 'error'">
            <div class="cell-reason">{{ shortReason(verdictOf(day, row).reason) }}</div>
          </template>
          <template v-else-if="verdictOf(day, row).state === 'loading'">
            <div class="cell-reason">加载中…</div>
          </template>
        </div>
      </template>
    </div>

    <!-- 拖拽跟随提示：只表达意图，松手前不产生任何状态变更 -->
    <div
      v-if="dragging && ghost"
      class="ghost"
      data-testid="swap-drag-ghost"
      :style="{ left: `${ghost.x}px`, top: `${ghost.y}px` }"
    >
      {{ entry.courseName }} → 同班目标格
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { evaluateDropCell, weekDates, weekdayLabel } from './swapTargetRules'
import type { DropCellVerdict, SwapTargetDayState } from './swapTargetRules'
import type { SwapTargetsResponse } from './types'
import type { Entry } from '../types'

const props = defineProps<{
  /** 来源课（含 classId/dayOfWeek/periodNo，位置即当前实际安排）。 */
  entry: Entry
  lessonDate: string
  /** 所选日期所在周的周一（列头日期）。 */
  weekStart: string
  /** 方案生成中暂停拖拽，避免半途改目标。 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 松手在有效目标格：带目标日期与课节（periodId）交回面板，走表单同一条路径。 */
  dropTarget: [target: { date: string; periodId: number; periodNo: number; courseName: string }]
  /** 松手在网格内的无效格子：给出明确不可用原因，不产生任何预览或写入。 */
  invalidDrop: [reason: string]
}>()

const gridDays = computed(() => weekDates(props.weekStart))
const gridColumns = '64px repeat(5, minmax(96px, 1fr))'

/** 每天的 swap-targets 结果：早于来源日的格子不发请求（判定规则直接给窗口外原因）。 */
const dayStates = ref<Record<string, SwapTargetDayState>>({})

const anyLoading = computed(() =>
  gridDays.value.some((date) => (dayStates.value[date]?.status ?? 'loading') === 'loading'),
)

const rows = computed(() => {
  const periodNos = new Set<number>([props.entry.periodNo])
  for (const state of Object.values(dayStates.value)) {
    for (const slot of state.slots) periodNos.add(slot.periodNo)
  }
  return [...periodNos].sort((a, b) => a - b)
})

const cellVerdicts = computed(() => {
  const map = new Map<string, DropCellVerdict>()
  for (const date of gridDays.value) {
    for (const row of rows.value) {
      map.set(
        `${date}|${row}`,
        evaluateDropCell({
          date,
          periodNo: row,
          lessonDate: props.lessonDate,
          sourcePeriodNo: props.entry.periodNo,
          day: dayStates.value[date] ?? { status: 'loading', slots: [] },
        }),
      )
    }
  }
  return map
})

function verdictOf(date: string, periodNo: number): DropCellVerdict {
  return (
    cellVerdicts.value.get(`${date}|${periodNo}`) ?? {
      state: 'loading',
      reason: '目标槽位加载中',
    }
  )
}

function cellClass(date: string, periodNo: number) {
  const verdict = verdictOf(date, periodNo)
  return {
    'cell-source': verdict.state === 'source',
    'cell-valid': verdict.state === 'valid',
    'cell-disabled': verdict.state !== 'valid' && verdict.state !== 'source',
    'cell-drop-hover': dragging.value && verdict.state === 'valid' && hoverKey.value === `${date}|${periodNo}`,
  }
}

function shortReason(reason?: string) {
  if (!reason) return ''
  // 窗口外格子展示日期级原因；后端拒绝原因可能较长，截断到一行内。
  return reason.length > 18 ? `${reason.slice(0, 18)}…` : reason
}

async function loadWeekTargets() {
  await Promise.all(
    gridDays.value.map(async (date) => {
      if (date < props.lessonDate) {
        // 窗口外的天不发请求：显式落状态，避免 anyLoading 永远为真遮住网格。
        dayStates.value[date] = { status: 'before-source', slots: [] }
        return
      }
      try {
        const { data } = await http.get<SwapTargetsResponse>('/substitution-workbench/swap-targets', {
          params: { entryId: props.entry.id, lessonDate: props.lessonDate, targetDate: date },
        })
        dayStates.value[date] = { status: 'ok', slots: data.slots }
      } catch (error) {
        // 该天不可作目标的原因原样展示（后端对窗口/学期的裁决就是唯一标准）。
        dayStates.value[date] = {
          status: 'error',
          slots: [],
          error: serverMessage(error, '目标槽位加载失败，请重试'),
        }
      }
    }),
  )
}

// ------------------------------------------------------------ 指针拖拽（桌面）
// 选择 Pointer Events 而非 HTML5 DnD：Playwright 的 mouse.move/down/up 走真实
// 输入管线即可稳定驱动；命中测试用 elementFromPoint（视口坐标），与票 08 的
// overflow 滚动容器互不干扰，也不会触发原生拖拽滚动。

const dragging = ref(false)
const hoverKey = ref('')
const ghost = ref<{ x: number; y: number } | null>(null)
let startX = 0
let startY = 0
let pressActive = false

function onCellPointerDown(event: PointerEvent, date: string, periodNo: number) {
  if (props.disabled || event.button !== 0) return
  if (verdictOf(date, periodNo).state !== 'source') return
  // 源格内没有可点击子元素，阻止默认行为只为避免拖动时选中文本。
  event.preventDefault()
  startX = event.clientX
  startY = event.clientY
  pressActive = true
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', cancelDrag)
}

function onPointerMove(event: PointerEvent) {
  if (!pressActive) return
  if (!dragging.value) {
    // 位移超过阈值才算拖拽，避免误触。
    if (Math.hypot(event.clientX - startX, event.clientY - startY) < 4) return
    dragging.value = true
  }
  event.preventDefault()
  ghost.value = { x: event.clientX, y: event.clientY }
  hoverKey.value = hitCellKey(event.clientX, event.clientY) ?? ''
}

function onPointerUp(event: PointerEvent) {
  const wasDragging = dragging.value
  const key = hitCellKey(event.clientX, event.clientY)
  cleanupDrag()
  if (!wasDragging) return
  const verdict = key ? cellVerdicts.value.get(key) : undefined
  if (verdict?.state === 'valid' && verdict.slot) {
    const [date] = (key ?? '').split('|')
    emit('dropTarget', {
      date,
      periodId: verdict.slot.periodId,
      periodNo: verdict.slot.periodNo,
      courseName: verdict.slot.courseName ?? '',
    })
  } else if (verdict && verdict.state !== 'loading') {
    // 网格内的无效格子：给出明确原因；加载中或网格外松手视为放弃，不打扰。
    emit('invalidDrop', verdict.reason ?? '该格不能作为调课目标')
  }
}

function cancelDrag() {
  cleanupDrag()
}

function cleanupDrag() {
  pressActive = false
  dragging.value = false
  hoverKey.value = ''
  ghost.value = null
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', cancelDrag)
}

/** 指针捕获不在格子上，命中测试按视口坐标反查（ghost 为 pointer-events:none）。 */
function hitCellKey(x: number, y: number): string | null {
  const element = document.elementFromPoint(x, y)
  const cell = element?.closest('[data-swap-cell]') as HTMLElement | null
  return cell?.dataset.cellKey ?? null
}

onBeforeUnmount(cleanupDrag)

loadWeekTargets()
</script>

<style scoped>
.target-grid {
  margin-top: 10px;
  position: relative;
}
.grid-head {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 6px;
}
.grid-title {
  font-weight: 600;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
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
  min-height: 48px;
}
.day-head {
  border-radius: 6px;
  font-weight: 600;
  padding: 4px;
  text-align: center;
}
.day-head.source-day {
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
  min-height: 48px;
  padding: 4px;
}
.cell-source {
  border: 1px dashed var(--el-color-primary);
  cursor: grab;
}
.cell-valid {
  background: var(--el-color-primary-light-9);
  cursor: pointer;
}
.cell-disabled {
  color: var(--paiban-muted);
}
.dragging {
  user-select: none;
}
.dragging .cell-valid {
  outline: 2px dashed var(--el-color-primary);
}
.dragging .cell-disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.dragging .cell-drop-hover {
  background: var(--el-color-primary-light-7);
  outline: 2px solid var(--el-color-primary);
}
.lesson-course {
  font-weight: 600;
}
.lesson-meta {
  color: var(--paiban-muted);
  font-size: 12px;
}
.source-tag {
  margin-top: 2px;
}
.cell-reason {
  font-size: 12px;
  line-height: 1.4;
  padding: 4px 2px;
}
.ghost {
  background: var(--el-color-primary);
  border-radius: 6px;
  color: #fff;
  font-size: 12px;
  padding: 4px 10px;
  pointer-events: none;
  position: fixed;
  transform: translate(-50%, -50%);
  z-index: 2000;
}
</style>
