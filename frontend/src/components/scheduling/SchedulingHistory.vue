<template>
  <el-card shadow="never" class="history-card">
    <button
      type="button"
      class="history-toggle"
      data-testid="toggle-scheduling-history"
      :aria-expanded="expanded"
      aria-controls="scheduling-history"
      @click="expanded = !expanded"
    >
      <el-icon><ArrowRight v-if="!expanded" /><ArrowDown v-else /></el-icon>
      <span>排课历史（共 {{ jobs.length }} 次任务<template v-if="failureCount">
        · 含 {{ failureCount }} 次失败或取消</template>）</span>
    </button>
    <div v-show="expanded" id="scheduling-history" data-testid="scheduling-history">
      <EmptyStateGuide
        v-if="!loading && jobs.length === 0"
        title="还没有排课历史"
        description="每次提交的排课任务都会记录在这里，包括状态、冲突情况与耗时。"
        help-slug="scheduling"
        help-anchor="run"
      />
      <el-table v-else v-loading="loading" :data="jobs" style="width: 100%">
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="草稿名称" min-width="160">
          <template #default="{ row }">{{ row.draftName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="170">
          <template #default="{ row }">
            <el-tag :type="statusTag(row).type" effect="light">{{ statusTag(row).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="冲突情况" min-width="160">
          <template #default="{ row }">
            <span :title="row.score || undefined">{{ conflictLabel(row) }}</span>
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
              @click="emit('open-draft', row.draftId)"
            >
              查看草稿
            </el-button>
            <el-button v-if="isRunning(row.status)" link type="danger" @click="emit('cancel', row.id)">
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import EmptyStateGuide from '@/components/EmptyStateGuide.vue'
import { getJobStatusTag, isRunning, jobConflictLabel, parseHardConflicts } from './jobStatus'
import type { JobItem } from './types'
import { formatDateTime } from '@/utils/datetime'

const props = defineProps<{ jobs: JobItem[]; loading: boolean; hasDrafts: boolean }>()
const emit = defineEmits<{ 'open-draft': [draftId: number]; cancel: [jobId: number] }>()
const expanded = ref(false)
const failureCount = computed(
  () => props.jobs.filter((job) => job.status === 'FAILED' || job.status === 'CANCELLED').length,
)

function latestJob(jobs: JobItem[]) {
  return [...jobs].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id)[0]
}

watch(
  () => [props.hasDrafts, props.jobs.length, latestJob(props.jobs)?.status],
  () => {
    const latest = latestJob(props.jobs)
    expanded.value = !props.hasDrafts && (latest?.status === 'FAILED' || latest?.status === 'CANCELLED')
  },
  { immediate: true },
)

function statusTag(job: JobItem) {
  return getJobStatusTag(job.status, parseHardConflicts(job.score) > 0 || job.resultPublishable === false)
}

function conflictLabel(job: JobItem) {
  return jobConflictLabel(job)
}
</script>

<style scoped>
.history-card {
  border: 1px solid #eef0f5;
}
.history-card :deep(.el-card__body) {
  padding: 0;
}
.history-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 16px 20px;
  border: 0;
  background: transparent;
  color: var(--paiban-ink);
  font: inherit;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}
#scheduling-history {
  padding: 0 20px 20px;
}
</style>
