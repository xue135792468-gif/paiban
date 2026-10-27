<template>
  <div class="failure">
    <el-alert
      data-testid="failed-reason"
      type="error"
      show-icon
      :closable="false"
      :title="explanation || 'AI 解析失败，请调整文件后重新上传'"
    />
    <!-- A workbook can fail before there is any batch to retry; re-planning is the only
         useful action then, and it must be visible without a preview card. -->
    <div v-if="workbook" class="actions">
      <el-button type="primary" :loading="reparsing" @click="emit('replan')">
        重新规划
      </el-button>
      <span class="hint">会重新读取文件并让 AI 重新识别工作表结构。</span>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  explanation?: string | null
  /** Only a whole-workbook session has a planning step to run again. */
  workbook?: boolean
  reparsing?: boolean
}>()
const emit = defineEmits<{ replan: [] }>()
</script>

<style scoped>
.failure {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hint {
  color: #6b7280;
  font-size: 12px;
}
</style>
