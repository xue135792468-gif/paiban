<template>
  <section v-if="cards.length > 0" class="unusable" data-testid="rule-unusable-panel">
    <h3 class="title">本学期用不上（{{ cards.length }}）</h3>
    <p class="hint">
      这些规则已停用：引用的老师/课程/班级/时间组或节次在本学期找不到。请修改或删除。
    </p>
    <article
      v-for="card in cards"
      :key="card.key"
      class="card"
      data-testid="rule-unusable-card"
    >
      <p class="sentences">{{ sentencesOf(card) }}</p>
      <ul v-if="reasonsOf(card).length > 0" class="reasons">
        <li v-for="reason in reasonsOf(card)" :key="reason">{{ reason }}</li>
      </ul>
      <div class="actions">
        <el-button
          link
          type="primary"
          :data-testid="`rule-unusable-edit-${card.cardId}`"
          @click="emit('edit', card)"
        >
          去修改
        </el-button>
        <el-button
          link
          type="danger"
          :data-testid="`rule-unusable-remove-${card.cardId}`"
          @click="emit('remove', card)"
        >
          删除
        </el-button>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import {
  describeSentence,
  missingParts,
  type PeriodOption,
  type RuleCard,
  type RuleNames,
} from '@/rules/compiler'

/**
 * Ticket 08: copied rules whose teacher/course/class/time group/slot is gone are
 * paused, never silently dropped. They sit apart from the normal list so the user
 * can repair or delete each card without mistaking it for an active rule.
 */
const props = defineProps<{
  cards: RuleCard[]
  names: RuleNames
  periods?: PeriodOption[]
}>()

const emit = defineEmits<{
  edit: [card: RuleCard]
  remove: [card: RuleCard]
}>()

function sentencesOf(card: RuleCard): string {
  return card.sentences
    .map((sentence) => describeSentence(sentence, props.names, props.periods))
    .join('；')
}

function reasonsOf(card: RuleCard): string[] {
  const reasons = card.sentences.flatMap((sentence) => {
    const invalid = sentence.invalidReferences ?? []
    if (invalid.length > 0) {
      return invalid
    }
    const missing = missingParts(sentence)
    return missing.length > 0 ? [`还差：${missing.join('、')}`] : []
  })
  return [...new Set(reasons)]
}
</script>

<style scoped>
.unusable {
  margin-bottom: 12px;
  padding: 12px 14px;
  border: 1px solid var(--el-color-warning-light-5, #f3d19e);
  border-radius: var(--paiban-radius, 8px);
  background: var(--el-color-warning-light-9, #fdf6ec);
}

.title {
  margin: 0 0 4px;
  color: var(--el-color-warning-dark-2, #b88230);
  font-size: 14px;
}

.hint {
  margin: 0 0 8px;
  color: var(--paiban-muted, #6b7280);
  font-size: 12px;
  line-height: 1.6;
}

.card {
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-color-warning-light-9, #fdf6ec);
}

.card + .card {
  margin-top: 8px;
}

.sentences {
  margin: 0 0 4px;
  color: var(--paiban-ink, #1f2937);
  font-size: 14px;
  line-height: 1.6;
}

.reasons {
  margin: 0 0 4px;
  padding-left: 18px;
  color: var(--el-color-danger, #f56c6c);
  font-size: 12px;
  line-height: 1.7;
}

.actions {
  display: flex;
  gap: 8px;
}
</style>
