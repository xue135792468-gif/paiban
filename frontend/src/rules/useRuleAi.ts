import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { ConstraintTypeDef, TypeDraft } from '@/components/constraints/types'
import type { ConstraintProposal } from '@/components/constraints/useConstraintAi'
import { cardsFromProposal, type ProposalDecision } from './proposalToCards'
import type { RuleCard } from './compiler'

/**
 * Ticket 03b: the rule page's AI state. The catalogue mapping and the audit trail
 * live here so the view stays a thin rendering shell (the SFC size ratchet).
 */
export function useRuleAi(options: {
  catalogue: Ref<ConstraintTypeDef[]>
  classIds: Ref<number[]>
  cards: Ref<RuleCard[]>
}) {
  const showAi = ref(false)
  const aiRunId = ref<number | null>(null)
  const aiDecisions = ref<ProposalDecision[]>([])
  const aiNotice = ref('')

  /** The panel only needs the catalogue shape; it never writes the draft. */
  const aiTypes = computed<TypeDraft[]>(() =>
    options.catalogue.value.map((type) => ({
      hardOnly: type.hardOnly,
      code: type.code,
      name: type.name,
      description: type.description,
      category: type.category,
      categoryLabel: type.categoryLabel,
      params: type.params,
      slotGrouped: type.slotGrouped,
      thresholdObjectField: type.thresholdObjectField,
      thresholdValueField: type.thresholdValueField,
      entries: [],
      enabled: true,
      hard: type.defaultHard,
      weight: type.defaultWeight,
    })),
  )

  function toggle() {
    showAi.value = !showAi.value
  }

  /** The selected suggestions become unsaved cards; nothing is written yet. */
  function onAiAdd(payload: { runId: number; proposals: ConstraintProposal[] }) {
    const result = cardsFromProposal(payload.proposals, { allClassIds: options.classIds.value })
    if (result.cards.length > 0) {
      options.cards.value.push(...result.cards)
      // The server accepts one run per save, so decisions of another run replace
      // this run's; adding to the same run extends it.
      if (aiRunId.value === payload.runId) {
        aiDecisions.value.push(...result.decisions)
      } else {
        aiRunId.value = payload.runId
        aiDecisions.value = [...result.decisions]
      }
    }
    const added = result.cards.length > 0
      ? `已加入 ${result.cards.length} 条规则（仍是未保存修改）`
      : ''
    const blocked = result.rejected
      .map((item) => `${item.typeName}：${item.reason}`)
      .join('；')
    aiNotice.value = [added, blocked ? `未加入：${blocked}` : ''].filter(Boolean).join('；')
      || '没有可加入的建议'
    if (result.cards.length === 0 || result.rejected.length > 0) {
      ElMessage.warning(aiNotice.value)
    } else {
      ElMessage.success(aiNotice.value)
    }
  }

  /** The audit fields of the save request; omitted unless this edit added suggestions. */
  function aiFields(): { runId: number; decisions: ProposalDecision[] } | null {
    if (aiRunId.value === null || aiDecisions.value.length === 0) {
      return null
    }
    return { runId: aiRunId.value, decisions: aiDecisions.value }
  }

  /** A save or a reload starts from the stored configuration again. */
  function resetAi() {
    aiRunId.value = null
    aiDecisions.value = []
    aiNotice.value = ''
  }

  return { showAi, aiTypes, aiNotice, toggle, onAiAdd, aiFields, resetAi }
}
