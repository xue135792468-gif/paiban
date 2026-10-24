<template>
  <el-card shadow="never" data-testid="workbook-summary">
    <template #header>
      <div class="card-head">
        <span class="card-title">工作簿识别结果</span>
        <el-tag v-if="!workbook.structureConfirmed" size="small" type="warning" effect="plain">
          结构未确认
        </el-tag>
        <el-tag v-else size="small" type="success" effect="plain">结构已确认</el-tag>
      </div>
    </template>

    <el-alert
      v-for="warning in warnings"
      :key="warning"
      data-testid="workbook-warning"
      type="warning"
      show-icon
      :closable="false"
      class="warning"
      :title="warning"
    />

    <el-table :data="workbook.sheets" size="small" border data-testid="workbook-sheets">
      <el-table-column prop="sheet" label="工作表" min-width="140" />
      <el-table-column label="数据类别" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ categoryLabel(row.category) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="列映射" min-width="260">
        <template #default="{ row }">{{ columnSummary(row.columns) }}</template>
      </el-table-column>
      <el-table-column prop="recognizedRows" label="识别行数" width="96" />
      <el-table-column prop="newRows" label="去重后新增" width="106" />
      <el-table-column label="跳过原因" min-width="180">
        <template #default="{ row }">{{ row.skippedReason || '—' }}</template>
      </el-table-column>
    </el-table>

    <el-collapse v-if="toolTrace.length" class="trace">
      <el-collapse-item :title="`模型调用轨迹（${toolTrace.length} 轮）`" name="trace">
        <el-table :data="toolTrace" size="small" border data-testid="workbook-tool-trace">
          <el-table-column prop="round" label="#" width="52" />
          <el-table-column prop="tool" label="工具" width="120" />
          <el-table-column prop="args" label="参数" min-width="180" />
          <el-table-column prop="result" label="结果摘要" min-width="260" />
        </el-table>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  categoryLabel,
  type ToolTrace,
  type WorkbookSummary,
} from './timetablePreview'

const props = defineProps<{
  workbook: WorkbookSummary
  warnings?: string[]
  toolTrace?: ToolTrace[]
  columns: { field: string; label: string }[]
}>()

const warnings = computed(() => props.warnings ?? [])
const toolTrace = computed(() => props.toolTrace ?? [])

function columnSummary(columns: Record<string, string>): string {
  const entries = Object.entries(columns ?? {})
  if (!entries.length) return '—'
  return entries.map(([field, label]) => `${labelOf(field)}←${label}`).join('、')
}

function labelOf(field: string): string {
  return props.columns.find((column) => column.field === field)?.label ?? field
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-title {
  font-weight: 600;
}

.warning {
  margin-bottom: 8px;
}

.trace {
  margin-top: 12px;
}
</style>
