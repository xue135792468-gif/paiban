<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>教师管理</h2>
        <p class="subtitle">维护全校专兼职任课教师的基础档案信息、工号及任教学科。</p>
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
          <span>新建教师</span>
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <EmptyStateGuide
        v-if="!loading && teachers.length === 0"
        title="还没有教师数据"
        description="教师是授课任务与调代的基础。点击下方按钮新建教师，或使用右上角「导入」从 Excel 批量导入。"
        :button-text="auth.hasPermission('foundation:manage') ? '新建教师' : undefined"
        help-slug="foundation"
        help-anchor="teachers"
        @action="openCreate"
      />
      <el-table v-else v-loading="loading" :data="teachers" stripe>
        <el-table-column prop="employeeNo" label="工号" min-width="120">
          <template #default="{ row }">
            <span>{{ row.employeeNo || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column prop="subject" label="任教学科" min-width="120">
          <template #default="{ row }">
            <span>{{ row.subject || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="联系电话" min-width="140">
          <template #default="{ row }">
            <span>{{ row.phone || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="电子邮箱" min-width="180">
          <template #default="{ row }">
            <span>{{ row.email || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="平台绑定" min-width="150">
          <template #default="{ row }">
            <div class="binding-tags">
              <el-tag
                v-if="row.bindingStatus === 'dingtalk' || row.bindingStatus === 'both'"
                size="small"
              >
                钉钉
              </el-tag>
              <el-tag
                v-if="row.bindingStatus === 'feishu' || row.bindingStatus === 'both'"
                size="small"
                type="success"
              >
                飞书
              </el-tag>
              <el-tag v-if="!row.bindingStatus || row.bindingStatus === 'none'" size="small" type="info">
                未绑定
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="active" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'">
              {{ row.active ? '在职' : '离职' }}
            </el-tag>
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
      :title="editingId ? '编辑教师' : '新建教师'"
      width="500px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" placeholder="请输入教师姓名" />
        </el-form-item>
        <el-form-item label="工号" prop="employeeNo">
          <el-input v-model="form.employeeNo" placeholder="请输入教师工号（选填）" />
        </el-form-item>
        <el-form-item label="任教学科" prop="subject">
          <el-input v-model="form.subject" placeholder="如：数学、语文、英语（选填）" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入联系电话（选填）" />
        </el-form-item>
        <el-form-item label="电子邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入电子邮箱（选填）" />
        </el-form-item>
        <el-divider content-position="left">平台绑定</el-divider>
        <el-form-item label="钉钉 UserID">
          <el-input v-model="form.dingtalkUserId" placeholder="留空表示清除绑定" />
        </el-form-item>
        <el-form-item label="飞书 UserID">
          <el-input v-model="form.feishuUserId" placeholder="留空表示清除绑定" />
        </el-form-item>
        <el-form-item label="飞书 OpenID">
          <el-input v-model="form.feishuOpenId" placeholder="留空表示清除绑定" />
        </el-form-item>
        <el-form-item label="状态" prop="active">
          <el-switch
            v-model="form.active"
            active-text="在职"
            inactive-text="离职"
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
      entity="teachers"
      entity-label="教师"
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
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ImportDialog from '@/components/ImportDialog.vue'

interface Teacher {
  id: number
  name: string
  employeeNo?: string | null
  phone?: string | null
  email?: string | null
  subject?: string | null
  active: boolean
  dingtalkUserId?: string | null
  feishuUserId?: string | null
  feishuOpenId?: string | null
  bindingStatus?: 'both' | 'dingtalk' | 'feishu' | 'none'
}

const auth = useAuthStore()
const teachers = ref<Teacher[]>([])
const loading = ref(false)
const saving = ref(false)
const exporting = ref(false)
const dialogVisible = ref(false)
const importVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const defaultForm = {
  name: '',
  employeeNo: '',
  phone: '',
  email: '',
  subject: '',
  active: true,
  dingtalkUserId: '',
  feishuUserId: '',
  feishuOpenId: '',
}

const form = reactive({ ...defaultForm })

const rules: FormRules = {
  name: [{ required: true, message: '请输入教师姓名', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/teachers')
    teachers.value = data
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '加载教师列表失败')
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

function openEdit(row: Teacher) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    employeeNo: row.employeeNo ?? '',
    phone: row.phone ?? '',
    email: row.email ?? '',
    subject: row.subject ?? '',
    active: row.active ?? true,
    dingtalkUserId: row.dingtalkUserId ?? '',
    feishuUserId: row.feishuUserId ?? '',
    feishuOpenId: row.feishuOpenId ?? '',
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
      employeeNo: form.employeeNo.trim() || null,
      phone: form.phone.trim() || null,
      email: form.email.trim() || null,
      subject: form.subject.trim() || null,
      active: form.active,
      // Empty string clears a binding; the backend treats an omitted field as unchanged.
      dingtalkUserId: form.dingtalkUserId.trim(),
      feishuUserId: form.feishuUserId.trim(),
      feishuOpenId: form.feishuOpenId.trim(),
    }
    if (editingId.value) {
      await http.put(`/teachers/${editingId.value}`, payload)
      ElMessage.success('更新教师成功')
    } else {
      await http.post('/teachers', payload)
      ElMessage.success('创建教师成功')
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: Teacher) {
  try {
    await ElMessageBox.confirm(`确定要删除教师“${row.name}”吗？`, '删除确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await http.delete(`/teachers/${row.id}`)
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
    const res = await http.get('/export/teachers', { responseType: 'blob' })
    const blob = new Blob([res.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = '教师数据.xlsx'
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
.binding-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
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
