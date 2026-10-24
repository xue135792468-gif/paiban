<template>
  <el-card shadow="never" class="explain-card">
    <template #header><span class="card-title">AI 说明</span></template>
    <p class="explanation">{{ explanation || 'AI 未提供额外说明。' }}</p>
    <el-table :data="mappings" size="small" border>
      <el-table-column prop="source" label="原始列 / 片段" min-width="160" />
      <el-table-column prop="target" label="系统字段" width="120" />
      <el-table-column prop="note" label="映射说明" min-width="200" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
defineProps<{
  explanation?: string
  mappings: { source: string; target: string; note: string }[]
}>()
</script>

<style scoped>
.card-title {
  font-weight: 600;
}

.explanation {
  margin: 0 0 12px;
  color: #374151;
}
</style>
