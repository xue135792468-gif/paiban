<template>
  <div class="groups" data-testid="rule-cards">
    <el-empty
      v-if="groups.length === 0"
      description="没有匹配的规则"
      data-testid="rule-search-empty"
    />
    <template v-else>
      <section
        v-for="group in groups"
        :key="group.key"
        class="group"
        :data-testid="`rule-group-${group.key}`"
      >
        <h4 class="group-title" :data-testid="`rule-group-title-${group.key}`">
          {{ group.label }}
          <span class="group-count">{{ group.cards.length }}</span>
        </h4>
        <RuleCardItem
          v-for="card in group.cards"
          :key="card.key"
          :card="card"
          :sentences="sentencesOf(card)"
          @edit="emit('edit', $event)"
          @toggle="emit('toggle', $event)"
          @duplicate="emit('duplicate', $event)"
          @remove="emit('remove', $event)"
          @impact="emit('impact', $event)"
        />
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import RuleCardItem from '@/components/rules/RuleCardItem.vue'
import {
  TARGET_LABELS,
  cardMatches,
  cardTarget,
  describeSentence,
  type RuleCard,
  type RuleNames,
  type RuleTargetGroup,
} from '@/rules/compiler'

const TARGET_ORDER: RuleTargetGroup[] = ['SCHOOL', 'CLASS', 'TEACHER', 'COURSE']

const props = defineProps<{
  cards: RuleCard[]
  query: string
  names: RuleNames
}>()

const emit = defineEmits<{
  edit: [card: RuleCard]
  toggle: [card: RuleCard]
  duplicate: [card: RuleCard]
  remove: [card: RuleCard]
  impact: [card: RuleCard]
}>()

const groups = computed(() => {
  const buckets = new Map<RuleTargetGroup, RuleCard[]>()
  TARGET_ORDER.forEach((key) => buckets.set(key, []))
  for (const card of props.cards) {
    if (!cardMatches(card, props.query, props.names)) {
      continue
    }
    buckets.get(cardTarget(card))?.push(card)
  }
  return TARGET_ORDER
    .map((key) => ({ key, label: TARGET_LABELS[key], cards: buckets.get(key) ?? [] }))
    .filter((group) => group.cards.length > 0)
})

function sentencesOf(card: RuleCard): string[] {
  return card.sentences.map((sentence) =>
    describeSentence(sentence, props.names))
}
</script>

<style scoped>
.groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.group-title {
  margin: 0 0 6px;
  font-size: 14px;
}

.group-count {
  margin-left: 4px;
  color: var(--paiban-muted);
  font-weight: 400;
}
</style>
