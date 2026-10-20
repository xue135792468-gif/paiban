<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>行政班管理</h2>
        <p class="subtitle">维护学期行政行政班及班主任配置（一期行政班与教学班一对一自动创建）。</p>
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
          v-if="auth.hasPermission('foundation:manage')"
          :disabled="!semesterId"
          @click="importVisible = true"
        >
          <el-icon><Upload /></el-icon>
          <span>导入</span>
        </el-button>
        <el-button
          v-if="auth.hasPermission('foundation:manage')"
          :disabled="!semesterId"
          :loading="exporting"
          @click="handleExport"
        >
          <el-icon><Download /></el-icon>
          <span>导出</span>
        </el-button>
        <el-button
          v-if="auth.hasPermission('foundation:manage')"
          type="primary"
          :disabled="!semesterId"
          @click="openCreate"
        >
          <el-icon><Plus /></el-icon>
          <span>新建行政班</span>
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-empty
        v-if="semesters.length === 0 && !loading"
        description="请先在「学期与作息」中创建学期"
      />
      <el-table
        v-else
        v-loading="loading"
        :data="classes"
        stripe
      >
        <el-table-column prop="gradeName" label="年级" min-width="120" />
        <el-table-column prop="name" label="行政班名称" min-width="140" />
        <el-table-column label="班主任" min-width="140">
          <template #default="{ row }">
            <span v-if="row.headTeacherName">{{ row.headTeacherName }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="studentCount" label="学生人数" min-width="100" />
        <el-table-column
          v-if="auth.hasPermission('foundation:manage')"
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
      :title="isEdit ? '编辑行政班' : '新建行政班'"
      width="480px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
      >
        <el-form-item label="所属年级" prop="gradeId">
          <el-select
            v-model="form.gradeId"
            :placeholder="grades.length === 0 ? '暂无年级，请先创建' : '请选择年级'"
            style="width: 100%"
          >
            <el-option
              v-for="g in grades"
              :key="g.id"
              :label="g.name"
              :value="g.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="行政班名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：1班、高一(1)班" maxlength="100" />
        </el-form-item>
        <el-form-item label="班主任" prop="headTeacherId">
          <el-select
            v-model="form.headTeacherId"
            clearable
            filterable
            placeholder="请选择班主任（可选）"
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
        <el-form-item label="学生人数" prop="studentCount">
          <el-input-number
            v-model="form.studentCount"
            :min="1"
            :max="200"
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
      entity="classes"
      entity-label="行政班"
      :semester-id="semesterId"
      @imported="loadClasses"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
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

interface GradeItem {
  id: number
  name: string
  sortOrder: number
}

interface TeacherItem {
  id: number
  name: string
  subject?: string
}

interface ClassItem {
  id: number
  name: string
  gradeId: number
  gradeName?: string
  headTeacherId?: number | null
  headTeacherName?: string | null
  studentCount: number
  teachingClassId?: number | null
}

interface FormState {
  gradeId?: number
  name: string
  headTeacherId?: number | null
  studentCount: number
}

const auth = useAuthStore()

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const grades = ref<GradeItem[]>([])
const teachers = ref<TeacherItem[]>([])
const classes = ref<ClassItem[]>([])
const loading = ref(false)
const exporting = ref(false)
const importVisible = ref(false)

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<FormState>({
  gradeId: undefined,
  name: '',
  headTeacherId: null,
  studentCount: 45,
})

const rules: FormRules = {
  gradeId: [{ required: true, message: '请选择所属年级', trigger: 'change' }],
  name: [
    { required: true, message: '请输入行政班名称', trigger: 'blur' },
    { max: 100, message: '行政班名称不能超过100个字符', trigger: 'blur' },
  ],
  studentCount: [{ required: true, message: '请输入学生人数', trigger: 'blur' }],
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

async function loadTeachers() {
  try {
    const { data } = await http.get<TeacherItem[]>('/teachers/options')
    teachers.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载教师列表失败')
  }
}

async function loadGrades() {
  if (!semesterId.value) {
    grades.value = []
    return
  }
  try {
    const { data } = await http.get<GradeItem[]>('/grades', {
      params: { semesterId: semesterId.value },
    })
    grades.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载年级列表失败')
  }
}

async function loadClasses() {
  if (!semesterId.value) {
    classes.value = []
    return
  }
  loading.value = true
  try {
    const { data } = await http.get<ClassItem[]>('/classes', {
      params: { semesterId: semesterId.value },
    })
    classes.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载行政班列表失败')
  } finally {
    loading.value = false
  }
}

async function onSemesterChange() {
  await Promise.all([loadGrades(), loadClasses()])
}

function openCreate() {
  isEdit.value = false
  editId.value = null
  form.gradeId = grades.value.length > 0 ? grades.value[0].id : undefined
  form.name = ''
  form.headTeacherId = null
  form.studentCount = 45
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(row: ClassItem) {
  isEdit.value = true
  editId.value = row.id
  form.gradeId = row.gradeId
  form.name = row.name
  form.headTeacherId = row.headTeacherId ?? null
  form.studentCount = row.studentCount ?? 45
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function submitForm() {
  if (!formRef.value || !semesterId.value) return
  await formRef.value.validate()

  saving.value = true
  try {
    const payload = {
      gradeId: form.gradeId,
      name: form.name.trim(),
      headTeacherId: form.headTeacherId || null,
      studentCount: form.studentCount,
    }
    if (isEdit.value && editId.value) {
      await http.put(`/classes/${editId.value}`, payload)
      ElMessage.success('更新行政班成功')
    } else {
      await http.post('/classes', payload, {
        params: { semesterId: semesterId.value },
      })
      ElMessage.success('新建行政班成功')
    }
    dialogVisible.value = false
    await loadClasses()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: ClassItem) {
  try {
    await ElMessageBox.confirm(
      `确定要删除行政班「${row.name}」吗？删除后关联教学班也将被移除，此操作不可撤销！`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    await http.delete(`/classes/${row.id}`)
    ElMessage.success('删除成功')
    await loadClasses()
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
    const res = await http.get('/export/classes', {
      params: { semesterId: semesterId.value },
      responseType: 'blob',
    })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '行政班数据.xlsx'
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
  await Promise.all([loadSemesters(), loadTeachers()])
  if (semesterId.value) {
    await Promise.all([loadGrades(), loadClasses()])
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
.muted {
  color: #9ca3af;
}
</style>
