<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>教室管理</h2>
        <p class="subtitle">
          {{
            isJunior
              ? '维护教学楼与教室资源，教室类型简化为普通教室与专用教室。'
              : '维护教学楼与教室资源，配置普通教室、实验室、多媒体教室等场地与容量。'
          }}
        </p>
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
          <span>新建教室</span>
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <EmptyStateGuide
        v-if="!loading && rooms.length === 0"
        title="还没有教室数据"
        description="教室是排课时的场地资源。点击下方按钮新建教室，或使用右上角「导入」从 Excel 批量导入。"
        :button-text="auth.hasPermission('foundation:manage') ? '新建教室' : undefined"
        help-slug="foundation"
        help-anchor="rooms"
        @action="openCreate"
      />
      <el-table v-else v-loading="loading" :data="rooms" stripe>
        <el-table-column prop="name" label="教室名称" min-width="140" />
        <el-table-column prop="building" label="所在楼栋" min-width="120">
          <template #default="{ row }">
            <span>{{ row.building || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="教室类型" min-width="120">
          <template #default="{ row }">
            <el-tag :type="getTypeTagType(row.type)">
              {{ ROOM_TYPE_MAP[row.type] || row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="capacity" label="容纳人数" min-width="110">
          <template #default="{ row }">
            <span>{{ row.capacity ? `${row.capacity} 人` : '—' }}</span>
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
      :title="editingId ? '编辑教室' : '新建教室'"
      width="480px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="教室名称" prop="name">
          <el-input v-model="form.name" placeholder="如：崇文楼 101" />
        </el-form-item>
        <el-form-item label="所在楼栋" prop="building">
          <el-input v-model="form.building" placeholder="如：崇文楼（选填）" />
        </el-form-item>
        <el-form-item label="教室类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择教室类型" style="width: 100%">
            <el-option
              v-for="item in roomTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="容纳人数" prop="capacity">
          <el-input-number
            v-model="form.capacity"
            :min="1"
            :max="1000"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-model="importVisible"
      entity="rooms"
      entity-label="教室"
      @imported="load"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Download, Upload } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ImportDialog from '@/components/ImportDialog.vue'

interface Room {
  id: number
  name: string
  capacity: number
  type: string
  building?: string | null
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

// ADR-0018: junior schools only distinguish normal and special-purpose rooms.
const JUNIOR_ROOM_TYPE_OPTIONS = [
  { value: 'NORMAL', label: '普通教室' },
  { value: 'LAB', label: '专用教室' },
]

function getTypeTagType(type: string): '' | 'success' | 'warning' | 'info' {
  switch (type) {
    case 'NORMAL':
      return ''
    case 'LAB':
      return 'warning'
    case 'PLAYGROUND':
      return 'success'
    case 'MULTIMEDIA':
    case 'OTHER':
    default:
      return 'info'
  }
}

const auth = useAuthStore()
const settings = useSettingsStore()
const isJunior = computed(() => settings.schoolType === 'junior')
const roomTypeOptions = computed(() =>
  isJunior.value ? JUNIOR_ROOM_TYPE_OPTIONS : ROOM_TYPE_OPTIONS,
)
const rooms = ref<Room[]>([])
const loading = ref(false)
const saving = ref(false)
const exporting = ref(false)
const dialogVisible = ref(false)
const importVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const defaultForm = {
  name: '',
  capacity: 50,
  type: 'NORMAL',
  building: '',
}

const form = reactive({ ...defaultForm })

const rules: FormRules = {
  name: [{ required: true, message: '请输入教室名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择教室类型', trigger: 'change' }],
  capacity: [{ required: true, message: '请输入容纳人数', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/rooms')
    rooms.value = data
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载教室列表失败')
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

function openEdit(row: Room) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    capacity: row.capacity ?? 50,
    type: row.type || 'NORMAL',
    building: row.building ?? '',
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
      capacity: form.capacity,
      type: form.type,
      building: form.building.trim() || null,
    }
    if (editingId.value) {
      await http.put(`/rooms/${editingId.value}`, payload)
      ElMessage.success('更新教室成功')
    } else {
      await http.post('/rooms', payload)
      ElMessage.success('创建教室成功')
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: Room) {
  try {
    await ElMessageBox.confirm(`确定要删除教室“${row.name}”吗？`, '删除确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await http.delete(`/rooms/${row.id}`)
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
    const res = await http.get('/export/rooms', { responseType: 'blob' })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '教室数据.xlsx'
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
</style>
