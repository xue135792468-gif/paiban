<template>
  <div class="target-grid" role="radiogroup" aria-label="导入目标">
    <div
      v-for="target in targets"
      :key="target.code"
      class="target-card"
      :class="{ 'is-selected': target.code === selected, 'is-disabled': !target.available }"
      data-testid="target-card"
      :data-target="target.code"
      :aria-disabled="target.available ? undefined : 'true'"
      :role="target.available ? 'radio' : undefined"
      :aria-checked="target.available ? target.code === selected : undefined"
      :tabindex="target.available ? 0 : -1"
      @click="select(target)"
      @keydown.enter.prevent="select(target)"
      @keydown.space.prevent="select(target)"
    >
      <div class="target-head">
        <span class="target-title">{{ target.title }}</span>
        <el-tag v-if="!target.available" size="small" type="info" effect="plain">即将支持</el-tag>
        <el-tag
          v-else-if="target.code === selected"
          size="small"
          type="primary"
          effect="plain"
          data-testid="target-selected"
        >
          已选择
        </el-tag>
      </div>
      <dl class="target-details">
        <div>
          <dt>能处理</dt>
          <dd>{{ target.handles }}</dd>
        </div>
        <div>
          <dt>输出</dt>
          <dd>{{ target.output }}</dd>
        </div>
        <div>
          <dt>限制</dt>
          <dd>{{ target.limits }}</dd>
        </div>
      </dl>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ImportTargetCard } from './importTargets'

defineProps<{
  targets: ImportTargetCard[]
  selected: string
}>()

const emit = defineEmits<{ (event: 'select', code: string): void }>()

function select(target: ImportTargetCard) {
  if (!target.available) return
  // Re-clicking the current card still emits: picking a target explicitly is what keeps
  // an Excel upload on the single-target path instead of the whole-workbook one.
  emit('select', target.code)
}
</script>

<style scoped>
.target-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.target-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.target-card:hover {
  border-color: #93c5fd;
}

.target-card.is-selected {
  border-color: #3b82f6;
  box-shadow: 0 0 0 1px #3b82f6 inset;
}

.target-card.is-disabled {
  cursor: not-allowed;
  background: #f8fafc;
  color: #94a3b8;
}

.target-card.is-disabled:hover {
  border-color: #e2e8f0;
}

.target-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.target-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--paiban-ink, #1f2937);
}

.target-card.is-disabled .target-title {
  color: #94a3b8;
}

.target-details {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
}

.target-details div {
  display: grid;
  grid-template-columns: 48px 1fr;
  gap: 6px;
}

.target-details dt {
  font-size: 12px;
  color: #6b7280;
}

.target-details dd {
  margin: 0;
  font-size: 13px;
  line-height: 1.45;
  color: #374151;
}

.target-card.is-disabled .target-details dd {
  color: #94a3b8;
}
</style>
