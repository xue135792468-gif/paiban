<template>
  <el-card data-testid="last-session-card" shadow="never">
    <template #header><span class="card-title">上次导入</span></template>
    <div class="last-session">
      <div class="last-session-line">
        <span class="last-session-name">{{ session.fileName }}</span>
        <el-tag size="small" :type="statusTagType(session.status)" effect="plain">
          {{ statusText(session.status) }}
        </el-tag>
      </div>
      <div class="hint">{{ savedAtLabel }}</div>
    </div>
    <el-button
      type="primary"
      link
      data-testid="continue-last-session"
      :loading="loading"
      @click="emit('continue')"
    >
      继续
    </el-button>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { LastSession } from './useImportSession'
import { statusTagType, statusText } from './importStatus'

const props = defineProps<{ session: LastSession; loading: boolean }>()

const emit = defineEmits<{ (e: 'continue'): void }>()

const savedAtLabel = computed(() => formatLocalTime(props.session.savedAt))

function formatLocalTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (number: number) => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<style scoped>
.card-title {
  font-weight: 600;
}

.last-session {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.last-session-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.last-session-name {
  font-weight: 600;
}

.hint {
  color: #6b7280;
  font-size: 12px;
}
</style>
