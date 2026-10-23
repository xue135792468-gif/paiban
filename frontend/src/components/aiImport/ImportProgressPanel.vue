<template>
  <el-card data-testid="import-progress" shadow="never" class="progress-card">
    <template #header>
      <div class="head">
        <span class="card-title">正在后台解析</span>
        <div class="head-side">
          <el-tag size="small" type="warning" effect="plain">解析中</el-tag>
          <el-button
            data-testid="stop-parsing"
            size="small"
            type="danger"
            plain
            :loading="stopping"
            @click="emit('stop')"
          >
            停止解析
          </el-button>
        </div>
      </div>
    </template>

    <el-progress
      :percentage="percentage"
      :stroke-width="14"
      :status="progress?.failed ? 'warning' : undefined"
    />

    <p class="line">
      <span data-testid="progress-count">已完成 {{ completed }}/{{ total }} 批</span>
      <span v-if="progress?.currentBatch" data-testid="progress-current" class="current">
        正在解析：{{ progress.currentBatch }}
      </span>
      <span v-else class="current">正在收尾…</span>
    </p>

    <p class="hint">
      解析在服务器后台进行，可以先去忙别的；页面会自动刷新，全部完成后直接进入预览。
      现在离开再回来，用「上次导入」可以接着看进度。停止解析会保留已完成的批次，剩下的可以之后重新解析。
    </p>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ImportProgress } from './timetablePreview'

const props = defineProps<{ progress?: ImportProgress | null; stopping?: boolean }>()
const emit = defineEmits<{ stop: [] }>()

const total = computed(() => props.progress?.total ?? 0)
const completed = computed(() => props.progress?.completed ?? 0)
const percentage = computed(() =>
  total.value <= 0 ? 0 : Math.min(100, Math.round((completed.value / total.value) * 100)),
)
</script>

<style scoped>
.progress-card {
  margin-bottom: 4px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-side {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-title {
  font-weight: 600;
}

.line {
  display: flex;
  gap: 16px;
  margin: 14px 0 0;
  color: #374151;
}

.current {
  color: #6b7280;
}

.hint {
  margin: 8px 0 0;
  color: #6b7280;
  font-size: 12px;
}
</style>
