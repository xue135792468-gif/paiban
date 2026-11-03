<template>
  <el-card
    shadow="never"
    class="rule-card"
    :class="{ 'rule-card--off': !card.enabled }"
    data-testid="rule-card"
  >
    <div class="rule-line">
      <span class="sentence" data-testid="rule-sentence">{{ sentence }}</span>
      <el-tag v-if="!card.enabled" size="small" type="info" data-testid="rule-disabled-tag">
        已停用
      </el-tag>
      <el-tag
        v-else size="small"
        :type="card.strength === 'HARD' ? 'danger' : 'info'"
        data-testid="rule-strength-tag"
      >
        {{ card.strength === 'HARD' ? '必须' : '尽量' }}
      </el-tag>
      <span class="spacer" />
      <el-button link type="primary" data-testid="rule-edit" @click="emit('edit', card)">
        编辑
      </el-button>
      <el-button link type="primary" data-testid="rule-toggle" @click="emit('toggle', card)">
        {{ card.enabled ? '停用' : '启用' }}
      </el-button>
      <el-button link type="danger" data-testid="rule-delete" @click="emit('remove', card)">
        删除
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import type { RuleCard } from '@/rules/compiler'

defineProps<{
  card: RuleCard
  sentence: string
}>()

const emit = defineEmits<{
  edit: [card: RuleCard]
  toggle: [card: RuleCard]
  remove: [card: RuleCard]
}>()
</script>

<style scoped>
.rule-card {
  border-radius: var(--paiban-radius, 8px);
}

.rule-card + .rule-card {
  margin-top: 10px;
}

.rule-card--off {
  background: var(--el-fill-color-lighter, #fafafa);
  opacity: 0.72;
}

.rule-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.sentence {
  font-size: 15px;
  color: var(--paiban-ink, #1f2937);
}

.spacer {
  flex: 1 1 auto;
}
</style>
