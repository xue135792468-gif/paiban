<template>
  <el-table data-testid="preview-rows-table" :data="rows" border size="small" max-height="520">
    <el-table-column type="index" label="#" width="52" />
    <el-table-column
      v-for="column in columns"
      :key="column.field"
      :label="column.label + (column.required ? ' *' : '')"
      :min-width="column.field === 'name' ? 150 : 130"
    >
      <template #default="{ row }">
        <el-input v-if="editing && !committed" v-model="row.values[column.field]" size="small" />
        <span
          v-else
          class="cell"
          :class="{ 'low-confidence': row.lowConfidence?.includes(column.field) }"
          :title="confidenceTitle(row, column.field)"
        >
          {{ row.values[column.field] || '—' }}
        </span>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import type { PreviewRow } from './timetablePreview'

interface PreviewColumn {
  field: string
  label: string
  required?: boolean
}

defineProps<{
  rows: PreviewRow[]
  columns: PreviewColumn[]
  editing: boolean
  committed: boolean
}>()

function confidenceTitle(row: PreviewRow, field: string) {
  const value = row.confidence?.[field]
  return value === undefined ? undefined : `AI 置信度 ${Math.round(value * 100)}%`
}
</script>

<style scoped>
.cell {
  display: inline-block;
  padding: 2px 6px;
  border-radius: 3px;
}
.low-confidence {
  background: #fff7cc;
}
</style>
