<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>授课任务管理</h2>
        <p class="subtitle">配置班级课程的每周课时与授课教师，作为智能排课的核心输入依据。</p>
      </div>
      <div class="actions">
        <el-select
          v-model="semesterId"
          placeholder="请选择学期"
          :disabled="semesters.length === 0"
          style="width: 200px"
          @change="onSemesterChange"
        >
          <el-option
            v-for="s in semesters"
            :key="s.id"
            :label="s.current ? `${s.name} (当前)` : s.name"
            :value="s.id"
          />
        </el-select>
        <el-button
          v-if="canManage"
          :disabled="!semesterId"
          @click="importVisible = true"
        >
          <el-icon><Upload /></el-icon>
          <span>导入</span>
        </el-button>
        <el-button
          v-if="canManage"
          :disabled="!semesterId"
          :loading="exporting"
          @click="handleExport"
        >
          <el-icon><Download /></el-icon>
          <span>导出</span>
        </el-button>
        <el-button
          v-if="canManage"
          type="primary"
          :disabled="!semesterId"
          @click="openCreate"
        >
          <el-icon><Plus /></el-icon>
          <span>新建授课任务</span>
        </el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="授课任务 = 班级 + 课程 + 每周节数 + 任课教师"
      class="hint-alert"
    />

    <el-card shadow="never">
      <div v-if="semesterId" class="filter-bar">
        <template v-if="canManage">
          <el-select
            v-model="filters.classId"
            placeholder="全部班级"
            clearable
            filterable
            style="width: 180px"
            @change="loadAssignments"
          >
            <el-option
              v-for="c in classes"
              :key="c.id"
              :label="c.gradeName ? `${c.gradeName} - ${c.name}` : c.name"
              :value="c.id"
            />
          </el-select>

          <el-select
            v-model="filters.courseId"
            placeholder="全部课程"
            clearable
            filterable
            style="width: 180px"
            @change="loadAssignments"
          >
            <el-option
              v-for="course in courses"
              :key="course.id"
              :label="course.name"
              :value="course.id"
            />
          </el-select>

          <el-select
            v-model="filters.teacherId"
            placeholder="全部教师"
            clearable
            filterable
            style="width: 180px"
            @change="loadAssignments"
          >
            <el-option
              v-for="t in teachers"
              :key="t.id"
              :label="t.subject ? `${t.name} (${t.subject})` : t.name"
              :value="t.id"
            />
          </el-select>

          <el-checkbox
            v-model="onlyMine"
            @change="loadAssignments"
          >
            只看我的
          </el-checkbox>
        </template>
        <template v-else>
          <el-tag type="info">只看我的授课任务</el-tag>
        </template>
      </div>

      <el-empty
        v-if="semesters.length === 0 && !loading"
        description="请先在「学期与作息」中创建学期"
      />
      <el-table
        v-else
        v-loading="loading"
        :data="assignments"
        stripe
      >
        <el-table-column prop="className" label="班级" min-width="140" />
        <el-table-column prop="gradeName" label="年级" min-width="120" />
        <el-table-column prop="courseName" label="课程" min-width="140" />
        <el-table-column prop="teacherName" label="教师" min-width="140">
          <template #default="{ row }">
            <span v-if="row.teacherName">{{ row.teacherName }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="weeklyLessons" label="每周节数" min-width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ row.weeklyLessons }} 节/周</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="canManage"
          label="操作"
          width="160"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑授课任务' : '新建授课任务'"
      width="480px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
      >
        <el-form-item label="班级" prop="classId">
          <el-select
            v-model="form.classId"
            placeholder="请选择班级"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="c in classes"
              :key="c.id"
              :label="c.gradeName ? `${c.gradeName} - ${c.name}` : c.name"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="课程" prop="courseId">
          <el-select
            v-model="form.courseId"
            placeholder="请选择课程"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="course in courses"
              :key="course.id"
              :label="course.name"
              :value="course.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="教师" prop="teacherId">
          <el-select
            v-model="form.teacherId"
            placeholder="请选择任课教师"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="t in teachers"
              :key="t.id"
              :label="t.subject ? `${t.name} (${t.subject})` : t.name"
              :value="t.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="每周节数" prop="weeklyLessons">
          <el-input-number
            v-model="form.weeklyLessons"
            :min="1"
            :max="20"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-model="importVisible"
      entity="assignments"
      entity-label="授课任务"
      :semester-id="semesterId"
      preview-url="/assignments/import/preview"
      commit-url="/assignments/import/commit"
      template-url="/assignments/template"
      @imported="load"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Download, Plus, Upload } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import ImportDialog from '@/components/ImportDialog.vue'
import { useAuthStore } from '@/stores/auth'

interface Semester {
  id: number
  name: string
  startDate?: string
  endDate?: string
  current: boolean
}

interface ClassItem {
  id: number
  name: string
  gradeId?: number
  gradeName?: string
}

interface CourseItem {
  id: number
  name: string
  code?: string
}

interface TeacherItem {
  id: number
  name: string
  subject?: string
}

interface AssignmentItem {
  id: number
  classId: number
  className: string
  gradeId?: number
  gradeName?: string
  courseId: number
  courseName: string
  teacherId: number
  teacherName: string
  weeklyLessons: number
  teachingClassId?: number
}

interface FormState {
  classId?: number
  courseId?: number
  teacherId?: number
  weeklyLessons: number
}

interface FilterState {
  classId?: number
  courseId?: number
  teacherId?: number
}

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission('assignment:manage'))

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const classes = ref<ClassItem[]>([])
const courses = ref<CourseItem[]>([])
const teachers = ref<TeacherItem[]>([])
const assignments = ref<AssignmentItem[]>([])

const loading = ref(false)
const exporting = ref(false)
const importVisible = ref(false)
const onlyMine = ref(false)

const filters = reactive<FilterState>({
  classId: undefined,
  courseId: undefined,
  teacherId: undefined,
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<FormState>({
  classId: undefined,
  courseId: undefined,
  teacherId: undefined,
  weeklyLessons: 2,
})

const rules: FormRules = {
  classId: [{ required: true, message: '请选择班级', trigger: 'change' }],
  courseId: [{ required: true, message: '请选择课程', trigger: 'change' }],
  teacherId: [{ required: true, message: '请选择任课教师', trigger: 'change' }],
  weeklyLessons: [{ required: true, message: '请输入每周节数', trigger: 'blur' }],
}

async function loadSemesters() {
  try {
    const [listRes, currentRes] = await Promise.all([
      http.get<Semester[]>('/semesters'),
      http.get<Semester>('/semesters/current', { validateStatus: (status) => status < 500 }),
    ])
    semesters.value = listRes.data ?? []

    let targetId: number | null = null
    if (currentRes.status === 200 && currentRes.data?.id) {
      targetId = currentRes.data.id
    } else {
      const currentInList = semesters.value.find((s) => s.current)
      if (currentInList) {
        targetId = currentInList.id
      } else if (semesters.value.length > 0) {
        targetId = semesters.value[0].id
      }
    }
    semesterId.value = targetId
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载学期失败')
  }
}

async function loadClasses() {
  if (!semesterId.value) {
    classes.value = []
    return
  }
  try {
    const { data } = await http.get<ClassItem[]>('/classes', {
      params: { semesterId: semesterId.value },
    })
    classes.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载班级列表失败')
  }
}

async function loadCourses() {
  try {
    const { data } = await http.get<CourseItem[]>('/courses')
    courses.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载课程列表失败')
  }
}

async function loadTeachers() {
  try {
    const { data } = await http.get<TeacherItem[]>('/teachers')
    teachers.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载教师列表失败')
  }
}

async function loadAssignments() {
  if (!semesterId.value) {
    assignments.value = []
    return
  }
  loading.value = true
  try {
    if (!canManage.value || onlyMine.value) {
      const { data } = await http.get<AssignmentItem[]>('/assignments/mine', {
        params: { semesterId: semesterId.value },
      })
      let list = data ?? []
      if (filters.courseId) {
        list = list.filter((a) => a.courseId === filters.courseId)
      }
      assignments.value = list
    } else {
      const { data } = await http.get<AssignmentItem[]>('/assignments', {
        params: {
          semesterId: semesterId.value,
          classId: filters.classId || undefined,
          courseId: filters.courseId || undefined,
          teacherId: filters.teacherId || undefined,
        },
      })
      assignments.value = data ?? []
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载授课任务失败')
  } finally {
    loading.value = false
  }
}

async function load() {
  await loadAssignments()
}

async function onSemesterChange() {
  filters.classId = undefined
  await loadClasses()
  await loadAssignments()
}

function openCreate() {
  isEdit.value = false
  editId.value = null
  form.classId = filters.classId ?? (classes.value.length > 0 ? classes.value[0].id : undefined)
  form.courseId = filters.courseId ?? undefined
  form.teacherId = filters.teacherId ?? undefined
  form.weeklyLessons = 2
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(row: AssignmentItem) {
  isEdit.value = true
  editId.value = row.id
  form.classId = row.classId
  form.courseId = row.courseId
  form.teacherId = row.teacherId
  form.weeklyLessons = row.weeklyLessons
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function submitForm() {
  if (!formRef.value) return
  await formRef.value.validate()

  saving.value = true
  try {
    const payload = {
      classId: form.classId,
      courseId: form.courseId,
      teacherId: form.teacherId,
      weeklyLessons: form.weeklyLessons,
    }
    if (isEdit.value && editId.value) {
      await http.put(`/assignments/${editId.value}`, payload)
      ElMessage.success('更新授课任务成功')
    } else {
      await http.post('/assignments', payload)
      ElMessage.success('新建授课任务成功')
    }
    dialogVisible.value = false
    await loadAssignments()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: AssignmentItem) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${row.className}」的「${row.courseName}」授课任务吗？`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    await http.delete(`/assignments/${row.id}`)
    ElMessage.success('删除成功')
    await loadAssignments()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.response?.data?.message ?? '删除失败')
    }
  }
}

async function handleExport() {
  if (!semesterId.value) return
  exporting.value = true
  try {
    const res = await http.get('/assignments/export', {
      params: { semesterId: semesterId.value },
      responseType: 'blob',
    })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '授课任务.xlsx'
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadSemesters(), loadCourses(), loadTeachers()])
  if (semesterId.value) {
    await Promise.all([loadClasses(), loadAssignments()])
  }
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
}
h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.hint-alert {
  margin-bottom: 16px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.muted {
  color: #9ca3af;
}
</style>
