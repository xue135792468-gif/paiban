<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>课程管理</h2>
        <p class="subtitle">维护学校开设的课程科目与颜色，设定是否允许连堂。</p>
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
      <div v-if="groups.length > 0" class="group-filter-row">
        <el-select
          v-model="filterGroupId"
          class="group-filter"
          data-testid="course-group-filter"
        >
          <el-option label="全部分组" value="all" />
          <el-option
            v-for="group in groups"
            :key="group.id"
            :label="group.name"
            :value="group.id"
          />
        </el-select>
      </div>
      <EmptyStateGuide
        v-if="!loading && courses.length === 0"
        title="还没有课程数据"
        description="课程是授课任务与课表的基础。点击下方按钮新建课程，或使用右上角「导入」从 Excel 批量导入。"
        :button-text="auth.hasPermission('foundation:manage') ? '新建课程' : undefined"
        help-slug="foundation"
        help-anchor="courses"
        @action="openCreate"
      />
      <el-table v-else v-loading="loading" :data="filteredCourses" stripe>
        <el-table-column prop="name" label="课程名称" min-width="140">
          <template #default="{ row }">
            <span>{{ row.name }}</span>
            <span v-if="suffixOf(row)" class="group-suffix" :data-testid="`course-group-suffix-${row.id}`">
              · {{ suffixOf(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="所属学科" min-width="120">
          <template #default="{ row }">
            <span>{{ row.subject || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="allowConsecutive" label="允许连堂" width="110">
          <template #default="{ row }">
            <el-tag :type="row.allowConsecutive ? 'success' : 'info'" size="small">
              {{ row.allowConsecutive ? '允许' : '禁止' }}
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
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active === false ? 'info' : 'success'">
              {{ row.active === false ? '已停用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.hasPermission('foundation:manage')"
          label="操作"
          width="220"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <LifecycleCell entity-type="course" :row="row" @updated="load" />
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Download, Upload } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { getRuleGroups, type RuleGroup } from '@/api/ruleGroups'
import { useAuthStore } from '@/stores/auth'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import LifecycleCell from '@/components/lifecycle/LifecycleCell.vue'
import InfoTooltip from '@/components/InfoTooltip.vue'
import { serverMessage } from '@/api/errors'

interface Course {
  id: number
  name: string
  subject?: string | null
  allowConsecutive: boolean
  color?: string | null
  active?: boolean
}

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
const groups = ref<RuleGroup[]>([])
const filterGroupId = ref<number | 'all'>('all')

const filteredCourses = computed(() => {
  if (filterGroupId.value === 'all') {
    return courses.value
  }
  const group = groups.value.find((item) => item.id === filterGroupId.value)
  if (!group) {
    return courses.value
  }
  const memberIds = new Set(
    group.members
      .filter((member) => member.kind === 'COURSE')
      .map((member) => member.objectId),
  )
  return courses.value.filter((course) => memberIds.has(course.id))
})

/** 「技能课」/「技能课、理科实验」 for the name column. */
function courseGroupNames(courseId: number): string[] {
  return groups.value
    .filter((group) =>
      group.members.some((member) => member.kind === 'COURSE' && member.objectId === courseId))
    .map((group) => group.name)
}

function suffixOf(row: Course): string {
  return courseGroupNames(row.id).join('、')
}

const defaultForm = {
  name: '',
  subject: '',
  allowConsecutive: false,
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
    ElMessage.error(serverMessage(e, '加载课程列表失败'))
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
    ElMessage.error(serverMessage(e, '保存失败'))
  } finally {
    saving.value = false
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
    } else {
      message = serverMessage(e, message)
    }
    ElMessage.error(message)
  } finally {
    exporting.value = false
  }
}

/**
 * Groups only decorate the list; a missing read permission must not block the
 * course page, so a failure leaves the suffix/filter empty.
 */
async function loadGroups() {
  try {
    groups.value = await getRuleGroups('COURSE')
  } catch (e: unknown) {
    groups.value = []
    console.warn('加载课程分组失败', e)
  }
}

onMounted(async () => {
  await Promise.all([load(), loadGroups()])
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
.group-suffix {
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
}
.group-filter-row {
  margin-bottom: 12px;
}
.group-filter {
  width: 220px;
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
