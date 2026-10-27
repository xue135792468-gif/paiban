<template>
  <article
    class="proposal-card"
    :class="`status-${proposal.status.toLowerCase()}`"
    data-testid="constraint-proposal"
    :data-status="proposal.status"
  >
    <header class="proposal-head">
      <el-tag size="small" :type="statusType" data-testid="constraint-proposal-status">
        {{ statusLabel }}
      </el-tag>
      <span class="proposal-type">{{ proposal.typeName }}</span>
      <el-tag size="small" effect="plain" data-testid="constraint-proposal-strength">
        {{ strengthLabel }}
      </el-tag>
      <span class="proposal-source">“{{ proposal.sourceText }}”</span>
    </header>

    <p class="proposal-explanation">{{ proposal.explanation }}</p>
    <p v-if="proposal.status !== 'READY' && proposal.reason" class="proposal-reason" data-testid="constraint-proposal-reason">
      {{ proposal.reason }}
    </p>

    <div v-if="proposal.candidates.length > 0" class="proposal-candidates" data-testid="constraint-proposal-candidates">
      <span v-for="candidate in proposal.candidates" :key="candidate.field" class="candidate-row">
        {{ candidate.label }}：
        <span class="candidate-option">{{ candidate.options.map((option) => option.name).join(' / ') }}</span>
      </span>
      <span class="candidate-hint">（请在校对步骤中选择，系统不会猜测）</span>
    </div>

    <ul v-if="proposal.entries.length > 0" class="proposal-entries" data-testid="constraint-proposal-entries">
      <li v-for="(entry, index) in proposal.entries" :key="index">
        {{ describe(entry) }}
      </li>
    </ul>

    <p v-if="proposal.effectiveWeight !== null" class="proposal-weight">
      重要程度 {{ proposal.effectiveWeight }}（沿用类型默认，未编造数值）
    </p>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElTag } from 'element-plus'
import { formatEntrySummary } from './entrySummary'
import type { ConstraintProposal, ProposalEntry } from './useConstraintAi'
import type { ParamDef, TypeDraft } from './types'

const props = defineProps<{
  proposal: ConstraintProposal
  types: TypeDraft[]
  teacherNames: Map<number, string>
  courseNames: Map<number, string>
}>()

const statusLabel = computed(() => {
  switch (props.proposal.status) {
    case 'READY':
      return '可加入'
    case 'NEEDS_INPUT':
      return '待补齐'
    case 'UNSUPPORTED':
      return '暂不支持'
    default:
      return '非法'
  }
})

const statusType = computed(() => {
  switch (props.proposal.status) {
    case 'READY':
      return 'success'
    case 'NEEDS_INPUT':
      return 'warning'
    case 'UNSUPPORTED':
      return 'info'
    default:
      return 'danger'
  }
})

const strengthLabel = computed(() => {
  if (props.proposal.strength === 'HARD') {
    return '必须'
  }
  if (props.proposal.strength === 'SOFT') {
    return '尽量'
  }
  return `继承类型默认（${props.proposal.effectiveHard ? '必须' : '尽量'}）`
})

const params = computed<ParamDef[]>(
  () => props.types.find((row) => row.code === props.proposal.type)?.params ?? [],
)

const ctx = computed(() => ({
  teacherNames: props.teacherNames,
  courseNames: props.courseNames,
}))

function describe(entry: ProposalEntry): string {
  if (params.value.length === 0) {
    return '全局开关要求（没有对象参数）'
  }
  return formatEntrySummary(params.value, entry.params, ctx.value)
}
</script>

<style scoped>
.proposal-card {
  padding: 12px 14px;
  border-radius: 8px;
  background: #fbfcfe;
  border: 1px solid #eef1f6;
}

.proposal-card.status-needs_input {
  border-color: #f3d19e;
  background: #fdf8ef;
}

.proposal-card.status-invalid {
  border-color: #f3b5b5;
  background: #fef6f6;
}

.proposal-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.proposal-type {
  font-weight: 600;
  color: var(--paiban-ink);
}

.proposal-source {
  font-size: 12px;
  color: var(--paiban-muted);
}

.proposal-explanation {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
}

.proposal-reason {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-color-warning-dark-2, #b88230);
  line-height: 1.6;
}

.proposal-candidates {
  margin-top: 8px;
  font-size: 12px;
  color: var(--paiban-muted);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.candidate-option {
  color: var(--paiban-ink);
  font-weight: 600;
}

.candidate-hint {
  font-size: 11px;
}

.proposal-entries {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
}

.proposal-weight {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--paiban-muted);
}
</style>
