<template>
  <div v-if="!available" class="ai-unavailable" data-testid="ai-unavailable-hint">
    AI 未配置，可手动选列。
  </div>
  <div v-else class="ai-block" data-testid="manual-ai">
    <div class="ai-head">
      <span class="ai-title">没有匹配的配方？</span>
      <el-button
        size="small"
        :loading="suggesting"
        data-testid="ai-suggest-button"
        @click="emit('suggest')"
      >
        让 AI 帮忙识别列
      </el-button>
    </div>
    <p class="hint">
      AI 只看表头和前 10 行，给出列映射建议；预览和入库仍由你确认，手动选列始终可用。
    </p>
    <div v-if="result?.suggestion" class="ai-result" data-testid="ai-suggestion">
      <div class="lines">
        <p class="line">
          <span class="label">表头行</span>
          <span>{{ headerText }}</span>
        </p>
        <p class="line">
          <span class="label">列映射</span>
          <span data-testid="ai-mapping-summary">{{ columnSummary }}</span>
        </p>
        <p v-if="ignoredText" class="line">
          <span class="label">忽略列</span>
          <span>{{ ignoredText }}</span>
        </p>
        <p v-if="countsText" class="line">
          <span class="label">预计结果</span>
          <span data-testid="ai-preview-summary">{{ countsText }}</span>
        </p>
      </div>
      <p v-if="result.suggestion.reasoning" class="reason" data-testid="ai-reasoning">
        {{ result.suggestion.reasoning }}
      </p>
      <div class="actions">
        <el-button
          type="primary"
          plain
          size="small"
          data-testid="adopt-ai-suggestion"
          @click="emit('adopt')"
        >
          采用建议
        </el-button>
        <span class="hint">采用后仍可修改每一列，再点“预览”。</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AiRecipeSuggestion, AiRecipeSuggestionResult } from '@/api/imports'

const props = defineProps<{
  available: boolean
  suggesting: boolean
  result: AiRecipeSuggestionResult | null
  headers: string[]
}>()
const emit = defineEmits<{ suggest: []; adopt: [] }>()

const suggestion = computed<AiRecipeSuggestion | undefined>(() => props.result?.suggestion)

function label(column: number | null | undefined): string {
  if (column === null || column === undefined) return '未指定'
  const header = props.headers[column]
  return header ? `「${header}」第 ${column + 1} 列` : `第 ${column + 1} 列`
}

const headerText = computed(() => {
  const row = suggestion.value?.headerRow
  if (row === null || row === undefined || row < 0) return '无表头（第一行就是数据）'
  return `第 ${row + 1} 行`
})

const columnSummary = computed(() => {
  const current = suggestion.value
  if (!current) return ''
  const parts: string[] = []
  if (current.compositeColumn !== null && current.compositeColumn !== undefined) {
    parts.push(`复合键 ${label(current.compositeColumn)}，分隔符「${current.delimiter || '|'}」`)
  } else {
    parts.push(`班级 ${label(current.classColumn)}`)
    parts.push(`星期 ${label(current.dayColumn)}`)
    parts.push(`节次 ${label(current.periodColumn)}`)
  }
  parts.push(`课程 ${label(current.courseColumn)}`)
  parts.push(`教师 ${label(current.teacherColumn)}`)
  return parts.join(' · ')
})

const ignoredText = computed(() =>
  (suggestion.value?.ignoreColumns ?? []).map((column) => label(column)).join('、'),
)

const countsText = computed(() => {
  const counts = props.result?.previewSummary
  if (!counts) return ''
  return `${counts.slotCount} 槽，${counts.lessonCount} 节有课，${counts.emptySlotCount} 个空槽，${counts.teacherCount} 位教师`
})
</script>

<style scoped>
.ai-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #e5e7ef;
}
.ai-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.ai-title {
  font-weight: 600;
  font-size: 14px;
}
.ai-unavailable {
  margin-top: 10px;
  font-size: 12px;
  color: var(--paiban-muted);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--paiban-muted);
}
.ai-result {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  padding: 12px;
  border: 1px solid #e2e7f5;
  border-radius: var(--paiban-radius);
  background: #f8f9ff;
}
.lines {
  display: grid;
  gap: 6px;
}
.line {
  margin: 0;
  font-size: 13px;
  color: var(--paiban-ink);
  line-height: 1.5;
}
.label {
  display: inline-block;
  min-width: 62px;
  color: var(--paiban-muted);
}
.reason {
  margin: 0;
  font-size: 13px;
  color: var(--paiban-muted);
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
