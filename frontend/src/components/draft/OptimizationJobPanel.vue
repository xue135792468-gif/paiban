<template>
  <div v-if="job" class="job-panel" data-testid="optimization-job-panel">
    <div class="job-head">
      <div>
        <strong>继续优化任务 #{{ job.id }}</strong>
        <el-tag size="small" :type="statusType" effect="light" data-testid="optimization-status">
          {{ statusLabel }}
        </el-tag>
      </div>
      <el-button
        v-if="active"
        data-testid="optimization-cancel"
        :loading="cancelling"
        :disabled="cancelling"
        @click="emit('cancel')"
      >
        取消任务
      </el-button>
    </div>

    <p class="job-source" data-testid="optimization-source">
      来源草稿：{{ sourceName }}
      <template v-if="job.sourceDraftVersion != null">（版本 v{{ job.sourceDraftVersion }}）</template>
      <template v-if="job.inheritedLockCount != null"> · 继承锁定 {{ job.inheritedLockCount }} 节</template>
    </p>

    <template v-if="active">
      <el-progress :percentage="percentage" :stroke-width="10" />
      <p class="job-meta">
        已用 {{ formatMs(job.elapsedMs) }}
        <template v-if="remainingMs != null"> · 预计剩余 {{ formatMs(remainingMs) }}</template>
        <template v-if="job.progress != null"> · {{ percentage }}%</template>
      </p>
    </template>

    <template v-if="job.status === 'COMPLETED'">
      <el-alert
        v-if="resultPublishable === false"
        data-testid="optimization-result"
        type="warning"
        :closable="false"
        show-icon
        title="任务完成，但这节课表未达到发布标准"
      >
        <ul v-if="blockers.length > 0" class="blocker-list">
          <li
            v-for="(blocker, index) in blockers"
            :key="index"
            data-testid="optimization-blocker"
          >
            {{ blocker }}
          </li>
        </ul>
      </el-alert>
      <el-alert
        v-else-if="resultPublishable === true"
        data-testid="optimization-result"
        type="success"
        :closable="false"
        show-icon
        title="整稿校验通过"
      />
      <el-alert
        v-else
        data-testid="optimization-result"
        type="info"
        :closable="false"
        show-icon
        title="任务已完成"
      />
      <p class="job-hint">任务完成不等于可发布；发布仍以新草稿的整稿校验为准。</p>
      <div v-if="job.draftId" class="job-actions">
        <el-button
          type="primary"
          data-testid="optimization-open-draft"
          @click="emit('open-draft', job.draftId)"
        >
          打开新草稿
        </el-button>
        <el-button data-testid="optimization-compare" @click="emit('compare', job.draftId)">
          与原稿比较
        </el-button>
      </div>
      <el-alert
        v-else
        type="error"
        :closable="false"
        show-icon
        title="任务已完成，但没有生成新草稿"
      />
    </template>

    <template v-else-if="job.status === 'FAILED'">
      <el-alert
        data-testid="optimization-failed"
        type="error"
        :closable="false"
        show-icon
        :title="job.errorMessage ? `优化失败：${job.errorMessage}` : '优化失败'"
      />
      <p class="job-hint">原草稿保持不变，可以修改后重新发起。</p>
      <el-button data-testid="optimization-retry" @click="emit('retry')">重新发起</el-button>
    </template>

    <template v-else-if="job.status === 'CANCELLED'">
      <el-alert
        data-testid="optimization-cancelled"
        type="info"
        :closable="false"
        show-icon
        title="优化任务已取消"
      />
      <el-button data-testid="optimization-retry" @click="emit('retry')">重新发起</el-button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { OptimizationJob } from '@/api/scheduling'
import { isOptimizationActive } from '@/composables/useOptimizationJob'

const props = withDefaults(
  defineProps<{
    job: OptimizationJob | null
    sourceDraftId: number
    sourceDraftName?: string | null
    cancelling?: boolean
  }>(),
  {
    sourceDraftName: null,
    cancelling: false,
  },
)

const emit = defineEmits<{
  (e: 'cancel'): void
  (e: 'open-draft', draftId: number): void
  (e: 'compare', draftId: number): void
  (e: 'retry'): void
}>()

const STATUS_LABELS: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const statusLabel = computed(() => STATUS_LABELS[props.job?.status ?? ''] ?? props.job?.status ?? '')
const statusType = computed<TagType>(() => {
  switch (props.job?.status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    case 'RUNNING':
      return 'primary'
    default:
      return 'warning'
  }
})

const active = computed(() => isOptimizationActive(props.job))
const percentage = computed(() => Math.max(0, Math.min(100, Math.round(props.job?.progress ?? 0))))
const remainingMs = computed(() => {
  const value = props.job?.estimatedRemainingMs
  return value != null && value >= 0 ? value : null
})
const resultPublishable = computed(() => props.job?.resultPublishable ?? null)
const blockers = computed(() => props.job?.resultBlockers ?? [])
// The panel only knows the current editor's draft name; the job's own name is the new draft.
const sourceName = computed(
  () => props.sourceDraftName ?? (props.sourceDraftId ? `草稿 #${props.sourceDraftId}` : '当前草稿'),
)

function formatMs(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1000))
  const minutes = Math.floor(seconds / 60)
  return minutes > 0 ? `${minutes} 分 ${seconds % 60} 秒` : `${seconds} 秒`
}
</script>

<style scoped>
.job-panel {
  border: 1px solid #e4e7ed;
  border-radius: 10px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: #fafbfc;
}
.job-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.job-head strong {
  margin-right: 8px;
}
.job-source,
.job-meta,
.job-hint {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 12px;
}
.job-actions {
  display: flex;
  gap: 8px;
}
.blocker-list {
  margin: 6px 0 0;
  padding-left: 18px;
  line-height: 1.8;
}
</style>
