<template>
  <div class="scheduling-page">
    <div class="toolbar">
      <div>
        <h2>自动排课</h2>
        <p class="subtitle">提交排课任务后可在草稿上手工微调，满意后发布为正式课表。</p>
      </div>
      <div class="actions">
        <el-select
          v-model="semesterId"
          placeholder="请选择学期"
          :disabled="semesters.length === 0"
          style="width: 220px"
          @change="onSemesterChange"
        >
          <el-option
            v-for="s in semesters"
            :key="s.id"
            :label="s.name + (s.current ? ' (当前)' : '')"
            :value="s.id"
          />
        </el-select>
        <el-button :loading="refreshing" @click="handleManualRefresh">刷新</el-button>
      </div>
    </div>

    <el-empty v-if="!semesterId" description="暂无学期数据，请先创建或选择学期" />

    <div v-else class="content-body">
      <el-alert
        v-if="showPrerequisiteHint"
        title="排课前请先录入授课任务、配置作息表和教室"
        type="warning"
        :closable="false"
        show-icon
        class="hint-alert"
      />

      <el-card shadow="never" class="section-card">
        <template #header>
          <div class="card-header">
            <span>发起排课任务</span>
          </div>
        </template>
        <el-form :inline="true" :model="submitForm" class="submit-form">
          <el-form-item label="草稿名称">
            <el-input
              v-model="submitForm.draftName"
              placeholder="方案 A"
              clearable
              style="width: 240px"
            />
          </el-form-item>
          <el-form-item label="最大求解时长(秒)">
            <el-input-number
              v-model="submitForm.maxSeconds"
              :min="5"
              :max="600"
              :step="10"
              style="width: 160px"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              v-if="canRunScheduling"
              type="primary"
              :loading="submitting"
              :disabled="isJobRunning"
              @click="handleStartJob"
            >
              开始排课
            </el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card v-if="activeJob" shadow="never" class="section-card active-job-card">
        <template #header>
          <div class="active-job-header">
            <div class="job-header-left">
              <span class="card-title">当前排课任务</span>
              <el-tag :type="getJobStatusTag(activeJob.status).type" effect="light">
                {{ getJobStatusTag(activeJob.status).label }}
              </el-tag>
              <span v-if="activeJob.draftName" class="job-draft-name">
                {{ activeJob.draftName }}
              </span>
            </div>
            <div class="job-header-actions">
              <el-button
                v-if="isRunning(activeJob.status)"
                type="danger"
                plain
                :loading="cancelling"
                @click="handleCancelJob(activeJob.id)"
              >
                取消任务
              </el-button>
              <el-button
                v-if="activeJob.status === 'COMPLETED' && activeJob.draftId"
                type="primary"
                @click="openDraftEditor(activeJob.draftId)"
              >
                打开草稿
              </el-button>
            </div>
          </div>
        </template>

        <div class="active-job-body">
          <div class="progress-wrapper">
            <el-progress
              :percentage="Math.min(100, Math.max(0, activeJob.progress))"
              :status="
                activeJob.status === 'COMPLETED'
                  ? 'success'
                  : activeJob.status === 'FAILED'
                    ? 'exception'
                    : undefined
              "
              :stroke-width="16"
              striped
              :striped-flow="isRunning(activeJob.status)"
            />
          </div>

          <div class="metric-grid">
            <div class="metric-item">
              <span class="metric-label">当前得分</span>
              <span class="metric-value">{{ activeJob.score || '暂无得分' }}</span>
            </div>
            <div class="metric-item">
              <span class="metric-label">已耗时</span>
              <span class="metric-value">{{ Math.round((activeJob.elapsedMs || 0) / 1000) }} 秒</span>
            </div>
            <div class="metric-item">
              <span class="metric-label">预计剩余</span>
              <span class="metric-value">
                {{ activeJob.status === 'RUNNING' ? Math.max(0, Math.round((activeJob.estimatedRemainingMs || 0) / 1000)) + ' 秒' : '—' }}
              </span>
            </div>
            <div class="metric-item">
              <span class="metric-label">设定时长</span>
              <span class="metric-value">{{ activeJob.solveSeconds }} 秒</span>
            </div>
            <div class="metric-item">
              <span class="metric-label">创建时间</span>
              <span class="metric-value">{{ formatDateTime(activeJob.createdAt) }}</span>
            </div>
          </div>

          <el-alert
            v-if="activeJob.status === 'FAILED'"
            type="error"
            :closable="false"
            show-icon
            :title="activeJob.errorMessage || '排课任务求解失败，请检查排课约束与授课数据'"
            class="job-error-alert"
          />
        </div>
      </el-card>

      <el-card shadow="never" class="section-card">
        <template #header>
          <div class="card-header">
            <span>课表草稿</span>
          </div>
        </template>

        <el-table v-loading="draftsLoading" :data="drafts" style="width: 100%">
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" effect="light">
                {{ row.status === 'PUBLISHED' ? '已发布' : '草稿' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="硬分/软分" min-width="180">
            <template #default="{ row }">
              <span>{{ row.scoreHard }} hard / {{ row.scoreSoft }} soft</span>
            </template>
          </el-table-column>
          <el-table-column prop="lessonCount" label="课节数" width="110" />
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">
              {{ formatDateTime(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDraftEditor(row.id)">
                打开编辑器
              </el-button>
              <el-button
                v-if="canEditDraft"
                link
                type="primary"
                @click="handleRenameDraft(row)"
              >
                重命名
              </el-button>
              <el-button
                v-if="canEditDraft"
                link
                type="danger"
                :disabled="row.status === 'PUBLISHED'"
                @click="handleDeleteDraft(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="section-card">
        <template #header>
          <div class="card-header">
            <span>排课历史</span>
          </div>
        </template>

        <el-table v-loading="jobsLoading" :data="jobs" style="width: 100%">
          <el-table-column label="创建时间" min-width="170">
            <template #default="{ row }">
              {{ formatDateTime(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="草稿名称" min-width="160">
            <template #default="{ row }">
              {{ row.draftName || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="getJobStatusTag(row.status).type" effect="light">
                {{ getJobStatusTag(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="得分" min-width="160">
            <template #default="{ row }">
              {{ row.score || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="耗时(秒)" width="110">
            <template #default="{ row }">
              {{ row.elapsedMs != null ? Math.round(row.elapsedMs / 1000) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'COMPLETED' && row.draftId"
                link
                type="primary"
                @click="openDraftEditor(row.draftId)"
              >
                查看草稿
              </el-button>
              <el-button
                v-if="isRunning(row.status)"
                link
                type="danger"
                @click="handleCancelJob(row.id)"
              >
                取消
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

interface Semester {
  id: number
  name: string
  startDate?: string
  endDate?: string
  current: boolean
}

interface JobItem {
  id: number
  semesterId: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string
  score?: string | null
  progress: number
  elapsedMs: number
  estimatedRemainingMs?: number
  errorMessage?: string | null
  draftId?: number | null
  draftName?: string | null
  solveSeconds: number
  createdAt: string
  startedAt?: string | null
  finishedAt?: string | null
}

interface DraftItem {
  id: number
  semesterId: number
  name: string
  status: 'DRAFT' | 'PUBLISHED' | string
  scoreHard: number
  scoreSoft: number
  createdBy?: string | null
  createdAt: string
  lessonCount: number
}

const auth = useAuthStore()
const router = useRouter()

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const drafts = ref<DraftItem[]>([])
const jobs = ref<JobItem[]>([])
const assignmentCount = ref<number | null>(null)
const activeJob = ref<JobItem | null>(null)

const draftsLoading = ref(false)
const jobsLoading = ref(false)
const refreshing = ref(false)
const submitting = ref(false)
const cancelling = ref(false)

const submitForm = reactive({
  draftName: '',
  maxSeconds: 60,
})

let pollTimer: ReturnType<typeof setInterval> | null = null

const canRunScheduling = computed(() => auth.hasPermission('scheduling:run'))
const canEditDraft = computed(() => auth.hasPermission('timetable:edit'))

const isJobRunning = computed(() => {
  return isRunning(activeJob.value?.status)
})

const showPrerequisiteHint = computed(() => {
  if (!semesterId.value) return false
  return drafts.value.length === 0 || assignmentCount.value === 0
})

function isRunning(status?: string | null) {
  return status === 'PENDING' || status === 'RUNNING'
}

function isTerminal(status?: string | null) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
}

function getJobStatusTag(status?: string | null): {
  type: 'warning' | 'primary' | 'success' | 'danger' | 'info'
  label: string
} {
  switch (status) {
    case 'PENDING':
      return { type: 'warning', label: '排队中' }
    case 'RUNNING':
      return { type: 'primary', label: '求解中' }
    case 'COMPLETED':
      return { type: 'success', label: '已完成' }
    case 'FAILED':
      return { type: 'danger', label: '失败' }
    case 'CANCELLED':
      return { type: 'info', label: '已取消' }
    default:
      return { type: 'info', label: status || '未知' }
  }
}

function formatDateTime(val?: string | null) {
  if (!val) return '-'
  try {
    const d = new Date(val)
    if (isNaN(d.getTime())) return val
    const pad = (n: number) => String(n).padStart(2, '0')
    const y = d.getFullYear()
    const m = pad(d.getMonth() + 1)
    const day = pad(d.getDate())
    const h = pad(d.getHours())
    const min = pad(d.getMinutes())
    const s = pad(d.getSeconds())
    return `${y}-${m}-${day} ${h}:${min}:${s}`
  } catch {
    return val
  }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling(jobId: number) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const { data } = await http.get<JobItem>(`/scheduling-jobs/${jobId}`)
      activeJob.value = data
      const idx = jobs.value.findIndex((j) => j.id === jobId)
      if (idx !== -1) {
        jobs.value[idx] = data
      }

      if (isTerminal(data.status)) {
        stopPolling()
        if (data.status === 'COMPLETED') {
          ElMessage.success('排课已完成')
        } else if (data.status === 'FAILED') {
          ElMessage.error(data.errorMessage ? `排课失败：${data.errorMessage}` : '排课失败')
        } else if (data.status === 'CANCELLED') {
          ElMessage.info('排课任务已取消')
        }
        await Promise.all([loadDrafts(), loadHistory()])
      }
    } catch {
      stopPolling()
    }
  }, 1000)
}

async function loadSemesters() {
  try {
    const [listRes, currentRes] = await Promise.all([
      http.get<Semester[]>('/semesters'),
      http.get<Semester>('/semesters/current', {
        validateStatus: (status) => status < 500,
      }),
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

async function loadDrafts() {
  if (!semesterId.value) {
    drafts.value = []
    return
  }
  draftsLoading.value = true
  try {
    const { data } = await http.get<DraftItem[]>('/timetable-drafts', {
      params: { semesterId: semesterId.value },
    })
    drafts.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载课表草稿失败')
  } finally {
    draftsLoading.value = false
  }
}

async function loadHistory() {
  if (!semesterId.value) {
    jobs.value = []
    return
  }
  jobsLoading.value = true
  try {
    const { data } = await http.get<JobItem[]>('/scheduling-jobs', {
      params: { semesterId: semesterId.value },
    })
    jobs.value = data ?? []
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载排课历史失败')
  } finally {
    jobsLoading.value = false
  }
}

async function checkAssignments() {
  if (!semesterId.value) {
    assignmentCount.value = null
    return
  }
  try {
    const { data } = await http.get('/assignments', {
      params: { semesterId: semesterId.value },
    })
    assignmentCount.value = Array.isArray(data) ? data.length : 0
  } catch {
    assignmentCount.value = null
  }
}

async function refreshSemesterData() {
  if (!semesterId.value) {
    drafts.value = []
    jobs.value = []
    assignmentCount.value = null
    activeJob.value = null
    return
  }
  await Promise.all([loadDrafts(), loadHistory(), checkAssignments()])
  const running = jobs.value.find((j) => isRunning(j.status))
  if (running) {
    activeJob.value = running
    startPolling(running.id)
  }
}

async function onSemesterChange() {
  stopPolling()
  activeJob.value = null
  await refreshSemesterData()
}

async function handleManualRefresh() {
  refreshing.value = true
  try {
    await refreshSemesterData()
    if (activeJob.value && isRunning(activeJob.value.status)) {
      const { data } = await http.get<JobItem>(`/scheduling-jobs/${activeJob.value.id}`)
      activeJob.value = data
      if (isTerminal(data.status)) {
        stopPolling()
        await Promise.all([loadDrafts(), loadHistory()])
      }
    }
  } finally {
    refreshing.value = false
  }
}

async function handleStartJob() {
  if (!semesterId.value) return
  submitting.value = true
  try {
    const { data } = await http.post<JobItem>(
      '/scheduling-jobs',
      {
        draftName: submitForm.draftName.trim() || undefined,
        maxSeconds: submitForm.maxSeconds,
      },
      {
        params: { semesterId: semesterId.value },
      },
    )
    activeJob.value = data
    ElMessage.success('已提交排课任务')
    submitForm.draftName = ''
    await loadHistory()
    if (isRunning(data.status)) {
      startPolling(data.id)
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '提交排课任务失败')
  } finally {
    submitting.value = false
  }
}

async function handleCancelJob(jobId: number) {
  try {
    await ElMessageBox.confirm('确定要取消该排课任务吗？', '取消确认', {
      confirmButtonText: '确定取消',
      cancelButtonText: '再等等',
      type: 'warning',
    })
    cancelling.value = true
    const { data } = await http.delete<JobItem>(`/scheduling-jobs/${jobId}`)
    ElMessage.success('已取消排课任务')
    if (activeJob.value?.id === jobId) {
      activeJob.value = data
      stopPolling()
    }
    await Promise.all([loadDrafts(), loadHistory()])
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.response?.data?.message ?? '取消排课任务失败')
    }
  } finally {
    cancelling.value = false
  }
}

async function handleRenameDraft(row: DraftItem) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的草稿名称', '重命名草稿', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: row.name,
      inputValidator: (val) => {
        if (!val || !val.trim()) {
          return '草稿名称不能为空'
        }
        if (val.trim().length > 100) {
          return '草稿名称不能超过100个字符'
        }
        return true
      },
    })
    await http.put(`/timetable-drafts/${row.id}/name`, { name: value.trim() })
    ElMessage.success('草稿重命名成功')
    await loadDrafts()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.response?.data?.message ?? '重命名失败')
    }
  }
}

async function handleDeleteDraft(row: DraftItem) {
  if (row.status === 'PUBLISHED') {
    ElMessage.warning('已发布的课表草稿不可删除')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定要删除草稿「${row.name}」吗？此操作不可撤销！`,
      '删除确认',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
    await http.delete(`/timetable-drafts/${row.id}`)
    ElMessage.success('草稿删除成功')
    if (activeJob.value?.draftId === row.id) {
      activeJob.value = null
    }
    await loadDrafts()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e?.response?.data?.message ?? '删除失败')
    }
  }
}

function openDraftEditor(draftId: number) {
  router.push(`/admin/drafts/${draftId}`)
}

onMounted(async () => {
  await loadSemesters()
  if (semesterId.value) {
    await refreshSemesterData()
  }
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.scheduling-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
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

.content-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hint-alert {
  margin-bottom: 0;
}

.section-card {
  border-radius: var(--paiban-radius);
}

.card-header {
  font-weight: 600;
  font-size: 15px;
}

.submit-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}

.submit-form :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: 20px;
}

.active-job-card {
  border-color: #dbeafe;
  background: #f8fafc;
}

.active-job-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.job-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.card-title {
  font-weight: 600;
  font-size: 15px;
}

.job-draft-name {
  color: var(--paiban-muted);
  font-size: 14px;
}

.job-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.active-job-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.progress-wrapper {
  padding: 4px 0;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  background: #ffffff;
  padding: 12px 16px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.metric-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-label {
  font-size: 12px;
  color: var(--paiban-muted);
}

.metric-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--paiban-ink);
}

.job-error-alert {
  margin-top: 4px;
}
</style>
