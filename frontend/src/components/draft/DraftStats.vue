<template>
  <div class="summary" :class="{ 'has-conflicts': hardConflicts > 0 }" data-testid="draft-status-summary">
    <el-tag :type="status.tone" effect="light">{{ status.label }}</el-tag>
    <span>已排 {{ lessonCount - unassignedCount }} / {{ lessonCount }} 节</span>
    <span>未排 {{ unassignedCount }} 节</span>
    <span>已锁定 {{ lockedCount }} 节</span>
    <el-popover placement="bottom-start" :width="340" trigger="click">
      <template #reference>
        <el-button link type="primary" data-testid="draft-score-info">排课详情 ⓘ</el-button>
      </template>
      参考求解得分：<code>{{ scoreLabel }}</code>
      <p class="hint">手工微调后得分可能过期，发布以实时全校校验为准。</p>
    </el-popover>
    <span
      v-if="conflictTotal > 0"
      data-testid="publish-blocker"
      data-code="HARD_CONFLICTS"
    >
      <el-button
        link
        type="primary"
        data-testid="blocker-action-HARD_CONFLICTS"
        @click="emit('locate-conflict')"
      >
        逐条定位{{ activeConflictIndex >= 0 ? `（${activeConflictIndex + 1}/${conflictTotal}）` : ' →' }}
      </el-button>
    </span>
    <DraftPublishBlockers :blockers="nonConflictBlockers" @edit-lessons="emit('edit-lessons')" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import DraftPublishBlockers from './DraftPublishBlockers.vue'
import type { DraftPublishBlocker } from './types'

const props = withDefaults(
  defineProps<{
    hardConflicts: number
    scoreLabel: string
    lessonCount: number
    unassignedCount: number
    lockedCount: number
    conflictTotal?: number
    activeConflictIndex?: number
    blockers?: DraftPublishBlocker[]
  }>(),
  { conflictTotal: 0, activeConflictIndex: -1, blockers: () => [] },
)

const emit = defineEmits<{ 'locate-conflict': []; 'edit-lessons': [] }>()
const nonConflictBlockers = computed(() =>
  props.blockers.filter((blocker) => blocker.code !== 'HARD_CONFLICTS'),
)
const status = computed(() => {
  if (props.hardConflicts > 0) {
    return { tone: 'danger' as const, label: `${props.hardConflicts} 处排课冲突` }
  }
  if (props.blockers.length > 0) {
    return { tone: 'warning' as const, label: `无冲突 · 还需处理 ${props.blockers.length} 项` }
  }
  return { tone: 'success' as const, label: '无冲突 · 可发布' }
})
</script>

<style scoped>
.summary {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 9px 12px;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  background: #f0fdf4;
  font-size: 13px;
}
.summary.has-conflicts {
  border-color: #fecaca;
  background: #fff7f7;
}
.summary :deep(.blockers) {
  flex-basis: 100%;
}
.summary :deep(.actions) {
  display: inline;
  margin: 0 0 0 8px;
}
.hint {
  margin-bottom: 0;
  color: var(--paiban-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
