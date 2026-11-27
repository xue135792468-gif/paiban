<template>
  <el-card
    v-if="submitError && !activeJob"
    shadow="never"
    class="section-card job-submit-error-card"
    data-testid="job-submit-error"
  >
    <template #header>
      <div class="active-job-header">
        <div class="job-header-left">
          <span class="card-title">当前排课任务</span>
          <el-tag type="danger" effect="light">提交失败</el-tag>
        </div>
      </div>
    </template>
    <el-alert
      type="error"
      :closable="true"
      show-icon
      title="排课任务未能启动"
      :description="submitError"
      @close="emit('clearSubmitError')"
    />
  </el-card>

  <el-card
    v-else-if="activeJob"
    shadow="never"
    class="section-card active-job-card"
    :class="{ 'is-warning': hasConflicts, 'is-complete': cleanCompleted }"
    data-testid="active-job-card"
  >
    <template v-if="cleanCompleted && !detailsExpanded" #header>
      <div class="completed-job-summary" data-testid="completed-job-summary">
        <el-icon><CircleCheckFilled /></el-icon>
        <strong>无冲突 · 可发布</strong>
        <span v-if="activeJob?.draftName">{{ activeJob.draftName }}</span>
        <el-tag type="success" effect="light" data-testid="active-job-status">已完成</el-tag>
        <div class="job-header-actions">
          <el-button link type="primary" data-testid="expand-job-details" @click="detailsExpanded = true">
            展开详情
          </el-button>
          <el-button v-if="activeJob?.draftId" type="primary" @click="emit('openDraft', activeJob.draftId)">
            打开草稿
          </el-button>
        </div>
      </div>
    </template>
    <template v-else #header>
      <div class="active-job-header">
        <div class="job-header-left">
          <span class="card-title">当前排课任务</span>
          <el-tag :type="statusTag.type" effect="light" data-testid="active-job-status">
            {{ statusTag.label }}
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
            @click="emit('cancel', activeJob.id)"
          >
            取消任务
          </el-button>
          <el-button
            v-if="activeJob.status === 'COMPLETED' && activeJob.draftId"
            :type="hasConflicts ? 'warning' : 'primary'"
            @click="emit('openDraft', activeJob.draftId)"
          >
            打开草稿
          </el-button>
        </div>
      </div>
    </template>

    <div v-if="!cleanCompleted || detailsExpanded" class="active-job-body" data-testid="job-details">
      <div class="progress-wrapper">
        <el-progress
          :percentage="Math.min(100, Math.max(0, activeJob.progress))"
          :status="progressStatus"
          :stroke-width="16"
          striped
          :striped-flow="isRunning(activeJob.status)"
        />
      </div>

      <el-alert
        v-if="hasConflicts"
        type="warning"
        :closable="false"
        show-icon
        :title="conflictTitle"
        :description="conflictDescription"
        data-testid="job-conflict-warning"
      />

      <el-alert
        v-if="activeJob.warmStartSource === 'NONE'"
        title="该学期没有可用的已发布课表或完整导入草稿，本次将从零搜索，可能仍需人工调整；可加长求解时间或先导入草稿。"
        type="warning"
        :closable="false"
        show-icon
      />

      <SchedulingJobMetrics :job="activeJob" />

      <el-alert
        v-if="activeJob.status === 'FAILED'"
        type="error"
        :closable="false"
        show-icon
        :title="activeJob.errorMessage || '排课任务求解失败，请检查排课约束与授课数据'"
        class="job-error-alert"
      />

      <JobDiagnostics
        v-if="activeJob.diagnostics?.length"
        :diagnostics="activeJob.diagnostics"
        @rerun="emit('rerun')"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import SchedulingJobMetrics from './SchedulingJobMetrics.vue'
import JobDiagnostics from './JobDiagnostics.vue'
import { getJobHardConflictCount, getJobStatusTag, isCompletedWithConflicts, isRunning } from './jobStatus'
import type { DraftItem, JobItem } from './types'

const props = defineProps<{
  activeJob: JobItem | null
  associatedDraft: DraftItem | null
  submitError: string | null
  cancelling: boolean
}>()

const emit = defineEmits<{
  cancel: [jobId: number]
  openDraft: [draftId: number]
  rerun: []
  clearSubmitError: []
}>()

const conflictCount = computed(() => getJobHardConflictCount(props.activeJob, props.associatedDraft))

const hasConflicts = computed(() => isCompletedWithConflicts(props.activeJob, props.associatedDraft))

const cleanCompleted = computed(() => props.activeJob?.status === 'COMPLETED' && !hasConflicts.value)
const detailsExpanded = ref(false)
watch(
  () => props.activeJob?.id,
  () => {
    detailsExpanded.value = false
  },
)

const statusTag = computed(() => getJobStatusTag(props.activeJob?.status, hasConflicts.value))

const progressStatus = computed(() => {
  if (!props.activeJob) return undefined
  if (props.activeJob.status === 'COMPLETED') {
    return hasConflicts.value ? 'warning' : 'success'
  }
  if (props.activeJob.status === 'FAILED') return 'exception'
  return undefined
})

const conflictTitle = computed(() =>
  conflictCount.value > 0
    ? `本次排课仍有 ${conflictCount.value} 处排课冲突，暂不可发布`
    : '本次排课存在阻塞项，暂不可发布',
)

const conflictDescription = computed(() => {
  const blockers = props.activeJob?.resultBlockers?.filter(Boolean) ?? []
  const prefix = blockers.length > 0 ? `${blockers.join('；')}。` : ''
  return `${prefix}请查看下方冲突诊断，或点击「打开草稿」进入草稿编辑器查看并解决冲突。`
})
</script>

<style scoped>
.section-card {
  border-radius: var(--paiban-radius);
}

.active-job-card {
  border-color: #dbeafe;
  background: #f8fafc;
}

.active-job-card.is-warning {
  border-color: #fde68a;
  background: #fffbeb;
}

.active-job-card.is-complete {
  border-color: #bbf7d0;
  background: #f0fdf4;
}

.active-job-card.is-complete :deep(.el-card__header) {
  padding: 10px 16px;
}

.completed-job-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #15803d;
}

.completed-job-summary .job-header-actions {
  margin-left: auto;
}

.job-submit-error-card {
  border-color: #fecaca;
  background: #fff7f7;
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

.job-error-alert {
  margin-top: 4px;
}
</style>
