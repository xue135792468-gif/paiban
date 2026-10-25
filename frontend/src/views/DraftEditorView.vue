<template>
  <div v-loading="loading" class="draft-page">
    <div class="toolbar">
      <div>
        <el-button link @click="router.push('/admin/scheduling')">
          <el-icon><ArrowLeft /></el-icon>
          <span>返回排课</span>
        </el-button>
        <h2>
          {{ detail?.draft.name ?? '课表草稿' }}
          <el-tag
            size="small"
            :type="detail?.draft.status === 'PUBLISHED' ? 'success' : 'info'"
            effect="light"
          >
            {{ detail?.draft.status === 'PUBLISHED' ? '已发布' : '草稿' }}
          </el-tag>
        </h2>
        <p class="subtitle">
          拖拽课节到其他时段即可调整。拖动时绿色表示可放置，红色表示有冲突，点击红格查看原因。可撤销最近 20
          次调整。
        </p>
      </div>
      <div class="actions">
        <el-button :disabled="!canUndo || undoing" @click="undoMove">
          <el-icon><RefreshLeft /></el-icon>
          <span>撤销{{ undoCount > 0 ? `（${undoCount}）` : '' }}</span>
        </el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="!isJunior" @click="openCompare">比较草稿</el-button>
        <el-button v-if="canEdit" @click="rename">重命名</el-button>
        <el-button
          v-if="canPublish && detail?.draft.status !== 'PUBLISHED'"
          type="success"
          :disabled="!publishable"
          @click="publishDraft"
        >
          发布课表
        </el-button>
        <el-button v-if="canEdit" type="danger" plain @click="removeDraft">删除</el-button>
      </div>
    </div>
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

    <RoomFillDialog v-model="roomFillVisible" :draft-id="draftId" @applied="load" />

    <el-card shadow="never" class="control-card">
      <div class="controls">
        <el-radio-group v-model="viewMode" @change="resetTarget">
          <el-radio-button value="class">按行政班</el-radio-button>
          <el-radio-button value="teacher">按教师</el-radio-button>
          <el-radio-button value="room">按教室</el-radio-button>
        </el-radio-group>
        <el-select v-model="targetId" filterable style="width: 220px" placeholder="选择对象">
          <el-option
            v-for="option in targetOptions"
            :key="option.id"
            :label="option.name"
            :value="option.id"
          />
        </el-select>
        <div class="spacer" />
        <template v-if="selectedLesson">
          <el-tag type="primary" effect="plain" closable @close="clearSelection">
            已选：{{ selectedLesson.courseName }} · {{ selectedLesson.teacherName }} ·
            {{ selectedLesson.periodLabel }}
          </el-tag>
          <el-button
            type="primary"
            :disabled="!draftEditable || selectedLesson.periodId == null"
            @click="openForSelected"
          >
            换课
          </el-button>
        </template>
        <el-button @click="drawerVisible = !drawerVisible">
          <el-icon><Tickets /></el-icon>
          <span>未排课节（{{ unassigned.length }}）</span>
        </el-button>
      </div>
    </el-card>

    <div class="layout">
      <el-card shadow="never" class="grid-card">
        <DraftGrid
          :periods="detail?.periods ?? []"
          :lessons="filteredLessons"
          :courses="courses"
          :draggable="draftEditable"
          :dragged-lesson-id="dragged?.id ?? null"
          :targets="dragTargets"
          @drag-start="onDragStart"
          @drag-end="onDragEnd"
          @drop-cell="onDropCell"
          @cell-click="onCellClick"
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

    <DraftCompareDialog
      v-if="!isJunior"
      v-model="compareVisible"
      :draft-id="draftId"
      :semester-id="detail?.draft.semesterId"
    />

    <el-drawer
      v-model="panelVisible"
      title="换课候选"
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
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, RefreshLeft, Tickets } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import DraftGrid from '@/components/draft/DraftGrid.vue'
import DraftCompareDialog from '@/components/draft/DraftCompareDialog.vue'
import DraftStats from '@/components/draft/DraftStats.vue'
import DraftPublishBlockers from '@/components/draft/DraftPublishBlockers.vue'
import RoomFillDialog from '@/components/draft/RoomFillDialog.vue'
import SwapCandidatePanel from '@/components/draft/SwapCandidatePanel.vue'
import DualTimetablePreview from '@/components/draft/DualTimetablePreview.vue'
import { courseColor } from '@/components/draft/colors'
import { useDraftAdjust } from '@/composables/useDraftAdjust'
import { useSwapFlow } from '@/composables/useSwapFlow'
import type {
  Draft,
  DraftConflict,
  DraftCourse,
  DraftLesson,
  DraftPeriod,
  DraftRoom,
  DraftValidation,
  MoveTargetState,
} from '@/components/draft/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const settings = useSettingsStore()
const isJunior = computed(() => settings.schoolType === 'junior')
const draftId = Number(route.params.id)
const canEdit = computed(() => auth.hasPermission('timetable:edit'))
const canPublish = computed(() => auth.hasPermission('timetable:publish'))
// Filling rooms creates main data as well as editing the draft, so the server wants both.
const canFillRooms = computed(
  () => auth.hasPermission('timetable:edit') && auth.hasPermission('foundation:manage'),
)

const adjust = useDraftAdjust(draftId)
const { undoCount, canUndo, undoing, move, undo, loadMoveTargets } = adjust
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
  openPanel,
  openForSelected,
  setIncludeUnavailable,
  selectCandidate,
  confirm: confirmSwap,
  closePanel,
  closePreview,
} = useSwapFlow({ adjust })

const loading = ref(false)
type DraftDetail = { draft: Draft; lessons: DraftLesson[]; periods: DraftPeriod[]; rooms: DraftRoom[] }
const detail = ref<DraftDetail | null>(null)
const draftEditable = computed(() => canEdit.value && detail.value?.draft.status === 'DRAFT')
const validation = ref<DraftValidation | null>(null)
const courses = ref<DraftCourse[]>([])
const viewMode = ref<'class' | 'teacher' | 'room'>('class')
const targetId = ref<number | null>(null)
const drawerVisible = ref(false)
const dragged = ref<DraftLesson | null>(null)
const dragTargets = ref<Record<number, MoveTargetState> | null>(null)
const conflictVisible = ref(false)
const cellConflicts = ref<DraftConflict[]>([])
const compareVisible = ref(false)
const roomFillVisible = ref(false)

const filteredLessons = computed(() => {
  const lessons = detail.value?.lessons ?? []
  if (targetId.value == null) return lessons
  return lessons.filter((lesson) => {
    if (viewMode.value === 'class') return lesson.classId === targetId.value
    if (viewMode.value === 'teacher') return lesson.teacherId === targetId.value
    return lesson.roomId === targetId.value
  })
})

const unassigned = computed(() => filteredLessons.value.filter((lesson) => !lesson.periodId))

const hardConflicts = computed(() => validation.value?.hardConflicts ?? 0)
// The server's whole-draft judgement is the only opinion on whether publishing can work.
const blockers = computed(() => validation.value?.blockers ?? [])
const publishable = computed(() => validation.value?.publishable ?? true)
const scoreLabel = computed(
  () => `${detail.value?.draft.scoreHard ?? 0} hard / ${detail.value?.draft.scoreSoft ?? 0} soft`,
)

const targetOptions = computed(() => {
  if (viewMode.value === 'room') {
    return (detail.value?.rooms ?? []).map((room) => ({ id: room.id, name: room.name }))
  }
  const map = new Map<number, string>()
  detail.value?.lessons.forEach((lesson) => {
    if (viewMode.value === 'class') {
      map.set(lesson.classId, lesson.className ?? `行政班${lesson.classId}`)
    } else {
      map.set(lesson.teacherId, lesson.teacherName ?? `教师${lesson.teacherId}`)
    }
  })
  return [...map.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
})

async function load() {
  loading.value = true
  try {
    const [detailRes, validationRes, courseRes] = await Promise.all([
      http.get(`/timetable-drafts/${draftId}`),
      http.get(`/timetable-drafts/${draftId}/validation`),
      http.get('/courses'),
    ])
    detail.value = detailRes.data
    validation.value = validationRes.data
    courses.value = courseRes.data
    if (targetId.value == null) resetTarget()
    clearSelection()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '加载草稿失败'))
  } finally {
    loading.value = false
  }
}

function resetTarget() {
  const options = targetOptions.value
  targetId.value = options.length > 0 ? options[0].id : null
}

function showConflicts(conflicts: DraftConflict[]) {
  cellConflicts.value = conflicts
  conflictVisible.value = true
}

async function onDragStart(lesson: DraftLesson) {
  dragged.value = lesson
  dragTargets.value = null
  const targets = await loadMoveTargets(lesson.id)
  if (dragged.value?.id === lesson.id) {
    dragTargets.value = targets
  }
}

function onDragEnd() {
  dragged.value = null
  dragTargets.value = null
}

async function onDropCell(payload: { day: number; periodNo: number; periodId: number }) {
  const lesson = dragged.value
  if (!lesson) return
  const state = dragTargets.value?.[payload.periodId]
  if (!state) return
  if (!state.available) {
    const occupied = filteredLessons.value.find(
      (item) =>
        item.dayOfWeek === payload.day &&
        item.periodNo === payload.periodNo &&
        item.id !== lesson.id,
    )
    if (occupied && occupied.classId === lesson.classId && draftEditable.value) {
      openPanel(lesson, 'INTRA_CLASS')
      onDragEnd()
      return
    }
    if (occupied && occupied.classId !== lesson.classId) {
      ElMessage.warning(
        `该节次属于${occupied.className ?? '其他班级'}的课，跨班只能换老师、不能换节次`,
      )
    }
    showConflicts(state.conflicts)
    onDragEnd()
    return
  }
  const outcome = await move(lesson, { periodId: payload.periodId, roomId: lesson.roomId ?? null })
  if (outcome.conflicts.length > 0) {
    showConflicts(outcome.conflicts)
  }
  if (outcome.saved || outcome.conflicts.length > 0) {
    await load()
  }
  onDragEnd()
}

function onCellClick(payload: { day: number; periodNo: number }) {
  const lesson = filteredLessons.value.find(
    (item) => item.dayOfWeek === payload.day && item.periodNo === payload.periodNo,
  )
  if (!lesson) return
  selectLesson(lesson)
  const related = (validation.value?.conflicts ?? []).filter((conflict) => conflict.lessonId === lesson.id)
  if (related.length > 0) {
    showConflicts(related)
  }
}

async function onConfirmSwap() {
  const outcome = await confirmSwap()
  if (outcome.saved) {
    await load()
  } else if (outcome.conflicts.length > 0) {
    showConflicts(outcome.conflicts)
  }
}

async function undoMove() {
  const outcome = await undo()
  if (!outcome.undone) {
    if (outcome.conflicts && outcome.conflicts.length > 0) {
      showConflicts(outcome.conflicts)
    }
    return
  }
  await load()
}

function onKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'z') return
  const target = event.target as HTMLElement | null
  const tag = target?.tagName?.toLowerCase()
  if (tag === 'input' || tag === 'textarea' || target?.isContentEditable) return
  if (!canUndo.value) return
  event.preventDefault()
  void undoMove()
}

async function rename() {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('输入新的草稿名称', '重命名草稿', {
      inputValue: detail.value.draft.name,
      inputValidator: (input) => Boolean(input && input.trim()),
    })
    await http.put(`/timetable-drafts/${draftId}/name`, { name: value })
    ElMessage.success('已重命名')
    await load()
  } catch {
    // cancelled
  }
}

async function publishDraft() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      '发布后该草稿将成为正式课表基线，后续变更需走调代流程。确定发布？',
      '发布课表',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    const { data } = await http.post(`/timetable-drafts/${draftId}/publish`)
    ElMessage.success(`课表已发布，共 ${data.entryCount} 节课`)
    await load()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '发布失败'))
  }
}

async function removeDraft() {
  try {
    await ElMessageBox.confirm('确定删除该草稿吗？删除后无法恢复。', '删除草稿', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/timetable-drafts/${draftId}`)
    ElMessage.success('已删除')
    router.push('/admin/scheduling')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '删除失败'))
  }
}

async function openCompare() {
  if (isJunior.value) return
  compareVisible.value = true
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  void load()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.draft-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
h2 {
  margin: 4px 0;
  font-size: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 8px;
}
.control-card {
  border: none;
}
.controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.spacer {
  flex: 1;
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
