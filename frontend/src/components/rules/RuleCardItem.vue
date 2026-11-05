<template>
  <el-card
    shadow="never"
    class="rule-card"
    :class="{ 'rule-card--off': !card.enabled }"
    data-testid="rule-card"
  >
    <div class="card-head">
      <span class="target-tag" data-testid="rule-card-target">{{ targetLabel }}</span>
      <el-tag v-if="!card.enabled" size="small" type="info" data-testid="rule-disabled-tag">
        已停用
      </el-tag>
      <el-tooltip v-if="invalidReasons.length > 0" :content="invalidReasons.join('；')">
        <el-tag size="small" type="danger" effect="plain" data-testid="rule-invalid-tag">
          已失效
        </el-tag>
      </el-tooltip>
      <span class="spacer" />
      <el-button link type="primary" data-testid="rule-impact" @click="emit('impact', card)">
        影响
      </el-button>
      <el-button link type="primary" data-testid="rule-edit" @click="emit('edit', card)">
        编辑
      </el-button>
      <el-button
        v-if="!switchCard"
        link
        type="primary"
        data-testid="rule-duplicate"
        @click="emit('duplicate', card)"
      >
        复制
      </el-button>
      <el-button link type="primary" data-testid="rule-toggle" @click="emit('toggle', card)">
        {{ card.enabled ? '停用' : '启用' }}
      </el-button>
      <el-button link type="danger" data-testid="rule-delete" @click="emit('remove', card)">
        删除
      </el-button>
    </div>

    <div class="sentences">
      <div
        v-for="(sentence, index) in sentences"
        :key="index"
        class="sentence-line"
        :data-testid="`rule-sentence-line-${index}`"
      >
        <span
          class="sentence"
          :data-testid="index === 0 ? 'rule-sentence' : `rule-sentence-${index}`"
        >
          {{ sentence }}
        </span>
        <el-tag
          size="small"
          :type="strengthOf(index) === 'HARD' ? 'danger' : 'info'"
          :data-testid="index === 0 ? 'rule-strength-tag' : `rule-strength-tag-${index}`"
        >
          {{ strengthOf(index) === 'HARD' ? '必须' : '尽量' }}
        </el-tag>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { TARGET_LABELS, cardTarget, isSwitchRule, type RuleCard } from '@/rules/compiler'

const props = defineProps<{
  card: RuleCard
  sentences: string[]
}>()

const emit = defineEmits<{
  edit: [card: RuleCard]
  toggle: [card: RuleCard]
  duplicate: [card: RuleCard]
  remove: [card: RuleCard]
  impact: [card: RuleCard]
}>()

const switchCard = computed(() => props.card.sentences.some((sentence) => isSwitchRule(sentence.type)))

const targetLabel = computed(() => TARGET_LABELS[cardTarget(props.card)])

/**
 * Ticket 04: a rule whose teacher/class/course was paused or removed does not vanish
 * silently — the card says 已失效 and the tooltip names the reason from the server.
 */
const invalidReasons = computed(() => [
  ...new Set(props.card.sentences.flatMap((sentence) => sentence.invalidReferences ?? [])),
])

function strengthOf(index: number): 'HARD' | 'SOFT' {
  return props.card.sentences[index]?.strength ?? 'HARD'
}
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

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.target-tag {
  padding: 0 8px;
  border-radius: 999px;
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
  line-height: 22px;
}

.sentences {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sentence-line {
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
