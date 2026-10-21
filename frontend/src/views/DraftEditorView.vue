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
          @click="publishDraft"
        >
          发布课表
        </el-button>
        <el-button v-if="canEdit" type="danger" plain @click="removeDraft">删除</el-button>
      </div>
    </div>

    <div class="stats">
      <div class="stat">
        <span class="stat-label">硬冲突</span>
        <span class="stat-value" :class="{ danger: hardConflicts > 0 }">{{ hardConflicts }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">得分</span>
        <span class="stat-value">{{ scoreLabel }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">课节数</span>
        <span class="stat-value">{{ detail?.lessons.length ?? 0 }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">未排课</span>
        <span class="stat-value" :class="{ danger: unassigned.length > 0 }">{{ unassigned.length }}</span>
      </div>
    </div>

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
          :draggable="canEdit && detail?.draft.status !== 'PUBLISHED'"
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
          :draggable="canEdit && detail?.draft.status !== 'PUBLISHED'"
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

    <el-dialog v-if="!isJunior" v-model="compareVisible" title="并排比较草稿" width="1120px">
      <div class="compare-grid">
        <div class="compare-side">
          <el-select v-model="compareA" placeholder="选择草稿 A" style="width: 100%">
            <el-option v-for="item in allDrafts" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <div v-if="draftSummary(compareA)" class="compare-score">
            {{ draftSummary(compareA) }}
          </div>
          <el-table :data="compareLessonsA" size="small" max-height="420" border>
            <el-table-column prop="periodLabel" label="课节" width="96" />
            <el-table-column prop="courseName" label="课程" width="96" />
            <el-table-column prop="teacherName" label="教师" width="96" />
            <el-table-column prop="roomName" label="教室" width="96" />
          </el-table>
        </div>
        <div class="compare-side">
          <el-select v-model="compareB" placeholder="选择草稿 B" style="width: 100%">
            <el-option v-for="item in allDrafts" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
          <div v-if="draftSummary(compareB)" class="compare-score">
            {{ draftSummary(compareB) }}
          </div>
          <el-table :data="compareLessonsB" size="small" max-height="420" border>
            <el-table-column prop="periodLabel" label="课节" width="96" />
            <el-table-column prop="courseName" label="课程" width="96" />
            <el-table-column prop="teacherName" label="教师" width="96" />
            <el-table-column prop="roomName" label="教室" width="96" />
          </el-table>
        </div>
      </div>
      <p class="compare-hint">得分越高越好（硬分必须为 0 才能发布）。</p>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, RefreshLeft, Tickets } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import DraftGrid from '@/components/draft/DraftGrid.vue'
import { courseColor } from '@/components/draft/colors'
import { useDraftAdjust } from '@/composables/useDraftAdjust'
import type {
  Draft,
  DraftConflict,
  DraftCourse,
  DraftLesson,
  DraftPeriod,
  DraftRoom,
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

const { undoCount, canUndo, undoing, move, undo, loadMoveTargets } = useDraftAdjust(draftId)

const loading = ref(false)
type DraftDetail = { draft: Draft; lessons: DraftLesson[]; periods: DraftPeriod[]; rooms: DraftRoom[] }
const detail = ref<DraftDetail | null>(null)
const validation = ref<{ valid: boolean; hardConflicts: number; conflicts: DraftConflict[] } | null>(null)
const courses = ref<DraftCourse[]>([])
const viewMode = ref<'class' | 'teacher' | 'room'>('class')
const targetId = ref<number | null>(null)
const drawerVisible = ref(false)
const dragged = ref<DraftLesson | null>(null)
const dragTargets = ref<Record<number, MoveTargetState> | null>(null)
const conflictVisible = ref(false)
const cellConflicts = ref<DraftConflict[]>([])
const compareVisible = ref(false)
const allDrafts = ref<Draft[]>([])

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

const compareA = ref<number | null>(null)
const compareB = ref<number | null>(null)
const compareLessonsA = ref<DraftLesson[]>([])
const compareLessonsB = ref<DraftLesson[]>([])

function draftSummary(id: number | null) {
  const draft = allDrafts.value.find((item) => item.id === id)
  if (!draft) return ''
  return `${draft.scoreHard} hard / ${draft.scoreSoft} soft · ${draft.lessonCount} 节`
}

async function loadCompareSide(side: 'A' | 'B') {
  const id = side === 'A' ? compareA.value : compareB.value
  const target = side === 'A' ? compareLessonsA : compareLessonsB
  if (!id) {
    target.value = []
    return
  }
  try {
    const { data } = await http.get(`/timetable-drafts/${id}`)
    target.value = [...(data.lessons as DraftLesson[])].sort((a, b) => {
      const dayA = a.dayOfWeek ?? 9
      const dayB = b.dayOfWeek ?? 9
      return dayA !== dayB ? dayA - dayB : (a.periodNo ?? 99) - (b.periodNo ?? 99)
    })
  } catch {
    target.value = []
  }
}

watch([compareA, compareB], () => {
  loadCompareSide('A')
  loadCompareSide('B')
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
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载草稿失败')
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
  const related = (validation.value?.conflicts ?? []).filter((conflict) => conflict.lessonId === lesson.id)
  if (related.length > 0) {
    showConflicts(related)
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
    ElMessage.error(e?.response?.data?.message ?? '发布失败')
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
    ElMessage.error(e?.response?.data?.message ?? '删除失败')
  }
}

async function openCompare() {
  if (isJunior.value) return
  const { data } = await http.get('/timetable-drafts', {
    params: { semesterId: detail.value?.draft.semesterId },
  })
  allDrafts.value = data
  compareA.value = draftId
  const other = (data as Draft[]).find((item) => item.id !== draftId)
  compareB.value = other ? other.id : draftId
  compareVisible.value = true
  await Promise.all([loadCompareSide('A'), loadCompareSide('B')])
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
.stats {
  display: flex;
  gap: 12px;
}
.stat {
  background: #fff;
  border-radius: 10px;
  padding: 12px 18px;
  min-width: 150px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid #eef0f5;
}
.stat-label {
  color: var(--paiban-muted);
  font-size: 12px;
}
.stat-value {
  font-size: 18px;
  font-weight: 600;
}
.danger {
  color: #e45656;
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
.compare-hint {
  margin: 12px 0 0;
  color: var(--paiban-muted);
  font-size: 12px;
}
.compare-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.compare-side {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.compare-score {
  font-size: 12px;
  color: var(--paiban-muted);
}
</style>
