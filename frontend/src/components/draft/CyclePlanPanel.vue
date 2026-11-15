<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    :width="dialogWidth"
    class="cycle-plan-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="panel-body" :class="{ 'preview-full': isNarrow && previewOpen }">
      <div ref="listPane" class="list-pane" data-testid="cycle-plan-list-pane">
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
            :class="{ active: index === activeIndex }"
            :data-testid="`cycle-plan-${index}`"
            @click="selectPlan(index)"
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
              <li v-for="move in plan.moves" :key="move.lessonId" class="move-item">
                <div class="move-who">
                  <button
                    type="button"
                    class="teacher-link"
                    :data-testid="`cycle-plan-teacher-${index}-${move.teacherId}`"
                    @click.stop="openPreview(index, move.teacherId)"
                  >
                    {{ teacherNameOf(move) }}
                  </button>
                  <span class="move-class">{{ classNameOf(move.lessonId) }}</span>
                </div>
                <div class="move-when">
                  {{ lessonLabel(move.lessonId) }}：{{ periodLabel(move.fromPeriodId) }}
                  <span class="arrow">→</span>
                  {{ periodLabel(move.toPeriodId) }}
                </div>
              </li>
            </ul>
            <div class="card-foot">
              <el-button
                type="primary"
                size="small"
                data-testid="cycle-plan-apply"
                :loading="applying"
                :disabled="applying"
                @click.stop="emit('apply', plan)"
              >
                应用这个方案
              </el-button>
              <span class="foot-hint">点教师姓名先看这份课表</span>
            </div>
          </div>
          <div v-if="plans.length === 0" class="empty-plans">没有可展示的方案</div>
        </div>
      </div>

      <div v-if="previewTeacherId !== null && activePlan" class="preview-pane">
        <CyclePlanTeacherPreview
          :plan="activePlan"
          :lessons="lessons"
          :periods="periods"
          :courses="courses"
          :teacher-id="previewTeacherId"
          :mode="previewMode"
          :show-back="isNarrow"
          @update:mode="previewMode = $event"
          @update:teacher-id="previewTeacherId = $event"
          @back="closePreview"
        />
      </div>
    </div>

    <template #footer>
      <el-button :disabled="applying" @click="cancel">{{ cancelLabel }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CyclePlanTeacherPreview from './CyclePlanTeacherPreview.vue'
import { periodCaption } from './cyclePlanPreview'
import type { CyclePlanPreviewMode } from './cyclePlanPreview'
import type { CyclePlan, CyclePlanMove, DraftCourse, DraftLesson, DraftPeriod } from './types'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    status?: 'FOUND' | 'NONE' | 'BUDGET_EXHAUSTED' | null
    message?: string
    plans: CyclePlan[]
    lessons?: DraftLesson[]
    periods?: DraftPeriod[]
    /** Course colours for the preview grid; the plan list itself does not use them. */
    courses?: DraftCourse[]
    loading?: boolean
    applying?: boolean
  }>(),
  {
    status: null,
    message: '',
    lessons: () => [],
    periods: () => [],
    courses: () => [],
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

const listPane = ref<HTMLElement | null>(null)
const activeIndex = ref(0)
const previewTeacherId = ref<number | null>(null)
const previewMode = ref<CyclePlanPreviewMode>('after')
const savedScroll = ref<number | null>(null)
const isNarrow = ref(false)

const activePlan = computed(() => props.plans[activeIndex.value] ?? null)
const previewOpen = computed(() => previewTeacherId.value !== null && !!activePlan.value)
const dialogWidth = computed(() => {
  if (isNarrow.value) return '100%'
  return previewOpen.value ? 'min(1280px, 96vw)' : '720px'
})
const dialogTitle = computed(() => (previewOpen.value ? '联动方案 · 教师课表预览' : '联动方案'))
const cancelLabel = computed(() => {
  if (previewOpen.value) return isNarrow.value ? '返回方案列表' : '关闭预览'
  return '取消'
})

function signed(value: number) {
  return value > 0 ? `+${value}` : `${value}`
}

/** The preview names teachers the same way, so a card and its chips never disagree. */
function teacherNameOf(move: CyclePlanMove) {
  const lesson = lessonById.value.get(move.lessonId)
  return lesson?.teacherName ?? `教师 #${move.teacherId}`
}

function lessonLabel(lessonId: number) {
  return lessonById.value.get(lessonId)?.courseName ?? `课节 #${lessonId}`
}

function classNameOf(lessonId: number) {
  const className = lessonById.value.get(lessonId)?.className
  return className ? `· ${className}` : ''
}

function periodLabel(periodId: number) {
  return periodCaption(props.periods, periodId)
}

function teachersOf(plan: CyclePlan | null): number[] {
  if (!plan) return []
  const ids: number[] = []
  plan.moves.forEach((move) => {
    if (!ids.includes(move.teacherId)) ids.push(move.teacherId)
  })
  return ids
}

/**
 * Switching plans always re-projects from the current draft: the mode goes back to the
 * applied preview and the teacher falls back to one this plan actually touches.
 */
function selectPlan(index: number) {
  if (index === activeIndex.value) return
  activeIndex.value = index
  previewMode.value = 'after'
  if (previewTeacherId.value !== null) {
    const candidates = teachersOf(props.plans[index])
    if (candidates.includes(previewTeacherId.value)) return
    previewTeacherId.value = candidates[0] ?? null
    if (previewTeacherId.value === null) closePreview()
  }
}

function openPreview(index: number, teacherId: number) {
  if (index !== activeIndex.value) {
    activeIndex.value = index
    previewMode.value = 'after'
  }
  if (isNarrow.value && savedScroll.value === null) {
    savedScroll.value = listPane.value?.scrollTop ?? 0
  }
  previewTeacherId.value = teacherId
}

function closePreview() {
  previewTeacherId.value = null
  previewMode.value = 'after'
  const keep = savedScroll.value
  savedScroll.value = null
  if (keep !== null) {
    nextTick(() => {
      if (listPane.value) listPane.value.scrollTop = keep
    })
  }
}

function cancel() {
  // While a preview is open the footer button means "back to the plans", not "close".
  if (previewOpen.value) {
    closePreview()
    return
  }
  savedScroll.value = null
  emit('cancel')
  emit('update:modelValue', false)
}

let media: MediaQueryList | null = null

function syncNarrow() {
  isNarrow.value = media?.matches ?? false
}

onMounted(() => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  media = window.matchMedia('(max-width: 900px)')
  syncNarrow()
  media.addEventListener('change', syncNarrow)
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', syncNarrow)
})

// Turning back to a wide screen leaves no full-screen preview to return from.
watch(isNarrow, (narrow) => {
  if (!narrow) savedScroll.value = null
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    activeIndex.value = 0
    previewTeacherId.value = null
    previewMode.value = 'after'
    savedScroll.value = null
  },
)

watch(
  () => props.plans,
  (plans) => {
    if (activeIndex.value >= plans.length) activeIndex.value = 0
    const candidates = teachersOf(props.plans[activeIndex.value])
    if (previewTeacherId.value !== null && !candidates.includes(previewTeacherId.value)) {
      closePreview()
    }
  },
)
</script>

<style scoped>
.panel-body {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  min-height: 0;
}
.list-pane {
  flex: none;
  width: 420px;
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 2px;
}
.preview-pane {
  flex: 1;
  min-width: 0;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 12px;
  max-height: 62vh;
  overflow: auto;
}
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
  background: #fff;
  cursor: pointer;
}
.plan-card.active {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.12);
}
.plan-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.plan-stats {
  font-size: 12px;
  color: var(--paiban-muted);
}
.move-list {
  margin: 0 0 10px;
  padding: 0;
  list-style: none;
  font-size: 13px;
}
.move-item {
  padding: 5px 0;
  border-top: 1px dashed #ebeef5;
  line-height: 1.6;
}
.move-item:first-child {
  border-top: 0;
}
.move-who {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}
.move-class {
  font-size: 12px;
  color: var(--paiban-muted);
}
.move-when {
  font-size: 12px;
  color: #4b5563;
}
.teacher-link {
  border: 0;
  background: none;
  padding: 0;
  font: inherit;
  font-weight: 600;
  color: #409eff;
  cursor: pointer;
  border-bottom: 1px dashed transparent;
}
.teacher-link:hover {
  border-bottom-color: #409eff;
}
.arrow {
  color: var(--paiban-muted);
  padding: 0 2px;
}
.card-foot {
  display: flex;
  align-items: center;
  gap: 8px;
}
.foot-hint {
  font-size: 12px;
  color: var(--paiban-muted);
}
.empty-plans {
  color: var(--paiban-muted);
  font-size: 13px;
  text-align: center;
  padding: 20px 0;
}
@media (max-width: 900px) {
  .panel-body {
    gap: 0;
  }
  .list-pane {
    width: 100%;
    /* `max-height: none` left the pane as tall as its cards, so the phone scrolled Element
       Plus' overlay and never this element: the position saved on the way into the preview was
       always 0. The pane stays the scroller here: 15vh above the dialog and 50px below it stay
       with the viewport, and the remaining 170px belongs to the dialog padding, header, footer
       and the pane's own edge. `vh` is the fallback for browsers without dynamic viewport units. */
    max-height: calc(85vh - 170px);
    max-height: calc(85dvh - 170px);
  }
  /* A phone gets one full-screen view: the preview replaces the plan list. */
  .panel-body.preview-full .list-pane {
    display: none;
  }
  .preview-pane {
    width: 100%;
    max-height: none;
    border: 0;
    border-radius: 0;
    padding: 0;
  }
}
</style>
