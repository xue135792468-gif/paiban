<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>用户管理</h2>
        <p class="subtitle">创建本地账号并分配角色。教师登录后可查看个人课表并提交调代申请。</p>
      </div>
      <div class="toolbar-actions">
        <el-button @click="importVisible = true">批量导入账号</el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span>新建用户</span>
        </el-button>
      </div>
    </div>

    <ImportDialog
      v-model="importVisible"
      entity="users"
      entity-label="用户账号"
      preview-url="/users/import/preview"
      commit-url="/users/import/commit"
      template-url="/users/import/template"
      @imported="load"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="users" stripe>
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="displayName" label="姓名" min-width="120" />
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="role in row.roles" :key="role" size="small" class="role-tag" effect="plain">
              {{ roleName(role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联教师" min-width="120">
          <template #default="{ row }">
            <span v-if="row.teacherId">{{ teacherName(row.teacherId) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新建用户" width="480px">
      <el-form :model="createForm" label-width="88px">
        <el-form-item label="用户名" required>
          <el-input v-model="createForm.username" placeholder="登录账号，建议使用工号" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="createForm.displayName" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="createForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="createForm.roleCodes" multiple style="width: 100%">
            <el-option v-for="role in roles" :key="role.code" :label="role.name" :value="role.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联教师">
          <el-select v-model="createForm.teacherId" clearable filterable style="width: 100%">
            <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑用户" width="480px">
      <el-form :model="editForm" label-width="88px">
        <el-form-item label="用户名">
          <el-input :model-value="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="editForm.displayName" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="editForm.roleCodes" multiple style="width: 100%">
            <el-option v-for="role in roles" :key="role.code" :label="role.name" :value="role.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联教师">
          <el-select v-model="editForm.teacherId" clearable filterable style="width: 100%">
            <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" title="重置密码" width="400px">
      <el-form label-width="88px">
        <el-form-item label="新密码">
          <el-input v-model="resetPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import ImportDialog from '@/components/ImportDialog.vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

interface UserRow {
  id: number
  username: string
  displayName: string
  enabled: boolean
  teacherId?: number | null
  roles: string[]
  permissions: string[]
}

interface RoleRow {
  id: number
  code: string
  name: string
}

interface TeacherOption {
  id: number
  name: string
}

const users = ref<UserRow[]>([])
const roles = ref<RoleRow[]>([])
const teachers = ref<TeacherOption[]>([])
const loading = ref(false)
const saving = ref(false)
const importVisible = ref(false)

const createVisible = ref(false)
const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  roleCodes: ['teacher'] as string[],
  teacherId: undefined as number | undefined,
})

const editVisible = ref(false)
const editForm = reactive({
  id: 0,
  username: '',
  displayName: '',
  enabled: true,
  roleCodes: [] as string[],
  teacherId: undefined as number | undefined,
})

const resetVisible = ref(false)
const resetPassword = ref('')
const resetUserId = ref(0)

const roleLabels: Record<string, string> = {
  admin: '教务管理员',
  teacher: '教师',
  head_teacher: '班主任',
}

function roleName(code: string) {
  return roleLabels[code] ?? code
}

function teacherName(id: number) {
  return teachers.value.find((teacher) => teacher.id === id)?.name ?? `#${id}`
}

async function load() {
  loading.value = true
  try {
    const [userRes, roleRes] = await Promise.all([http.get('/users'), http.get('/roles')])
    users.value = userRes.data
    roles.value = roleRes.data
  } finally {
    loading.value = false
  }
  try {
    const { data } = await http.get('/teachers/options')
    teachers.value = data.content ?? data
  } catch {
    teachers.value = []
  }
}

function openCreate() {
  Object.assign(createForm, {
    username: '',
    displayName: '',
    password: '',
    roleCodes: ['teacher'],
    teacherId: undefined,
  })
  createVisible.value = true
}

async function submitCreate() {
  saving.value = true
  try {
    await http.post('/users', createForm)
    ElMessage.success('用户已创建')
    createVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '创建失败'))
  } finally {
    saving.value = false
  }
}

function openEdit(row: UserRow) {
  Object.assign(editForm, {
    id: row.id,
    username: row.username,
    displayName: row.displayName,
    enabled: row.enabled,
    roleCodes: [...row.roles],
    teacherId: row.teacherId ?? undefined,
  })
  editVisible.value = true
}

async function submitEdit() {
  saving.value = true
  try {
    await http.put(`/users/${editForm.id}`, {
      displayName: editForm.displayName,
      enabled: editForm.enabled,
      roleCodes: editForm.roleCodes,
      teacherId: editForm.teacherId,
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

function openReset(row: UserRow) {
  resetUserId.value = row.id
  resetPassword.value = ''
  resetVisible.value = true
}

async function submitReset() {
  if (!resetPassword.value) {
    ElMessage.warning('请输入新密码')
    return
  }
  saving.value = true
  try {
    await http.post(`/users/${resetUserId.value}/reset-password`, { newPassword: resetPassword.value })
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '重置失败'))
  } finally {
    saving.value = false
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
}
.toolbar-actions {
  display: flex;
  gap: 8px;
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
.role-tag {
  margin-right: 6px;
}
.muted {
  color: #9ca3af;
}
</style>
