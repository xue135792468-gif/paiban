<template>
  <div class="preview" data-testid="cycle-plan-teacher-preview">
    <div class="pv-head">
      <div class="pv-row">
        <el-button
          v-if="showBack"
          text
          class="pv-back"
          data-testid="cycle-plan-preview-back"
          @click="emit('back')"
        >
          ‹ 返回方案
        </el-button>
        <h4 class="pv-title" data-testid="cycle-plan-preview-title">{{ teacherName }} · 完整周课表</h4>
        <span class="pv-sub">{{ kindLabel }} · 含该教师全部行政班</span>
        <span class="pv-spacer" />
        <el-radio-group v-model="modeProxy" size="small" data-testid="cycle-plan-preview-mode">
          <el-radio-button value="after" :disabled="!projection.available">应用后预览</el-radio-button>
          <el-radio-button value="current">当前安排</el-radio-button>
        </el-radio-group>
      </div>

      <div v-if="teacherChips.length > 1" class="pv-chips" data-testid="cycle-plan-preview-teachers">
        <span class="pv-sub">本方案涉及教师：</span>
        <button
          v-for="chip in teacherChips"
          :key="chip.teacherId"
          type="button"
          class="pv-chip"
          :class="{ on: chip.teacherId === teacherId }"
          :data-testid="`cycle-plan-preview-teacher-${chip.teacherId}`"
          @click="emit('update:teacherId', chip.teacherId)"
        >
          {{ chip.teacherName }}
        </button>
      </div>

      <p class="pv-notice" data-testid="cycle-plan-preview-notice">
        {{
          !projection.available
            ? '当前显示：草稿里的实际安排。方案有课节与当前草稿不一致，整组都不会应用，因此不提供应用后预览。'
            : isAfter
              ? '当前显示：应用后预览。整组联动同时投影在当前草稿上，方案尚未生效，课表没有被修改。'
              : '当前显示：草稿里的实际安排。高亮课节是本方案准备移动的课，括注写明移动后的位置。'
        }}
      </p>
    </div>

    <div class="pv-grid">
      <DraftGrid
        :periods="periods"
        :lessons="teacherLessons"
        :courses="courses"
        :draggable="false"
        compact
        show-class-name
        :highlight-lesson-ids="affectedIds"
        :lesson-notes="lessonNotes"
        :vacated-periods="vacatedPeriods"
      />
    </div>

    <section class="pv-detail" data-testid="cycle-plan-preview-moves">
      <h5>{{ teacherName }} 在本方案中的移动</h5>
      <ul v-if="teacherMoves.length">
        <li v-for="row in teacherMoves" :key="row.move.lessonId">
          {{ row.courseName }}<template v-if="row.className">（{{ row.className }}）</template>：
          {{ row.fromLabel }}
          <span class="arrow">→</span>
          {{ row.toLabel }}
          <span v-if="!row.projected" class="skip">（未投影：{{ row.reason }}）</span>
        </li>
      </ul>
      <p v-else class="pv-empty">该教师在本方案中没有课节移动。</p>
    </section>

    <p v-if="!projection.available" class="pv-warning" data-testid="cycle-plan-preview-unmatched">
      本方案 {{ projection.moves.length }} 步中没有一步投影：任一课节与当前草稿不符，服务端会整单拒绝。
      请刷新草稿或重新搜索方案。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import DraftGrid from './DraftGrid.vue'
import {
  affectedLessonIds,
  applyProjectionToLessons,
  lessonsForTeacher,
  projectCyclePlan,
  teacherNameOfId,
} from './cyclePlanPreview'
import type { CyclePlanPreviewMode } from './cyclePlanPreview'
import type { CyclePlan, DraftCourse, DraftLesson, DraftPeriod } from './types'

const props = withDefaults(
  defineProps<{
    plan: CyclePlan
    lessons: DraftLesson[]
    periods: DraftPeriod[]
    courses: DraftCourse[]
    /** Selected by id so two teachers with the same name never collide. */
    teacherId: number
    mode?: CyclePlanPreviewMode
    /** Only the narrow-screen view offers a back affordance. */
    showBack?: boolean
  }>(),
  { mode: 'after', showBack: false },
)

const emit = defineEmits<{
  (e: 'update:mode', value: CyclePlanPreviewMode): void
  (e: 'update:teacherId', value: number): void
  (e: 'back'): void
}>()

const projection = computed(() => projectCyclePlan(props.lessons, props.periods, props.plan))

/**
 * A plan that cannot be projected at all has no「应用后」to show, so the preview stays on the
 * draft view whatever mode the panel asked for; the selector and the grid then agree.
 */
const effectiveMode = computed<CyclePlanPreviewMode>(() =>
  projection.value.available ? props.mode : 'current',
)
const isAfter = computed(() => effectiveMode.value === 'after')

const modeProxy = computed({
  get: () => effectiveMode.value,
  set: (value: CyclePlanPreviewMode) => emit('update:mode', value),
})

const teacherChips = computed(() =>
  projection.value.teacherIds.map((teacherId) => ({
    teacherId,
    teacherName: teacherNameOfId(projection.value, teacherId),
  })),
)

const teacherName = computed(() => teacherNameOfId(projection.value, props.teacherId))
const kindLabel = computed(() => (props.plan.kind === 'DIRECT' ? '直接交换' : '联动换位'))

/** The whole plan is projected at once, then narrowed to the selected teacher. */
const projectedLessons = computed(() =>
  isAfter.value ? applyProjectionToLessons(props.lessons, projection.value) : props.lessons,
)
const teacherLessons = computed(() => lessonsForTeacher(projectedLessons.value, props.teacherId))
const affectedIds = computed(() => affectedLessonIds(projection.value, props.teacherId))
const teacherMoves = computed(() =>
  projection.value.moves.filter((row) => row.teacherId === props.teacherId),
)

const lessonNotes = computed(() => {
  const notes: Record<number, string> = {}
  teacherMoves.value.forEach((row) => {
    if (!row.projected) return
    notes[row.move.lessonId] = isAfter.value
      ? `原${row.fromLabel} → 现${row.toLabel}`
      : `应用后移至 ${row.toLabel}`
  })
  return notes
})

/**
 * Applied view: an original slot left behind is annotated only when it really is
 * empty for this teacher, because an exchange fills both sides with the other lesson.
 * Occupancy comes from the teacher's own week, not from the whole draft: another class
 * of another teacher sitting in the freed slot must not erase the explanation.
 */
const vacatedPeriods = computed(() => {
  const map: Record<number, string> = {}
  if (!isAfter.value) return map
  const occupied = new Set(teacherLessons.value.map((lesson) => lesson.periodId))
  teacherMoves.value.forEach((row) => {
    if (!row.projected) return
    if (occupied.has(row.move.fromPeriodId)) return
    map[row.move.fromPeriodId] = `这节课已移至 ${row.toLabel}`
  })
  return map
})
</script>

<style scoped>
.preview {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.pv-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pv-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.pv-back {
  padding: 0 4px;
  font-size: 14px;
}
.pv-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.pv-sub {
  font-size: 12px;
  color: var(--paiban-muted);
}
.pv-spacer {
  flex: 1;
}
.pv-chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.pv-chip {
  padding: 3px 11px;
  border-radius: 14px;
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #303133;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
.pv-chip.on {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}
.pv-notice {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #b88230;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  padding: 6px 10px;
}
.pv-grid {
  overflow: auto;
  max-height: 56vh;
  border: 1px solid #eef0f5;
  border-radius: 8px;
}
.pv-detail h5 {
  margin: 0 0 6px;
  font-size: 13px;
}
.pv-detail ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
}
.arrow {
  color: var(--paiban-muted);
  padding: 0 3px;
}
.skip {
  color: #b42318;
  font-size: 12px;
}
.pv-empty,
.pv-warning {
  margin: 0;
  font-size: 13px;
}
.pv-warning {
  color: #b42318;
  line-height: 1.6;
}
</style>
