<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>年级设置</h2>
        <p class="subtitle">维护学期年级划分与排序。删除年级将级联删除其关联的所有行政班与排课数据。</p>
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
          <span>新建年级</span>
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <EmptyStateGuide
        v-if="semesters.length === 0 && !loading"
        title="还没有学期"
        description="请先在「学期与作息」中创建学期，年级数据按学期维护。"
        button-text="去创建学期"
        button-to="/admin/semesters"
        help-slug="foundation"
        help-anchor="grades"
      />
      <EmptyStateGuide
        v-else-if="!loading && grades.length === 0"
        title="还没有年级数据"
        description="年级是行政班与授课任务的基础。点击下方按钮新建年级，或使用右上角「导入」从 Excel 批量导入。"
        button-text="新建年级"
        help-slug="foundation"
        help-anchor="grades"
        @action="openCreate"
      />
      <el-table
        v-else
        v-loading="loading"
        :data="grades"
        stripe
      >
        <el-table-column prop="name" label="年级名称" min-width="180" />
        <el-table-column prop="sortOrder" label="排序" min-width="120" />
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
      :title="isEdit ? '编辑年级' : '新建年级'"
      width="460px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="88px"
      >
        <el-form-item label="年级名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：高一、初一" maxlength="100" />
        </el-form-item>
        <el-form-item label="排序序号" prop="sortOrder">
          <el-input-number
            v-model="form.sortOrder"
            :min="1"
            :max="999"
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
      entity="grades"
      entity-label="年级"
      :semester-id="semesterId"
      @imported="loadGrades"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Download, Plus, Upload } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
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

const auth = useAuthStore()

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const grades = ref<GradeItem[]>([])
const loading = ref(false)
const exporting = ref(false)
const importVisible = ref(false)

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  name: '',
  sortOrder: 1,
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入年级名称', trigger: 'blur' },
    { max: 100, message: '年级名称不能超过100个字符', trigger: 'blur' },
  ],
  sortOrder: [{ required: true, message: '请输入排序序号', trigger: 'blur' }],
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

async function loadGrades() {
  if (!semesterId.value) {
    grades.value = []
    return
  }
  loading.value = true
  try {
    const { data } = await http.get<GradeItem[]>('/grades', {
      params: { semesterId: semesterId.value },
    })
    grades.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载年级失败')
  } finally {
    loading.value = false
  }
}

async function onSemesterChange() {
  await loadGrades()
}

function openCreate() {
  isEdit.value = false
  editId.value = null
  form.name = ''
  form.sortOrder = (grades.value.length + 1) * 10
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(row: GradeItem) {
  isEdit.value = true
  editId.value = row.id
  form.name = row.name
  form.sortOrder = row.sortOrder
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function submitForm() {
  if (!formRef.value || !semesterId.value) return
  await formRef.value.validate()

  saving.value = true
  try {
    if (isEdit.value && editId.value) {
      await http.put(`/grades/${editId.value}`, {
        name: form.name.trim(),
        sortOrder: form.sortOrder,
      })
      ElMessage.success('更新年级成功')
    } else {
      await http.post(
        '/grades',
        {
          name: form.name.trim(),
          sortOrder: form.sortOrder,
        },
        {
          params: { semesterId: semesterId.value },
        },
      )
      ElMessage.success('新建年级成功')
    }
    dialogVisible.value = false
    await loadGrades()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: GradeItem) {
  try {
    await ElMessageBox.confirm(
      `确定要删除年级「${row.name}」吗？删除年级将同时级联删除该年级下的所有行政班及关联数据，此操作不可撤销！`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    await http.delete(`/grades/${row.id}`)
    ElMessage.success('删除成功')
    await loadGrades()
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
    const res = await http.get('/export/grades', {
      params: { semesterId: semesterId.value },
      responseType: 'blob',
    })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '年级数据.xlsx'
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
  await loadSemesters()
  if (semesterId.value) {
    await loadGrades()
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
</style>
