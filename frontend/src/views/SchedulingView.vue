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
        <el-button v-if="canImport" @click="goImportTimetable">导入课表</el-button>
        <el-button :loading="refreshing" @click="handleManualRefresh">刷新</el-button>
      </div>
    </div>

    <EmptyStateGuide
      v-if="!semesterId"
      title="还没有学期"
      description="排课任务按学期提交。请先在「学期与作息」中创建学期，再回来发起排课。"
      button-text="去创建学期"
      button-to="/data/semesters"
      help-slug="scheduling"
      help-anchor="run"
    />

    <div v-else class="content-body">
      <el-alert
        v-if="showPrerequisiteHint"
        :title="missingPrerequisiteMessage"
        type="warning"
        :closable="false"
        show-icon
        class="hint-alert"
        data-testid="prerequisite-hint"
      />

      <el-card shadow="never" class="section-card">
        <template #header>
          <div class="card-header">
            <span>发起排课任务</span>
          </div>
        </template>
        <el-form :inline="true" :model="submitForm" class="submit-form">
          <el-form-item label="排课年级">
            <GradeScopePicker
              v-model="selectedGradeIds"
              :grades="grades"
              :partial-allowed="hasPublishedBaseline"
            />
          </el-form-item>
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
              :disabled="isJobRunning || partialScopeBlocked"
              @click="handleStartJob"
            >
              开始排课
            </el-button>
          </el-form-item>
        </el-form>
        <el-alert
          v-if="partialScopeBlocked"
          type="warning"
          :closable="false"
          show-icon
          class="partial-scope-alert"
          data-testid="partial-scope-warning"
          title="其他年级尚未发布课表，当前仅支持全校排课（不能单独保持其他年级不动）"
        >
          <template #default>
            <div class="partial-scope-actions">
              <span>请先全选全部年级进行整校排课，或先发布其他年级课表后再局部重排。</span>
              <el-button link type="primary" @click="selectAllGrades">全选全部年级</el-button>
            </div>
          </template>
        </el-alert>
      </el-card>

      <ActiveJobCard
        :active-job="activeJob"
        :associated-draft="activeJobDraft"
        :submit-error="submitError"
        :cancelling="cancelling"
        @cancel="handleCancelJob"
        @open-draft="openDraftEditor"
        @rerun="handleRerunDiagnosedGrades"
        @clear-submit-error="submitError = null"
      />

      <el-card shadow="never" class="section-card">
        <template #header>
          <div class="card-header">
            <span>课表草稿</span>
          </div>
        </template>

        <EmptyStateGuide
          v-if="!draftsLoading && drafts.length === 0"
          title="还没有课表草稿"
          description="提交一次排课任务后，生成的课表会出现在这里，可以打开编辑器手工微调。"
          help-slug="scheduling"
          help-anchor="draft"
        />
        <el-table v-else v-loading="draftsLoading" :data="drafts" style="width: 100%">
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="draftStatusTag(row.status)" effect="light">
                {{ draftStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="求解得分" min-width="180">
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
                data-testid="draft-delete"
                :disabled="row.status !== 'DRAFT'"
                :title="row.status === 'DRAFT' ? '删除后可在「最近删除」中恢复' : '已发布或已归档的课表不能直接删除'"
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

        <EmptyStateGuide
          v-if="!jobsLoading && jobs.length === 0"
          title="还没有排课历史"
          description="每次提交的排课任务都会记录在这里，包括状态、求解得分与耗时。"
          help-slug="scheduling"
          help-anchor="run"
        />
        <el-table v-else v-loading="jobsLoading" :data="jobs" style="width: 100%">
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
          <el-table-column label="状态" width="170">
            <template #default="{ row }">
              <el-tag
                :type="getJobStatusTag(row.status, isJobConflicted(row)).type"
                effect="light"
              >
                {{ getJobStatusTag(row.status, isJobConflicted(row)).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="求解得分" min-width="160">
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
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import ActiveJobCard from '@/components/scheduling/ActiveJobCard.vue'
import GradeScopePicker from '@/components/scheduling/GradeScopePicker.vue'
import {
  getJobStatusTag,
  isCompletedWithConflicts,
  isRunning,
  isTerminal,
} from '@/components/scheduling/jobStatus'
import type { DraftItem, GradeOption, JobItem, Semester } from '@/components/scheduling/types'
import { useAuthStore } from '@/stores/auth'
import { serverMessage } from '@/api/errors'
import { formatDateTime } from '@/utils/datetime'
import { deleteDraftWithConfirmation } from '@/composables/useDraftDeletion'

const auth = useAuthStore()
const router = useRouter()

const semesters = ref<Semester[]>([])
const semesterId = ref<number | null>(null)
const grades = ref<GradeOption[]>([])
const selectedGradeIds = ref<number[]>([])
const drafts = ref<DraftItem[]>([])
const jobs = ref<JobItem[]>([])
const assignmentCount = ref<number | null>(null)
const periodCount = ref<number | null>(null)
const activeJob = ref<JobItem | null>(null)
const submitError = ref<string | null>(null)

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
const canImport = computed(() => auth.hasPermission('foundation:manage'))

const isJobRunning = computed(() => {
  return isRunning(activeJob.value?.status)
})

const hasPublishedBaseline = computed(() =>
  drafts.value.some((draft) => draft.status === 'PUBLISHED'),
)

const partialScopeBlocked = computed(() => {
  const total = grades.value.length
  const count = selectedGradeIds.value.length
  return !hasPublishedBaseline.value && total > 1 && count > 0 && count < total
})

const activeJobDraft = computed(() => draftForJob(activeJob.value?.draftId))

const missingPrerequisiteMessage = computed(() => {
  if (!semesterId.value) return ''
  const missingAssignments = assignmentCount.value === 0
  const missingPeriods = periodCount.value === 0
  if (missingAssignments && missingPeriods) {
    return '排课前请先录入授课任务并配置作息表'
  }
  if (missingAssignments) {
    return '排课前请先录入授课任务'
  }
  if (missingPeriods) {
    return '排课前请先配置作息表'
  }
  return ''
})

const showPrerequisiteHint = computed(() => missingPrerequisiteMessage.value.length > 0)

function draftForJob(draftId?: number | null): DraftItem | null {
  if (!draftId) return null
  return drafts.value.find((draft) => draft.id === draftId) ?? null
}

function isJobConflicted(job: JobItem): boolean {
  return isCompletedWithConflicts(job, draftForJob(job.draftId))
}

function selectAllGrades() {
  selectedGradeIds.value = grades.value.map((grade) => grade.id)
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
        await Promise.all([loadDrafts(), loadHistory()])
        if (data.status === 'COMPLETED') {
          if (isCompletedWithConflicts(data, draftForJob(data.draftId))) {
            ElMessage.warning('排课已完成，但存在冲突暂不可发布，请打开草稿处理冲突')
          } else {
            ElMessage.success('排课已完成')
          }
        } else if (data.status === 'FAILED') {
          ElMessage.error(data.errorMessage ? `排课失败：${data.errorMessage}` : '排课失败')
        } else if (data.status === 'CANCELLED') {
          ElMessage.info('排课任务已取消')
        }
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
    ElMessage.error(serverMessage(e, '加载学期失败'))
  }
}

async function loadGrades() {
  if (!semesterId.value) {
    grades.value = []
    selectedGradeIds.value = []
    return
  }
  try {
    const { data } = await http.get<GradeOption[]>('/grades', {
      params: { semesterId: semesterId.value },
    })
    grades.value = data ?? []
    // The default scope is the whole school; the user narrows it for a batched solve.
    selectedGradeIds.value = grades.value.map((grade) => grade.id)
  } catch {
    grades.value = []
    selectedGradeIds.value = []
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
    ElMessage.error(serverMessage(e, '加载课表草稿失败'))
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
    ElMessage.error(serverMessage(e, '加载排课历史失败'))
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

async function checkPeriods() {
  if (!semesterId.value) {
    periodCount.value = null
    return
  }
  try {
    const { data } = await http.get(`/semesters/${semesterId.value}/periods`)
    periodCount.value = Array.isArray(data) ? data.length : 0
  } catch {
    periodCount.value = null
  }
}

async function refreshSemesterData() {
  if (!semesterId.value) {
    drafts.value = []
    jobs.value = []
    assignmentCount.value = null
    periodCount.value = null
    activeJob.value = null
    submitError.value = null
    grades.value = []
    selectedGradeIds.value = []
    return
  }
  await Promise.all([loadDrafts(), loadHistory(), checkAssignments(), checkPeriods(), loadGrades()])
  const running = jobs.value.find((j) => isRunning(j.status))
  if (running) {
    submitError.value = null
    activeJob.value = running
    startPolling(running.id)
  } else {
    // A finished job with teacher diagnostics stays visible after a reload, so the
    // "连带重排" entry point is not lost the moment polling stops.
    activeJob.value = jobs.value.find((job) => job.diagnostics?.length) ?? null
  }
}

async function onSemesterChange() {
  stopPolling()
  activeJob.value = null
  submitError.value = null
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

async function submitJob(gradeIds: number[], draftName?: string) {
  if (!semesterId.value) return
  submitting.value = true
  submitError.value = null
  try {
    const { data } = await http.post<JobItem>(
      '/scheduling-jobs',
      {
        draftName: draftName?.trim() || undefined,
        maxSeconds: submitForm.maxSeconds,
        gradeIds,
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
    const message = serverMessage(e, '提交排课任务失败')
    stopPolling()
    activeJob.value = null
    submitError.value = message
    ElMessage.error(message)
  } finally {
    submitting.value = false
  }
}

function handleStartJob() {
  return submitJob(selectedGradeIds.value, submitForm.draftName)
}

/**
 * The diagnosed teachers' grades plus whatever the form already had selected. The union
 * is a new job with the same semester and duration; the backend needs no extra concept.
 */
async function handleRerunDiagnosedGrades() {
  const merged = new Set<number>(selectedGradeIds.value)
  for (const diagnostic of activeJob.value?.diagnostics ?? []) {
    for (const gradeId of diagnostic.gradeIds ?? []) merged.add(gradeId)
  }
  const ordered = grades.value.map((grade) => grade.id).filter((id) => merged.has(id))
  selectedGradeIds.value = ordered
  await submitJob(ordered)
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
      ElMessage.error(serverMessage(e, '取消排课任务失败'))
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
      ElMessage.error(serverMessage(e, '重命名失败'))
    }
  }
}

function draftStatusLabel(status: string): string {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'ARCHIVED') return '已归档'
  return '草稿'
}

function draftStatusTag(status: string): 'success' | 'warning' | 'info' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'ARCHIVED') return 'warning'
  return 'info'
}

async function handleDeleteDraft(row: DraftItem) {
  if (row.status !== 'DRAFT') {
    ElMessage.warning(row.status === 'PUBLISHED'
      ? '已发布的课表请先撤回再删除'
      : '已归档的课表不能删除')
    return
  }
  const outcome = await deleteDraftWithConfirmation({
    id: row.id,
    name: row.name,
  })
  if (outcome === 'cancelled') return
  if (outcome === 'deleted' && activeJob.value?.draftId === row.id) {
    activeJob.value = null
  }
  await loadDrafts()
}

function openDraftEditor(draftId: number) {
  router.push(`/admin/drafts/${draftId}`)
}

function goImportTimetable() {
  router.push('/admin/ai-import')
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

.partial-scope-alert {
  margin-top: 12px;
}

.partial-scope-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
