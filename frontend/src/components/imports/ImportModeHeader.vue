<template>
  <div class="import-header">
    <div>
      <h2>数据导入</h2>
      <p class="subtitle">先把表格导入系统；需要 AI 时再切换智能解析。</p>
    </div>
    <el-radio-group :model-value="mode" data-testid="import-mode" @update:model-value="change">
      <el-radio-button value="manual">手动选列 / 配方</el-radio-button>
      <el-radio-button value="ai">AI 智能解析</el-radio-button>
    </el-radio-group>
  </div>
</template>

<script setup lang="ts">
defineProps<{ mode: 'manual' | 'ai' }>()
const emit = defineEmits<{ change: [mode: 'manual' | 'ai'] }>()
function change(value: string | number | boolean | undefined) {
  emit('change', value === 'ai' ? 'ai' : 'manual')
}
</script>

<style scoped>
.import-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
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
</style>
