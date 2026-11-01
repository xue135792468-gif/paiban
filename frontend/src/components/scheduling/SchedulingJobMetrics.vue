<template>
  <div class="metric-grid">
    <div class="metric-item">
      <span class="metric-label">当前求解得分</span>
      <span class="metric-value">{{ job.score || '暂无得分' }}</span>
    </div>
    <div class="metric-item">
      <span class="metric-label">已耗时</span>
      <span class="metric-value">{{ Math.round((job.elapsedMs || 0) / 1000) }} 秒</span>
    </div>
    <div class="metric-item">
      <span class="metric-label">预计剩余</span>
      <span class="metric-value">
        {{ job.status === 'RUNNING' ? Math.max(0, Math.round((job.estimatedRemainingMs || 0) / 1000)) + ' 秒' : '—' }}
      </span>
    </div>
    <div class="metric-item">
      <span class="metric-label">设定时长</span>
      <span class="metric-value">{{ job.solveSeconds }} 秒</span>
    </div>
    <div class="metric-item">
      <span class="metric-label">创建时间</span>
      <span class="metric-value">{{ formatDateTime(job.createdAt) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { formatDateTime } from '@/utils/datetime'

defineProps<{
  job: {
    status?: string
    score?: string | null
    elapsedMs?: number
    estimatedRemainingMs?: number
    solveSeconds: number
    createdAt: string
  }
}>()
</script>

<style scoped>
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
</style>
