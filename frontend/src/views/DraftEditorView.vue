<template>
  <div v-loading="loading" class="draft-page">
    <DraftEditorToolbar
      :draft-name="detail?.draft.name"
      :status="detail?.draft.status"
      :undo-count="undoCount"
      :can-undo="canUndo"
      :undoing="undoing"
      :busy="busy"
      :can-edit="canEdit"
      :can-publish="canPublish"
      :publishable="publishable"
      :is-junior="isJunior"
      @back="router.push('/admin/scheduling')"
      @undo="undoMove"
      @refresh="load"
      @compare="openCompare"
      @rename="rename"
      @publish="publishDraft"
      @remove="removeDraft"
    />
    <DraftStats
      :hard-conflicts="hardConflicts"
      :score-label="scoreLabel"
      :lesson-count="detail?.lessons.length ?? 0"
      :unassigned-count="unassigned.length"
    />

    <DraftPublishBlockers
      v-if="detail?.draft.status !== 'PUBLISHED'"
      :blockers="blockers"
      :room-fill="canFillRooms"
      @edit-lessons="drawerVisible = true"
      @fill-rooms="roomFillVisible = true"
    />

    <DraftOptimizationSection
      v-if="detail?.draft.status === 'DRAFT'"
      :draft="detail?.draft ?? null"
      :source-draft-id="draftId"
      :semester-id="detail?.draft.semesterId"
      :can-optimize="canOptimize"
      :locked-count="lockedCount"
      :lesson-count="detail?.lessons.length ?? 0"
    />

    <RoomFillDialog v-model="roomFillVisible" :draft-id="draftId" @applied="load" />

    <el-card shadow="never" class="control-card">
      <DraftEditorControls
        v-model:view-mode="viewMode"
        v-model:target-id="targetId"
        :target-options="targetOptions"
        :selected-lesson="selectedLesson"
        :draft-editable="draftEditable"
        :unassigned-count="unassigned.length"
        :selection-mode="selectionMode"
        :selected-count="selectedCount"
        :selected-lesson-locked="selectedLesson?.locked ?? false"
        :can-use-class-scope="canUseClassScope"
        :day-options="dayOptions"
        :class-scope-count="classScopeCount"
        @view-change="resetTarget"
        @swap="openSwapForSelected"
        @adjust-position="openTargetDialog"
        @toggle-drawer="drawerVisible = !drawerVisible"
        @clear-selection="clearSelection"
        @toggle-selection="toggleSelectionMode"
        @lock-single="lockSingle"
        @unlock-single="unlockSingle"
        @batch-lock="confirmBatch"
        @clear-multi-selection="clearLockSelection"
      />
    </el-card>

    <div class="layout">
      <el-card shadow="never" class="grid-card">
        <DraftGrid
          :periods="detail?.periods ?? []"
          :lessons="filteredLessons"
          :courses="courses"
          :draggable="draftEditable && !selectionMode"
          :dragged-lesson-id="dragged?.id ?? null"
          :selection-mode="selectionMode"
          :selected-lesson-ids="selectedIds"
          :targets="dragTargets"
          :blocked-periods="blockedPeriods"
          :conflict-lesson-ids="validation?.conflicts.map((item) => item.lessonId).filter((id) => id != null) ?? []"
          :locked-lesson-ids="lockedLessonIds"
          :show-room="false"
          @drag-start="onDragStart"
          @drag-end="onDragEnd"
          @drop-cell="onDropCell"
          @cell-click="onCellClick"
          @lesson-activate="onLessonActivate"
          @toggle-select="onToggleSelect"
        />
      </el-card>

      <el-card v-if="drawerVisible" shadow="never" class="drawer-card">
        <template #header>
          <div class="drawer-header">
            <span>未排课节</span>
            <el-button link @click="drawerVisible = false">收起</el-button>
          </div>
        </template>
        <div v-if="unassigned.length === 0" class="drawer-empty">没有未排课的授课任务</div>
        <div
          v-for="lesson in unassigned"
          :key="lesson.id"
          class="drawer-lesson"
          :style="{ background: courseColor(courses, lesson.courseId) }"
          :draggable="draftEditable"
          @dragstart="onDragStart(lesson)"
          @dragend="onDragEnd"
        >
          <div class="lesson-course">{{ lesson.courseName }}</div>
          <div class="lesson-meta">{{ lesson.className }} · {{ lesson.teacherName }}</div>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="conflictVisible" title="冲突原因" width="520px">
      <el-alert
        v-if="cellConflicts.length === 0"
        title="该位置没有冲突"
        type="success"
        :closable="false"
        show-icon
      />
      <ul v-else class="conflict-list">
        <li v-for="(conflict, index) in cellConflicts" :key="index">{{ conflict.message }}</li>
      </ul>
    </el-dialog>

    <DraftTargetDialog
      v-model="targetVisible"
      :periods="detail?.periods ?? []"
      :current-period-id="selectedLesson?.periodId ?? null"
      :locked-period-ids="lockedPeriodIds"
      :unavailable-period-ids="unavailablePeriodIds"
      @select="onTargetPick"
    />

    <CyclePlanPanel
      v-model="plansVisible"
      :status="plansStatus"
      :message="plansMessage"
      :plans="plans"
      :lessons="detail?.lessons ?? []"
      :periods="detail?.periods ?? []"
      :applying="planApplying"
      @apply="onApplyPlan"
      @cancel="closePlans"
    />

    <DraftCompareDialog
      v-if="!isJunior"
      v-model="compareVisible"
      :draft-id="draftId"
      :semester-id="detail?.draft.semesterId"
      :source-draft-id="compareSourceId"
      :compare-with-draft-id="draftId"
    />

    <el-drawer
      v-model="panelVisible"
      title="跨班换人"
      size="620px"
      destroy-on-close
      @closed="closePanel"
    >
      <SwapCandidatePanel
        :lesson="panelLesson"
        :response="candidates"
        :loading="candidatesLoading"
        :include-unavailable="includeUnavailable"
        :initial-group="initialGroup"
        :single-group="swapSingleGroup"
        @update:include-unavailable="setIncludeUnavailable"
        @select="selectCandidate"
      />
    </el-drawer>

    <el-dialog v-model="previewVisible" title="换课预览" width="92%" top="4vh">
      <DualTimetablePreview
        v-if="panelLesson && previewCandidate"
        v-model:swap-rooms="swapRooms"
        :periods="detail?.periods ?? []"
        :lessons="detail?.lessons ?? []"
        :courses="courses"
        :source="panelLesson"
        :candidate="previewCandidate"
      />
      <template #footer>
        <el-button @click="closePreview">取消</el-button>
        <el-button type="primary" :loading="swapping" @click="onConfirmSwap">确认换课</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import DraftGrid from '@/components/draft/DraftGrid.vue'
import DraftCompareDialog from '@/components/draft/DraftCompareDialog.vue'
import DraftStats from '@/components/draft/DraftStats.vue'
import DraftPublishBlockers from '@/components/draft/DraftPublishBlockers.vue'
import DraftOptimizationSection from '@/components/draft/DraftOptimizationSection.vue'
import RoomFillDialog from '@/components/draft/RoomFillDialog.vue'
import SwapCandidatePanel from '@/components/draft/SwapCandidatePanel.vue'
import DualTimetablePreview from '@/components/draft/DualTimetablePreview.vue'
import DraftEditorToolbar from '@/components/draft/DraftEditorToolbar.vue'
import DraftEditorControls from '@/components/draft/DraftEditorControls.vue'
import DraftTargetDialog from '@/components/draft/DraftTargetDialog.vue'
import CyclePlanPanel from '@/components/draft/CyclePlanPanel.vue'
import { courseColor } from '@/components/draft/colors'
import { useDraftAdjust } from '@/composables/useDraftAdjust'
import { useDraftEditor } from '@/composables/useDraftEditor'
import { useDraftDrop } from '@/composables/useDraftDrop'
import { useCyclePlans } from '@/composables/useCyclePlans'
import { useSwapFlow } from '@/composables/useSwapFlow'
import { useDraftLocks } from '@/composables/useDraftLocks'
import { useUndoShortcut } from '@/composables/useUndoShortcut'
import { useDraftCompareQuery } from '@/composables/useDraftCompareQuery'
import { useDraftActions } from '@/composables/useDraftActions'
import type { CyclePlan, DraftConflict, DraftLesson } from '@/components/draft/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const settings = useSettingsStore()
const isJunior = computed(() => settings.schoolType === 'junior')
const draftId = Number(route.params.id)
const canEdit = computed(() => auth.hasPermission('timetable:edit'))
const canPublish = computed(() => auth.hasPermission('timetable:publish'))
// Filling rooms needs two permissions; that pair is defined in the auth store.
const canFillRooms = computed(() => auth.canFillDraftRooms)
const canOptimize = computed(() => auth.hasPermission('scheduling:run'))
const lockedCount = computed(
  () => (detail.value?.lessons ?? []).filter((lesson) => lesson.locked).length,
)
const { compareVisible, compareSourceId, openCompare } = useDraftCompareQuery()

const adjust = useDraftAdjust(draftId)
const { undoCount, canUndo, undoing, busy, undo } = adjust
const cyclePlans = useCyclePlans({ adjust })
const {
  visible: plansVisible,
  status: plansStatus,
  message: plansMessage,
  plans,
  applying: planApplying,
  close: closePlans,
} = cyclePlans

const {
  selectedLesson,
  panelVisible,
  panelLesson,
  candidates,
  candidatesLoading,
  includeUnavailable,
  initialGroup,
  previewVisible,
  previewCandidate,
  swapRooms,
  swapping,
  selectLesson,
  clearSelection,
  openCrossClassForSelected,
  singleGroup: swapSingleGroup,
  setIncludeUnavailable,
  selectCandidate,
  confirm: confirmSwap,
  closePanel,
  closePreview,
} = useSwapFlow({
  adjust,
  // Read at confirm time: an old preview must not overwrite a newer draft version.
  getVersion: () => detail.value?.draft.version ?? null,
})

const {
  loading,
  detail,
  validation,
  courses,
  viewMode,
  targetId,
  draftEditable,
  blockedPeriods,
  filteredLessons,
  unassigned,
  hardConflicts,
  blockers,
  publishable,
  scoreLabel,
  targetOptions,
  draftVersion,
  rulesFingerprint,
  resetTarget,
  load,
  lessonAt,
} = useDraftEditor({ draftId, canEdit, onLoaded: () => clearSelection() })

const locks = useDraftLocks({
  draftId, detail, draftEditable, viewMode, targetId, reload: load, notify: ElMessage,
})
const {
  selectionMode, selectedIds, selectedCount, dayOptions, classScopeCount, canUseClassScope,
  lockedLessonIds, toggleSelectionMode, clearSelection: clearLockSelection, confirmBatch,
} = locks
const { toggle: toggleLockSelection, lockSingle, unlockSingle } = locks

const drawerVisible = ref(false)
const dragged = ref<DraftLesson | null>(null)
const dragTargets = ref<Record<number, { available: boolean; conflicts: DraftConflict[] }> | null>(null)
const conflictVisible = ref(false)
const cellConflicts = ref<DraftConflict[]>([])
const roomFillVisible = ref(false)
const targetVisible = ref(false)

const lockedPeriodIds = computed(() =>
  (detail.value?.lessons ?? [])
    .filter(
      (lesson) =>
        lesson.locked &&
        lesson.periodId != null &&
        lesson.classId === selectedLesson.value?.classId,
    )
    .map((lesson) => lesson.periodId!),
)

const unavailablePeriodIds = computed(() => {
  const ids = new Set<number>()
  const classId = selectedLesson.value?.classId
  detail.value?.lessons.forEach((lesson) => {
    if (lesson.periodId != null && classId != null && lesson.classId !== classId) {
      ids.add(lesson.periodId)
    }
  })
  Object.keys(blockedPeriods.value).forEach((periodId) => ids.add(Number(periodId)))
  return [...ids]
})

const { drop } = useDraftDrop({
  detail,
  draftEditable,
  adjust,
  cyclePlans,
  reload: load,
  notify: ElMessage,
  showConflicts,
})

function showConflicts(conflicts: DraftConflict[]) {
  cellConflicts.value = conflicts
  conflictVisible.value = true
}

async function onDragStart(lesson: DraftLesson) {
  dragged.value = lesson
  dragTargets.value = null
  const targets = await adjust.loadMoveTargets(lesson.id)
  if (dragged.value?.id === lesson.id) {
    dragTargets.value = targets
  }
}

function onDragEnd() {
  dragged.value = null
  dragTargets.value = null
}

async function onDropCell(payload: {
  day: number
  periodNo: number
  periodId: number
  lessonIds: number[]
}) {
  const lesson = dragged.value
  if (!lesson) return
  await drop(lesson, payload.periodId, payload.lessonIds)
  onDragEnd()
}

function onCellClick(payload: { day: number; periodNo: number }) {
  if (selectionMode.value) return
  const lesson = lessonAt(payload.day, payload.periodNo)
  if (!lesson) return
  selectLesson(lesson)
  const related = (validation.value?.conflicts ?? []).filter((conflict) => conflict.lessonId === lesson.id)
  if (related.length > 0) {
    showConflicts(related)
  }
}

function onLessonActivate(lesson: DraftLesson) {
  if (selectionMode.value) {
    toggleLockSelection(lesson.id)
    return
  }
  selectLesson(lesson)
}

function onToggleSelect(lesson: DraftLesson) {
  toggleLockSelection(lesson.id)
}

function openSwapForSelected() {
  if (selectedLesson.value?.locked) return ElMessage.warning('该课节已锁定，先解锁后再换课')
  openCrossClassForSelected()
}

function openTargetDialog() {
  if (!selectedLesson.value || selectedLesson.value.periodId == null) return
  if (selectedLesson.value.locked) return ElMessage.warning('该课节已锁定，先解锁后再选择位置')
  targetVisible.value = true
}

async function onTargetPick(periodId: number) {
  const lesson = selectedLesson.value
  if (!lesson) return
  const visibleIds = filteredLessons.value
    .filter((item) => item.periodId === periodId)
    .map((item) => item.id)
  await drop(lesson, periodId, visibleIds)
}

async function onApplyPlan(plan: CyclePlan) {
  try {
    await ElMessageBox.confirm(
      '应用后整组课节会一起换位，并作为一步撤销。确定应用这个方案？',
      '应用联动方案',
      { type: 'warning' },
    )
  } catch {
    return
  }
  const outcome = await cyclePlans.apply(plan, draftVersion.value, detail.value?.lessons ?? [])
  if (outcome.saved) {
    await load()
  } else if ('conflicts' in outcome && outcome.conflicts.length > 0) {
    showConflicts(outcome.conflicts)
    await load()
  } else {
    await load()
  }
}

async function onConfirmSwap() {
  const outcome = await confirmSwap()
  if (outcome.saved) {
    await load()
    return
  }
  if (outcome.conflicts.length > 0) {
    showConflicts(outcome.conflicts)
  }
  // A refusal or a stale version still needs the authoritative grid back.
  await load()
}

async function undoMove() {
  if (busy.value || undoing.value) return
  const outcome = await undo({
    expectedVersion: draftVersion.value,
    rulesFingerprint: rulesFingerprint.value,
  })
  if (outcome.undone) {
    await load()
    return
  }
  if (outcome.conflicts && outcome.conflicts.length > 0) {
    showConflicts(outcome.conflicts)
  }
  if (outcome.error || (outcome.conflicts && outcome.conflicts.length > 0)) {
    await load()
  }
}

useUndoShortcut({ canUndo, busy, undo: undoMove })

const { rename, publish: publishDraft, remove: removeDraft } = useDraftActions({
  draftId,
  detail,
  reload: load,
})

onMounted(() => {
  void load()
})
</script>

<style scoped>
.draft-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.control-card {
  border: none;
}
.layout {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.grid-card {
  flex: 1;
  border: none;
  overflow: auto;
}
.drawer-card {
  width: 240px;
  border: none;
}
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.drawer-empty {
  color: var(--paiban-muted);
  font-size: 13px;
  text-align: center;
  padding: 20px 0;
}
.drawer-lesson {
  margin-bottom: 8px;
  border-radius: 8px;
  padding: 6px 8px;
  cursor: grab;
  border: 1px solid rgba(0, 0, 0, 0.06);
  color: #2b3245;
  overflow: hidden;
}
.drawer-lesson .lesson-course {
  font-weight: 600;
  font-size: 13px;
}
.drawer-lesson .lesson-meta {
  font-size: 11px;
  margin-top: 3px;
  color: #4b5563;
}
.conflict-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
  color: #b43b3b;
}
</style>
