<template>
  <section v-loading="loading" aria-label="任课表">
    <div class="matrix-toolbar">
      <label>
        学期
        <el-select v-model="semesterId" aria-label="学期">
          <el-option v-for="item in semesters" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
      </label>
      <label>
        年级
        <el-select v-model="gradeId" aria-label="年级">
          <el-option v-for="item in grades" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
      </label>
      <div class="matrix-actions">
        <el-button :disabled="!context" @click="openImport">导入</el-button>
        <el-button :disabled="!context" :loading="downloading === 'template'" @click="download('template')">下载模板</el-button>
        <el-button :disabled="!context" :loading="downloading === 'export'" @click="download('export')">导出</el-button>
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-for="batch in pendingDeletions" :key="batch.id" class="undo" role="status">
      <span>已删除 {{ batch.label }}</span>
      <el-button link type="primary" :loading="batch.restoring" @click="undo(batch)">撤销</el-button>
      <el-button link :disabled="batch.restoring" @click="dismiss(batch.id)">关闭</el-button>
    </div>

    <el-table
      v-if="gradeId != null && !error" :data="matrix.rows" row-key="id" border
      empty-text="这个年级还没有行政班"
    >
      <el-table-column prop="name" label="班级" fixed width="116" />
      <el-table-column prop="studentCount" label="人数" fixed width="62" />
      <el-table-column label="班主任" fixed width="100">
        <template #default="{ row }">{{ row.headTeacherName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-for="(course, index) in matrix.columns" :key="course.id" min-width="118">
        <template #header>
          {{ course.name }}
          <small>默认 {{ course.weeklyHours == null ? '—' : `${course.weeklyHours} 节` }}</small>
        </template>
        <template #default="{ row }">
          <button
            class="matrix-cell"
            type="button"
            :aria-label="`${row.name} · ${course.name} · ${row.cells[index].assignment?.teacherName ?? '添加任课教师'}`"
            :disabled="loading || (!course.active && !row.cells[index].assignment)"
            @click="openCell(row, row.cells[index])"
          >
            {{ row.cells[index].assignment?.teacherName ?? '＋' }}
            <small v-if="row.cells[index].assignment && row.cells[index].assignment.weeklyLessons !== course.weeklyHours">
              {{ row.cells[index].assignment.weeklyLessons }} 节/周
            </small>
            <small v-if="row.cells[index].assignment?.active === false">已停用</small>
          </button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else-if="!loading && !error" description="请先建立学期和年级" />

    <TeachingMatrixImportDialog
      v-if="importSelection"
      :context="importSelection.context"
      :target="importSelection.label"
      @close="importSelection = undefined"
      @imported="afterImport"
    />
    <TeachingMatrixCellDialog
      v-if="editing"
      :selection="editing"
      :teachers="teachers"
      @close="editing = undefined"
      @saved="afterSave"
      @delete="confirmDeletion"
    />
    <DeleteImpactDialog
      v-model="deletionOpen"
      entity-type="assignment"
      :entity-id="deletion?.id ?? null"
      :entity-label="deletion?.label"
      @deleted="offerUndo"
      @updated="reload"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { restoreDeletion } from '@/api/lifecycle'
import DeleteImpactDialog from '@/components/lifecycle/DeleteImpactDialog.vue'
import TeachingMatrixImportDialog from '@/teachingMatrix/TeachingMatrixImportDialog.vue'
import { downloadMatrix, type MatrixContext } from '@/teachingMatrix/importApi'
import TeachingMatrixCellDialog from '@/teachingMatrix/TeachingMatrixCellDialog.vue'
import { buildTeachingMatrix, type MatrixAssignment, type MatrixCell, type MatrixClass, type MatrixCourse } from '@/teachingMatrix/matrix'

interface Option { id: number; name: string }
interface Semester extends Option { current: boolean; active: boolean }
interface Grade extends Option { active: boolean }
interface CellSelection extends MatrixCell { semesterId: number; adminClass: MatrixClass }
interface Deletion { id: number; label: string }
interface PendingDeletion { id: string; label: string; restoring: boolean }

const semesters = ref<Semester[]>([])
const grades = ref<Grade[]>([])
const teachers = ref<Option[]>([])
const classes = ref<MatrixClass[]>([])
const courses = ref<MatrixCourse[]>([])
const assignments = ref<MatrixAssignment[]>([])
const semesterId = ref<number>()
const gradeId = ref<number>()
const loading = ref(false)
const error = ref('')
const editing = ref<CellSelection>()
const deletionOpen = ref(false)
const deletion = ref<Deletion>()
const pendingDeletions = ref<PendingDeletion[]>([])
const importSelection = ref<{ context: MatrixContext; label: string }>()
const downloading = ref<'template' | 'export'>()
const context = computed(() => loading.value || error.value || semesterId.value == null || gradeId.value == null
  ? undefined : { semesterId: semesterId.value, gradeId: gradeId.value })
let loadVersion = 0
const matrix = computed(() => buildTeachingMatrix(gradeId.value, classes.value, courses.value, assignments.value))

async function reload() {
  const selectedSemester = semesterId.value
  if (selectedSemester == null) return
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  const params = { semesterId: selectedSemester }
  try {
    const [gradeList, classList, courseList, teacherList, assignmentList] = await Promise.all([
      http.get<Grade[]>('/grades', { params }),
      http.get<MatrixClass[]>('/classes', { params }),
      http.get<MatrixCourse[]>('/courses'),
      http.get<Option[]>('/teachers/options'),
      http.get<MatrixAssignment[]>('/assignments', { params }),
    ])
    if (version !== loadVersion) return
    grades.value = gradeList.data.filter((item) => item.active !== false)
    if (!grades.value.some((item) => item.id === gradeId.value)) gradeId.value = grades.value[0]?.id
    classes.value = classList.data
    courses.value = courseList.data
    teachers.value = teacherList.data
    assignments.value = assignmentList.data
  } catch (cause) {
    if (version === loadVersion) error.value = serverMessage(cause, '加载任课表失败')
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

watch(semesterId, () => {
  gradeId.value = undefined
  grades.value = []
  void reload()
})

onMounted(async () => {
  loading.value = true
  try {
    const { data } = await http.get<Semester[]>('/semesters')
    semesters.value = data.filter((item) => item.active !== false)
    semesterId.value = semesters.value.find((item) => item.current)?.id ?? semesters.value[0]?.id
  } catch (cause) {
    error.value = serverMessage(cause, '加载学期失败')
  } finally {
    if (semesterId.value == null) loading.value = false
  }
})

function openImport() {
  if (!context.value) return
  const semester = semesters.value.find((item) => item.id === semesterId.value)?.name
  const grade = grades.value.find((item) => item.id === gradeId.value)?.name
  importSelection.value = { context: context.value, label: `${semester} · ${grade}` }
}

function afterImport() {
  importSelection.value = undefined
  void reload()
}

async function download(mode: 'template' | 'export') {
  if (!context.value || downloading.value) return
  downloading.value = mode
  try {
    await downloadMatrix(mode, context.value)
  } catch (cause) {
    ElMessage.error(serverMessage(cause, '下载任课表失败'))
  } finally {
    downloading.value = undefined
  }
}

function openCell(adminClass: MatrixClass, cell: MatrixCell) {
  if (semesterId.value != null) editing.value = { ...cell, adminClass, semesterId: semesterId.value }
}

function afterSave() {
  editing.value = undefined
  void reload()
}

function confirmDeletion() {
  const cell = editing.value
  if (!cell?.assignment) return
  deletion.value = { id: cell.assignment.id, label: `${cell.adminClass.name} · ${cell.course.name}` }
  editing.value = undefined
  deletionOpen.value = true
}

function offerUndo(id: string) {
  if (!deletion.value) return
  pendingDeletions.value.push({ id, label: deletion.value.label, restoring: false })
  assignments.value = assignments.value.filter((item) => item.id !== deletion.value?.id)
}

function dismiss(id: string) {
  pendingDeletions.value = pendingDeletions.value.filter((item) => item.id !== id)
}

async function undo(batch: PendingDeletion) {
  batch.restoring = true
  try {
    await restoreDeletion(batch.id)
    dismiss(batch.id)
    await reload()
  } catch (cause) {
    ElMessage.error(serverMessage(cause, '撤销失败'))
  } finally {
    batch.restoring = false
  }
}
</script>

<style scoped>
.matrix-toolbar, .undo { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.matrix-toolbar label { display: flex; align-items: center; gap: 8px; }
.matrix-actions { margin-left: auto; display: flex; flex-wrap: wrap; row-gap: 8px; }
.matrix-toolbar .el-select { width: 180px; }
.undo { padding: 8px 12px; background: var(--el-color-success-light-9); }
small { display: block; color: var(--el-text-color-secondary); }
.matrix-cell { border: 0; border-radius: 4px; width: 100%; padding: 4px; background: transparent; color: inherit; cursor: pointer; }
.matrix-cell:enabled:hover, .matrix-cell:focus-visible { background: var(--el-color-primary-light-9); }
.matrix-cell:disabled { cursor: default; }
.matrix-cell small { color: var(--el-color-warning-dark-2); }
</style>
