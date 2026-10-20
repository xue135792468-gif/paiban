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
          <el-tag size="small" :type="detail?.draft.status === 'PUBLISHED' ? 'success' : 'info'" effect="light">
            {{ detail?.draft.status === 'PUBLISHED' ? '已发布' : '草稿' }}
          </el-tag>
        </h2>
        <p class="subtitle">
          拖拽课节到其他时段即可调整。拖动时绿色表示可放置，红色表示有冲突，点击红格查看原因。
        </p>
      </div>
      <div class="actions">
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
        <span class="stat-value">{{ detail?.draft.scoreHard ?? 0 }} hard / {{ detail?.draft.scoreSoft ?? 0 }} soft</span>
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
          <el-option v-for="option in targetOptions" :key="option.id" :label="option.name" :value="option.id" />
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
        <div class="grid" :style="{ gridTemplateColumns: `96px repeat(${days.length}, minmax(150px, 1fr))` }">
          <div class="corner">节次</div>
          <div v-for="day in days" :key="`h-${day}`" class="day-head">{{ dayName(day) }}</div>

          <template v-for="row in periodRows" :key="`row-${row.periodNo}`">
            <div class="row-head">
              <div class="row-title">第{{ row.periodNo }}节</div>
              <div class="row-time">{{ row.time }}</div>
              <div class="row-section">{{ sectionLabel(row.section) }}</div>
            </div>
            <div
              v-for="day in days"
              :key="`c-${day}-${row.periodNo}`"
              class="cell"
              :class="cellClass(day, row.periodNo)"
              @dragover.prevent
              @dragenter.prevent
              @drop.prevent="onDrop(day, row.periodNo)"
              @click="showCellConflict(day, row.periodNo)"
            >
              <div
                v-if="lessonAt(day, row.periodNo)"
                class="lesson"
                :class="{ dragging: dragged?.id === lessonAt(day, row.periodNo)?.id }"
                :style="{ background: courseColor(lessonAt(day, row.periodNo)!.courseId) }"
                :draggable="canEdit && detail?.draft.status !== 'PUBLISHED'"
                @dragstart="onDragStart(lessonAt(day, row.periodNo)!)"
                @dragend="onDragEnd"
              >
                <div class="lesson-course">{{ lessonAt(day, row.periodNo)!.courseName }}</div>
                <div class="lesson-meta">
                  {{ lessonAt(day, row.periodNo)!.teacherName }}
                  <span v-if="lessonAt(day, row.periodNo)!.roomName"> · {{ lessonAt(day, row.periodNo)!.roomName }}</span>
                </div>
              </div>
              <div v-else class="empty-cell">—</div>
            </div>
          </template>
        </div>
        <el-empty v-if="days.length === 0" description="该学期还没有配置作息表" />
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
          class="lesson drawer-lesson"
          :style="{ background: courseColor(lesson.courseId) }"
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Tickets } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'

interface Draft {
  id: number
  semesterId: number
  name: string
  status: string
  scoreHard: number
  scoreSoft: number
  createdBy?: string
  createdAt: string
  lessonCount: number
}

interface Lesson {
  id: number
  assignmentId: number
  courseId: number
  courseName?: string
  teacherId: number
  teacherName?: string
  classId: number
  className?: string
  studentCount: number
  periodId?: number | null
  dayOfWeek?: number | null
  periodNo?: number | null
  section?: string
  periodLabel?: string
  roomId?: number | null
  roomName?: string
}

interface Period {
  id: number
  dayOfWeek: number
  periodNo: number
  label: string
  section: string
  startTime: string
  endTime: string
}

interface Room {
  id: number
  name: string
  capacity: number
  type: string
}

interface Conflict {
  type: string
  message: string
  lessonId: number
}

interface Course {
  id: number
  color?: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const settings = useSettingsStore()
const isJunior = computed(() => settings.schoolType === 'junior')
const draftId = Number(route.params.id)
const canEdit = computed(() => auth.hasPermission('timetable:edit'))
const canPublish = computed(() => auth.hasPermission('timetable:publish'))

const loading = ref(false)
const detail = ref<{ draft: Draft; lessons: Lesson[]; periods: Period[]; rooms: Room[] } | null>(null)
const validation = ref<{ valid: boolean; hardConflicts: number; conflicts: Conflict[] } | null>(null)
const courses = ref<Course[]>([])
const viewMode = ref<'class' | 'teacher' | 'room'>('class')
const targetId = ref<number | null>(null)
const drawerVisible = ref(false)
const dragged = ref<Lesson | null>(null)
const dragValidity = ref<Record<number, { valid: boolean; conflicts: Conflict[] }>>({})
const conflictVisible = ref(false)
const cellConflicts = ref<Conflict[]>([])
const compareVisible = ref(false)
const allDrafts = ref<Draft[]>([])

const days = computed(() => {
  const set = new Set<number>()
  detail.value?.periods.forEach((period) => set.add(period.dayOfWeek))
  return [...set].sort((a, b) => a - b)
})

const periodRows = computed(() => {
  const map = new Map<number, { periodNo: number; time: string; section: string }>()
  detail.value?.periods.forEach((period) => {
    if (!map.has(period.periodNo)) {
      map.set(period.periodNo, {
        periodNo: period.periodNo,
        time: `${period.startTime.slice(0, 5)}-${period.endTime.slice(0, 5)}`,
        section: period.section,
      })
    }
  })
  return [...map.values()].sort((a, b) => a.periodNo - b.periodNo)
})

const periodIdByCell = computed(() => {
  const map: Record<string, number> = {}
  detail.value?.periods.forEach((period) => {
    map[`${period.dayOfWeek}-${period.periodNo}`] = period.id
  })
  return map
})

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
const compareLessonsA = ref<Lesson[]>([])
const compareLessonsB = ref<Lesson[]>([])

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
    target.value = [...(data.lessons as Lesson[])].sort((a, b) => {
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

const palette = [
  '#AEC6CF',
  '#FFD1DC',
  '#B5EAD7',
  '#FFDAC1',
  '#C7CEEA',
  '#E2F0CB',
  '#F1C0E8',
  '#FDFD96',
  '#B0E0E6',
  '#FFB7B2',
]

function courseColor(courseId: number) {
  const course = courses.value.find((item) => item.id === courseId)
  if (course?.color) return course.color
  return palette[courseId % palette.length]
}

function dayName(day: number) {
  return ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'][day] ?? `周${day}`
}

const sectionLabels: Record<string, string> = { MORNING: '上午', AFTERNOON: '下午', EVENING: '晚自习' }

function sectionLabel(section: string) {
  return sectionLabels[section] ?? ''
}

function lessonAt(day: number, periodNo: number) {
  return filteredLessons.value.find(
    (lesson) => lesson.dayOfWeek === day && lesson.periodNo === periodNo,
  )
}

function cellClass(day: number, periodNo: number) {
  if (!dragged.value) return ''
  const periodId = periodIdByCell.value[`${day}-${periodNo}`]
  if (!periodId) return ''
  const state = dragValidity.value[periodId]
  if (!state) return 'checking'
  return state.valid ? 'valid-target' : 'invalid-target'
}

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

async function onDragStart(lesson: Lesson) {
  dragged.value = lesson
  dragValidity.value = {}
  const periods = detail.value?.periods ?? []
  await Promise.all(
    periods.map(async (period) => {
      try {
        const { data } = await http.post(`/timetable-drafts/${draftId}/validate-move`, {
          lessonId: lesson.id,
          periodId: period.id,
          roomId: lesson.roomId ?? null,
        })
        dragValidity.value[period.id] = { valid: data.valid, conflicts: data.conflicts }
      } catch {
        dragValidity.value[period.id] = { valid: false, conflicts: [] }
      }
    }),
  )
}

function onDragEnd() {
  dragged.value = null
  dragValidity.value = {}
}

async function onDrop(day: number, periodNo: number) {
  const lesson = dragged.value
  const periodId = periodIdByCell.value[`${day}-${periodNo}`]
  if (!lesson || !periodId) return
  const state = dragValidity.value[periodId]
  if (!state) return
  if (!state.valid) {
    cellConflicts.value = state.conflicts
    conflictVisible.value = true
    return
  }
  try {
    const { data } = await http.patch(`/timetable-drafts/${draftId}/lessons/${lesson.id}`, {
      periodId,
      roomId: lesson.roomId ?? null,
    })
    if (!data.saved) {
      cellConflicts.value = data.conflicts
      conflictVisible.value = true
    } else {
      ElMessage.success('已移动课节')
    }
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '移动失败')
  } finally {
    onDragEnd()
  }
}

function showCellConflict(day: number, periodNo: number) {
  const lesson = lessonAt(day, periodNo)
  if (!lesson) return
  const related = (validation.value?.conflicts ?? []).filter((conflict) => conflict.lessonId === lesson.id)
  if (related.length > 0) {
    cellConflicts.value = related
    conflictVisible.value = true
  }
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

async function removeDraft() {  try {
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

onMounted(load)
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
.grid {
  display: grid;
  border-top: 1px solid #eef0f5;
  border-left: 1px solid #eef0f5;
  min-width: 900px;
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
.lesson-course {
  font-weight: 600;
  font-size: 13px;
}
.lesson-meta {
  font-size: 11px;
  margin-top: 3px;
  color: #4b5563;
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
