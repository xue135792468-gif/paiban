<template>
  <div data-testid="partial-result-panel" class="partial">
    <div class="summary">
      <span data-testid="partial-summary" class="summary-title">
        部分结果{{ progress ? `：已解析 ${completed}/${total} 批` : '' }}
      </span>
      <el-tag size="small" type="warning" effect="plain">部分结果</el-tag>
    </div>

    <el-alert
      data-testid="partial-hint"
      type="warning"
      show-icon
      :closable="false"
      title="确认入库已暂停：这次导入还缺部分批次的数据。请先重试失败批次或放弃导入。"
    />

    <div class="retry-actions">
      <el-button
        type="primary"
        data-testid="partial-retry"
        :loading="retrying"
        :disabled="!failedBatches.length"
        @click="emit('retry')"
      >
        重试失败批次
      </el-button>
      <span data-testid="partial-retry-hint" class="muted">
        只会重新解析失败的 {{ failedBatches.length }} 个批次，已经解析好的不会重复调用 AI
      </span>
    </div>

    <section data-testid="partial-success" class="section">
      <div class="section-head">
        <span class="section-title">已解析 {{ rows.length }} 节课</span>
        <span class="muted">来自 {{ batchCount }} 个成功批次</span>
        <el-button
          v-if="rows.length"
          link
          type="primary"
          data-testid="partial-toggle-rows"
          @click="showRows = !showRows"
        >
          {{ showRows ? '收起已解析的课' : '查看已解析的课' }}
        </el-button>
      </div>
      <el-table
        v-if="showRows"
        data-testid="partial-success-table"
        :data="rows"
        size="small"
        border
        max-height="360"
      >
        <el-table-column type="index" label="#" width="48" />
        <el-table-column
          v-for="column in columns"
          :key="column.field"
          :label="column.label"
          :min-width="110"
        >
          <template #default="{ row }">{{ row.values[column.field] || '—' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <section data-testid="partial-failed" class="section">
      <div class="section-head">
        <span class="section-title">失败批次：{{ failedBatches.length }} 批</span>
        <span class="muted">这些批次的数据没有解析出来，重试或放弃后才能继续</span>
      </div>
      <ul class="failed-list">
        <li v-for="batch in failedBatches" :key="batch.index" class="failed-item">
          <!-- `reason` already reads 「第 2/4 批（一年级2班）解析失败：…」; a second batch
               number in front of it would look like a different batch. -->
          <span class="failed-label">{{ batch.label || '未知班级' }}</span>
          <span class="failed-reason">{{ batch.reason }}</span>
        </li>
      </ul>
    </section>

    <div class="actions">
      <el-button
        data-testid="abandon-import"
        type="danger"
        plain
        :loading="abandoning"
        @click="emit('abandon')"
      >
        放弃导入
      </el-button>
      <span class="muted">已解析的内容不会入库，放弃后不写入任何数据</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { fieldLabel, type FailedBatch, type ImportProgress, type PreviewRow } from './timetablePreview'

const props = defineProps<{
  rows: PreviewRow[]
  failedBatches: FailedBatch[]
  progress?: ImportProgress | null
  /** True while the retry request is in flight, so the button cannot fire twice. */
  retrying?: boolean
  abandoning?: boolean
}>()

const emit = defineEmits<{ retry: []; abandon: [] }>()

/** A timetable batch is one class, so finished batches count the classes on screen. */
const batchCount = computed(() => props.progress?.completed ?? props.rows.length)
const completed = computed(() => props.progress?.completed ?? 0)
const total = computed(() => props.progress?.total ?? batchCount.value + props.failedBatches.length)

const showRows = ref(false)

const FIELD_ORDER = [
  'className',
  'dayOfWeek',
  'periodNo',
  'courseName',
  'teacherName',
  'roomName',
]

const columns = computed(() => {
  const present = new Set<string>()
  for (const row of props.rows) {
    Object.keys(row.values ?? {}).forEach((field) => present.add(field))
  }
  const ordered = FIELD_ORDER.filter((field) => present.has(field))
  const rest = [...present].filter((field) => !FIELD_ORDER.includes(field))
  return [...ordered, ...rest].map((field) => ({ field, label: fieldLabel(field) }))
})
</script>

<style scoped>
.partial {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary {
  display: flex;
  align-items: center;
  gap: 10px;
}

.summary-title {
  font-weight: 600;
  color: #374151;
}

.retry-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e5e7eb;
}

.section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.section-title {
  font-weight: 600;
}

.muted {
  color: #6b7280;
  font-size: 12px;
}

.failed-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.failed-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}

.failed-label {
  font-weight: 600;
}

.failed-reason {
  color: #b91c1c;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 10px;
  border-top: 1px dashed #e5e7eb;
}
</style>
