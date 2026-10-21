<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>课程管理</h2>
        <p class="subtitle">维护学校开设的课程科目，设定是否允许连堂、室外场地及专用教室类型要求。</p>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="auth.hasPermission('foundation:manage')" @click="importVisible = true">
          <el-icon><Upload /></el-icon>
          <span>导入</span>
        </el-button>
        <el-button :loading="exporting" @click="handleExport">
          <el-icon><Download /></el-icon>
          <span>导出</span>
        </el-button>
        <el-button v-if="auth.hasPermission('foundation:manage')" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span>新建课程</span>
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="courses" stripe>
        <el-table-column prop="name" label="课程名称" min-width="140" />
        <el-table-column prop="subject" label="所属学科" min-width="120">
          <template #default="{ row }">
            <span>{{ row.subject || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="requiredRoomType" label="所需教室类型" min-width="130">
          <template #default="{ row }">
            <el-tag v-if="row.requiredRoomType" size="small">
              {{ ROOM_TYPE_MAP[row.requiredRoomType] || row.requiredRoomType }}
            </el-tag>
            <span v-else class="muted">无特殊要求</span>
          </template>
        </el-table-column>
        <el-table-column prop="allowConsecutive" label="允许连堂" width="110">
          <template #default="{ row }">
            <el-tag :type="row.allowConsecutive ? 'success' : 'info'" size="small">
              {{ row.allowConsecutive ? '允许' : '禁止' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requireOutdoor" label="需要室外" width="110">
          <template #default="{ row }">
            <el-tag :type="row.requireOutdoor ? 'warning' : 'info'" size="small">
              {{ row.requireOutdoor ? '室外' : '室内' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="color" label="课表颜色" min-width="130">
          <template #default="{ row }">
            <div v-if="row.color" class="color-cell">
              <span class="color-swatch" :style="{ backgroundColor: row.color }" />
              <code>{{ row.color }}</code>
            </div>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.hasPermission('foundation:manage')"
          label="操作"
          width="160"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑课程' : '新建课程'"
      width="520px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="课程名称" prop="name">
          <el-input v-model="form.name" placeholder="如：数学、物理、体育" />
        </el-form-item>
        <el-form-item label="所属学科" prop="subject">
          <el-input v-model="form.subject" placeholder="如：理科、文科、综合（选填）" />
        </el-form-item>
        <el-form-item label="教室要求" prop="requiredRoomType">
          <el-select
            v-model="form.requiredRoomType"
            placeholder="无特殊要求（普通教室）"
            clearable
            style="width: 100%"
          >
            <el-option
              v-for="item in ROOM_TYPE_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item prop="allowConsecutive">
          <template #label>
            连堂设置
            <InfoTooltip field="course.consecutive" />
          </template>
          <el-switch
            v-model="form.allowConsecutive"
            active-text="允许连堂"
            inactive-text="禁止连堂"
          />
        </el-form-item>
        <el-form-item label="场地设置" prop="requireOutdoor">
          <el-switch
            v-model="form.requireOutdoor"
            active-text="需要室外场地"
            inactive-text="室内课程"
          />
        </el-form-item>
        <el-form-item label="课表颜色" prop="color">
          <div class="color-input-wrapper">
            <span
              class="color-preview-box"
              :style="{ backgroundColor: form.color || '#e5e7eb' }"
            />
            <el-input
              v-model="form.color"
              placeholder="如：#AEC6CF"
              style="width: 140px"
            />
            <el-color-picker v-model="form.color" :predefine="predefineColors" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-model="importVisible"
      entity="courses"
      entity-label="课程"
      @imported="load"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Download, Upload } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import ImportDialog from '@/components/ImportDialog.vue'
import InfoTooltip from '@/components/InfoTooltip.vue'

interface Course {
  id: number
  name: string
  subject?: string | null
  allowConsecutive: boolean
  requireOutdoor: boolean
  requiredRoomType?: string | null
  color?: string | null
}

const ROOM_TYPE_MAP: Record<string, string> = {
  NORMAL: '普通教室',
  LAB: '实验室',
  PLAYGROUND: '操场/室外',
  MULTIMEDIA: '多媒体',
  OTHER: '其他',
}

const ROOM_TYPE_OPTIONS = [
  { value: 'NORMAL', label: '普通教室' },
  { value: 'LAB', label: '实验室' },
  { value: 'PLAYGROUND', label: '操场/室外' },
  { value: 'MULTIMEDIA', label: '多媒体' },
  { value: 'OTHER', label: '其他' },
]

const predefineColors = [
  '#4F6EF7',
  '#10B981',
  '#F59E0B',
  '#EF4444',
  '#8B5CF6',
  '#EC4899',
  '#06B6D4',
  '#AEC6CF',
  '#F472B6',
  '#34D399',
]

const auth = useAuthStore()
const courses = ref<Course[]>([])
const loading = ref(false)
const saving = ref(false)
const exporting = ref(false)
const dialogVisible = ref(false)
const importVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const defaultForm = {
  name: '',
  subject: '',
  allowConsecutive: false,
  requireOutdoor: false,
  requiredRoomType: '',
  color: '#4F6EF7',
}

const form = reactive({ ...defaultForm })

const rules: FormRules = {
  name: [{ required: true, message: '请输入课程名称', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/courses')
    courses.value = data
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载课程列表失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, defaultForm)
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(row: Course) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    subject: row.subject ?? '',
    allowConsecutive: Boolean(row.allowConsecutive),
    requireOutdoor: Boolean(row.requireOutdoor),
    requiredRoomType: row.requiredRoomType ?? '',
    color: row.color ?? '#4F6EF7',
  })
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function submit() {
  if (!formRef.value) return
  await formRef.value.validate()
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      subject: form.subject.trim() || null,
      allowConsecutive: form.allowConsecutive,
      requireOutdoor: form.requireOutdoor,
      requiredRoomType: form.requiredRoomType || null,
      color: form.color.trim() || null,
    }
    if (editingId.value) {
      await http.put(`/courses/${editingId.value}`, payload)
      ElMessage.success('更新课程成功')
    } else {
      await http.post('/courses', payload)
      ElMessage.success('创建课程成功')
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: Course) {
  try {
    await ElMessageBox.confirm(`确定要删除课程“${row.name}”吗？`, '删除确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await http.delete(`/courses/${row.id}`)
    ElMessage.success('删除成功')
    await load()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || '删除失败')
    }
  }
}

async function handleExport() {
  exporting.value = true
  try {
    const res = await http.get('/export/courses', { responseType: 'blob' })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '课程数据.xlsx'
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e: any) {
    let message = '导出失败'
    if (e?.response?.data instanceof Blob) {
      try {
        const text = await e.response.data.text()
        const json = JSON.parse(text)
        if (json.message) message = json.message
      } catch {
        // ignore
      }
    } else if (e?.response?.data?.message) {
      message = e.response.data.message
    }
    ElMessage.error(message)
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
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
.muted {
  color: var(--paiban-muted);
}
.color-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.color-swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: 3px;
  border: 1px solid rgba(0, 0, 0, 0.15);
  flex-shrink: 0;
}
.color-input-wrapper {
  display: flex;
  align-items: center;
  gap: 10px;
}
.color-preview-box {
  display: inline-block;
  width: 28px;
  height: 28px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  flex-shrink: 0;
}
</style>
