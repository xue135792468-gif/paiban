<template>
  <div class="preview" data-testid="dual-timetable-preview">
    <div class="preview-head">
      <div class="summary">
        <el-tag size="small" :type="type === 'INTRA_CLASS' ? 'primary' : 'warning'" effect="light">
          {{ type === 'INTRA_CLASS' ? '同班换位' : '跨班换人' }}
        </el-tag>
        <span class="summary-text">{{ summary }}</span>
      </div>
      <div class="preview-actions">
        <el-checkbox
          v-if="type === 'INTRA_CLASS'"
          :model-value="swapRooms"
          @update:model-value="(value: string | number | boolean) => emit('update:swapRooms', Boolean(value))"
        >
          同时交换教室
        </el-checkbox>
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="before">调整前</el-radio-button>
          <el-radio-button value="after">调整后</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <el-alert
      v-if="candidate.roomSwapRequired"
      type="info"
      :closable="false"
      show-icon
      class="preview-warnings"
      title="该换位需要同时交换教室，已自动勾选「同时交换教室」"
    />

    <el-alert
      v-if="candidate.warnings.length > 0"
      type="warning"
      :closable="false"
      show-icon
      class="preview-warnings"
    >
      <template #title>{{ candidate.warnings.join('；') }}</template>
    </el-alert>

    <div class="panes">
      <section class="pane">
        <header class="pane-head">
          <span class="pane-teacher">{{ source.teacherName }}</span>
          <span class="pane-hint">的课表</span>
          <el-tag v-if="sameTeacher" size="small" type="info" effect="plain">与右侧为同一教师</el-tag>
        </header>
        <div class="pane-grid">
          <DraftGrid
            :periods="periods"
            :lessons="leftLessons"
            :courses="courses"
            :draggable="false"
            :highlight-lesson-ids="highlightIds"
            :show-room="false"
            compact
          />
        </div>
      </section>
      <section class="pane">
        <header class="pane-head">
          <span class="pane-teacher">{{ candidate.teacherName }}</span>
          <span class="pane-hint">的课表</span>
        </header>
        <div class="pane-grid">
          <DraftGrid
            :periods="periods"
            :lessons="rightLessons"
            :courses="courses"
            :draggable="false"
            :highlight-lesson-ids="highlightIds"
            :show-room="false"
            compact
          />
        </div>
      </section>
    </div>
    <p class="preview-hint">蓝框标出受影响的两节课；切换「调整前 / 调整后」核对变化后再确认。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DraftGrid from './DraftGrid.vue'
import { buildSwapPreview, swapTypeOf, teacherPaneLessons } from './swapPreview'
import type { DraftCourse, DraftLesson, DraftPeriod, SwapCandidate } from './types'

const props = defineProps<{
  periods: DraftPeriod[]
  lessons: DraftLesson[]
  courses: DraftCourse[]
  source: DraftLesson
  candidate: SwapCandidate
  swapRooms: boolean
}>()

const emit = defineEmits<{
  (e: 'update:swapRooms', value: boolean): void
}>()

const mode = ref<'before' | 'after'>('before')
const type = computed(() => swapTypeOf(props.source, props.candidate))
const preview = computed(() =>
  buildSwapPreview(props.lessons, props.source, props.candidate, props.periods, props.swapRooms),
)
const snapshot = computed(() => (mode.value === 'after' ? preview.value.after : preview.value.before))
const leftLessons = computed(() => teacherPaneLessons(snapshot.value, props.source.teacherId))
const rightLessons = computed(() => teacherPaneLessons(snapshot.value, props.candidate.teacherId))
const highlightIds = computed(() => [props.source.id, props.candidate.lessonId])
const sameTeacher = computed(() => props.source.teacherId === props.candidate.teacherId)
const summary = computed(() => {
  if (type.value === 'INTRA_CLASS') {
    return `${props.source.courseName} ${props.source.periodLabel ?? ''} ⇄ ${props.candidate.courseName} ${props.candidate.periodLabel ?? ''}（同班换位，节次互换）`
  }
  return `${props.source.teacherName} ⇄ ${props.candidate.teacherName}（跨班换人，节次与教室不变）`
})

watch(
  () => props.candidate.lessonId,
  () => {
    mode.value = 'before'
  },
)
</script>

<style scoped>
.preview {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.summary {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.summary-text {
  color: #4b5563;
}
.preview-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.panes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  align-items: start;
}
.pane {
  border: 1px solid #eef0f5;
  border-radius: 8px;
  overflow: hidden;
}
.pane-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: #f8fafc;
  font-size: 13px;
}
.pane-teacher {
  font-weight: 600;
}
.pane-hint {
  color: var(--paiban-muted);
}
.pane-grid {
  overflow: auto;
  max-height: 62vh;
}
.preview-hint {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 12px;
}
</style>
