<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>学期与作息</h2>
        <p class="subtitle">学期之间数据严格隔离。作息表决定排课可用的课节（星期 + 第几节）。</p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新建学期</span>
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="semesters" stripe>
        <el-table-column prop="name" label="学期" min-width="180">
          <template #default="{ row }">
            <span class="semester-name">{{ row.name }}</span>
            <el-tag v-if="row.current" size="small" type="success" effect="light" class="current-tag">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startDate" label="开始日期" width="130" />
        <el-table-column prop="endDate" label="结束日期" width="130" />
        <el-table-column label="课节数" width="110">
          <template #default="{ row }">
            <span>{{ periodCounts[row.id] ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/admin/semesters/${row.id}/schedule`)">
              作息表
            </el-button>
            <el-button link type="primary" :disabled="row.current" @click="setCurrent(row)">设为当前</el-button>
            <el-button link type="primary" :disabled="semesters.length < 2" @click="openCopy(row)">
              复制结构
            </el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" :disabled="row.current" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="copyVisible" title="复制学期结构" width="460px">
      <el-alert
        title="将复制来源学期的作息表、年级、班级与授课任务到当前学期（不含已发布课表与调代记录）。"
        type="info"
        :closable="false"
        show-icon
        class="copy-hint"
      />
      <el-form label-width="90px">
        <el-form-item label="目标学期">
          <span>{{ copyTarget?.name }}</span>
        </el-form-item>
        <el-form-item label="来源学期">
          <el-select v-model="copySourceId" placeholder="选择要复制的学期" style="width: 100%">
            <el-option
              v-for="item in copyCandidates"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="copying" :disabled="!copySourceId" @click="confirmCopy">
          开始复制
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑学期' : '新建学期'" width="440px">
      <el-form :model="form" label-width="88px">
        <el-form-item label="学期名称" required>
          <el-input v-model="form.name" placeholder="如：2025-2026 学年第一学期" />
        </el-form-item>
        <el-form-item label="开始日期" required>
          <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束日期" required>
          <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { http } from '@/api/http'

interface Semester {
  id: number
  name: string
  startDate: string
  endDate: string
  current: boolean
}

const router = useRouter()
const semesters = ref<Semester[]>([])
const periodCounts = reactive<Record<number, number>>({})
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ name: '', startDate: '', endDate: '' })

const copyVisible = ref(false)
const copying = ref(false)
const copyTarget = ref<Semester | null>(null)
const copySourceId = ref<number | null>(null)

const copyCandidates = computed(() =>
  semesters.value.filter((semester) => semester.id !== copyTarget.value?.id),
)

function openCopy(row: Semester) {
  copyTarget.value = row
  copySourceId.value = copyCandidates.value.length > 0 ? copyCandidates.value[0].id : null
  copyVisible.value = true
}

async function confirmCopy() {
  if (!copyTarget.value || !copySourceId.value) return
  copying.value = true
  try {
    const { data } = await http.post(
      `/semesters/${copyTarget.value.id}/copy-from/${copySourceId.value}`,
    )
    ElMessage.success(
      `复制完成：作息 ${data.periods} 节、年级 ${data.grades} 个、班级 ${data.classes} 个、授课任务 ${data.assignments} 条`,
    )
    copyVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '复制失败')
  } finally {
    copying.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/semesters')
    semesters.value = data
    await Promise.all(
      data.map(async (semester: Semester) => {
        try {
          const res = await http.get(`/semesters/${semester.id}/periods`)
          periodCounts[semester.id] = res.data.length
        } catch {
          periodCounts[semester.id] = 0
        }
      }),
    )
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', startDate: '', endDate: '' })
  dialogVisible.value = true
}

function openEdit(row: Semester) {
  editingId.value = row.id
  Object.assign(form, { name: row.name, startDate: row.startDate, endDate: row.endDate })
  dialogVisible.value = true
}

async function submit() {
  if (!form.name || !form.startDate || !form.endDate) {
    ElMessage.warning('请填写完整的学期信息')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await http.put(`/semesters/${editingId.value}`, form)
      ElMessage.success('已保存')
    } else {
      await http.post('/semesters', form)
      ElMessage.success('学期已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function setCurrent(row: Semester) {
  try {
    await http.post(`/semesters/${row.id}/set-current`)
    ElMessage.success(`已将「${row.name}」设为当前学期`)
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '操作失败')
  }
}

async function remove(row: Semester) {
  try {
    await ElMessageBox.confirm(`确定删除学期「${row.name}」及其作息表吗？`, '删除学期', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/semesters/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '删除失败')
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
h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.semester-name {
  font-weight: 500;
}
.current-tag {
  margin-left: 8px;
}
.copy-hint {
  margin-bottom: 16px;
}
</style>
