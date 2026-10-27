<template>
  <el-card shadow="never" class="ai-card" data-testid="constraint-ai-panel">
    <template #header>
      <div class="ai-head">
        <span class="card-title">用自然语言帮填表</span>
        <el-tag v-if="!availability.available" size="small" type="info">暂不可用</el-tag>
        <span class="ai-readonly">只读建议：生成和查看都不会改动已保存的约束配置</span>
      </div>
    </template>

    <el-alert
      v-if="!availability.available"
      type="info"
      :closable="false"
      show-icon
      data-testid="constraint-ai-unavailable"
    >
      <template #title>当前不能调用模型</template>
      <p class="ai-copy">{{ availability.message }}</p>
    </el-alert>

    <template v-else>
      <el-input
        v-model="text"
        type="textarea"
        :rows="3"
        maxlength="2000"
        show-word-limit
        placeholder="例如：王老师周三下午不排课；体育课不排上午第1、2节；所有老师每天最多上4节"
        data-testid="constraint-ai-input"
      />
      <div class="ai-actions">
        <el-button
          type="primary"
          :loading="generating"
          :disabled="!text.trim()"
          data-testid="constraint-ai-generate"
          @click="onGenerate"
        >
          生成建议
        </el-button>
        <el-button v-if="generating" data-testid="constraint-ai-cancel" @click="cancel">
          取消等待
        </el-button>
        <span v-if="generating" class="ai-wait" data-testid="constraint-ai-wait">
          最多等待 60 秒（已等待 {{ elapsed }} 秒）；失败不会自动重试
        </span>
      </div>
    </template>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      class="ai-message"
      data-testid="constraint-ai-error"
    >
      <template #title>{{ error }}</template>
    </el-alert>

    <template v-if="run">
      <el-alert
        v-if="run.status === 'FAILED'"
        type="error"
        :closable="false"
        show-icon
        class="ai-message"
        data-testid="constraint-ai-run-failed"
      >
        <template #title>生成失败，原话已保留</template>
        <p class="ai-copy">{{ run.errorMessage || '供应商暂时不可用' }}；请修改后主动重试。</p>
      </el-alert>
      <el-alert
        v-else-if="run.status === 'TIMEOUT'"
        type="warning"
        :closable="false"
        show-icon
        class="ai-message"
        data-testid="constraint-ai-run-timeout"
      >
        <template #title>生成超时（最多等待 60 秒）</template>
        <p class="ai-copy">原话已保留，重试由你发起；本次调用仍会记录。</p>
      </el-alert>
      <el-alert
        v-else-if="run.status === 'CANCELLED'"
        type="info"
        :closable="false"
        show-icon
        class="ai-message"
        data-testid="constraint-ai-run-cancelled"
      >
        <template #title>已取消等待</template>
        <p class="ai-copy">已发出的调用仍可能计费；晚到的结果只记录，不会填回页面。</p>
      </el-alert>
      <el-alert
        v-else-if="run.status === 'RUNNING'"
        type="info"
        :closable="false"
        show-icon
        class="ai-message"
        data-testid="constraint-ai-run-running"
      >
        <template #title>这条记录仍在生成中</template>
        <p class="ai-copy">请稍后重新打开记录查看结果。</p>
      </el-alert>

      <div v-if="run.status === 'SUCCEEDED'" class="ai-run" data-testid="constraint-ai-run">
        <p class="ai-counts" data-testid="constraint-ai-counts">
          共 {{ counts.total }} 条独立建议：{{ counts.ready }} 条可加入、{{ counts.needsInput }}
          条待补齐、{{ counts.unsupported }} 条暂不支持或非法。
        </p>
        <p v-if="counts.total === 0" class="ai-empty" data-testid="constraint-ai-empty">
          模型没有返回可识别的建议。原话仍保留在上方，可换一种说法重试。
        </p>
        <ConstraintProposalCard
          v-for="proposal in proposals"
          :key="proposal.proposalId"
          :proposal="proposal"
          :types="types"
          :teacher-names="teacherNames"
          :course-names="courseNames"
          :selected="selectedIds.includes(proposal.proposalId)"
          :editable="canManage"
          @select="(value: boolean) => toggleSelected(proposal.proposalId, value)"
          @resolve="(overrides: Record<string, unknown>) => onResolve(proposal.proposalId, overrides)"
        />
        <div class="ai-add">
          <el-button
            type="primary"
            :disabled="!canManage || selectedProposals.length === 0"
            data-testid="constraint-ai-add"
            @click="onAdd"
          >
            添加到约束表（已选 {{ selectedProposals.length }} 条）
          </el-button>
          <span class="ai-note-inline">
            加入后仍是未保存修改；统一「保存配置」后才生效。
          </span>
        </div>
        <p class="ai-note" data-testid="constraint-ai-note">
          建议由模型生成，需要你校对后才会加入；添加和保存都不会调用模型。
        </p>
      </div>
    </template>

    <el-collapse class="ai-history" data-testid="constraint-ai-history">
      <el-collapse-item title="生成记录" name="history">
        <div class="history-tools">
          <el-checkbox
            v-if="canReadAll"
            :model-value="scopeAll"
            data-testid="constraint-ai-history-all"
            @update:model-value="(value: string | number | boolean) => setScopeAll(Boolean(value))"
          >
            查看全局记录（管理员）
          </el-checkbox>
          <el-button link type="primary" data-testid="constraint-ai-history-refresh" @click="loadHistory">
            刷新
          </el-button>
        </div>
        <p v-if="history.length === 0" class="ai-empty" data-testid="constraint-ai-history-empty">
          还没有生成记录。
        </p>
        <ul v-else class="history-list">
          <li v-for="item in history" :key="item.id">
            <el-button link type="primary" data-testid="constraint-ai-history-item" @click="openRun(item.id)">
              {{ formatTime(item.startedAt) }} ·
              {{ statusText(item.status) }} · {{ item.proposalCount }} 条 ·
              {{ item.inputText.slice(0, 24) }}{{ item.inputText.length > 24 ? '…' : '' }}
            </el-button>
          </li>
        </ul>
        <div v-if="audits.length > 0" class="audit-list" data-testid="constraint-ai-audits">
          <p class="audit-title">已保存的确认记录</p>
          <p
            v-for="audit in audits.slice(0, 5)"
            :key="audit.id"
            class="ai-copy"
            data-testid="constraint-ai-audit-item"
          >
            {{ formatTime(audit.createdAt) }} · 版本 {{ audit.version }} ·
            {{ audit.actor || '—' }} · 确认 {{ audit.decisions.length }} 项
          </p>
        </div>
        <div v-if="detail" class="history-detail" data-testid="constraint-ai-history-detail">
          <p class="ai-copy">
            原话：{{ detail.run.inputText }}
          </p>
          <p v-if="detail.run.errorMessage" class="ai-copy">
            失败原因：{{ detail.run.errorMessage }}
          </p>
          <details v-if="detail.rawResponse || detail.lateResponse">
            <summary>查看完整原始输出</summary>
            <pre class="raw" data-testid="constraint-ai-raw">{{ detail.lateResponse || detail.rawResponse }}</pre>
          </details>
        </div>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElAlert, ElButton, ElCard, ElCheckbox, ElCollapse, ElCollapseItem, ElInput, ElTag } from 'element-plus'
import ConstraintProposalCard from './ConstraintProposalCard.vue'
import { useConstraintAi, type ConstraintProposal } from './useConstraintAi'
import type { OptionItem, TypeDraft } from './types'

/**
 * The read-only generation surface of the constraint page (ticket 14/08). It is
 * always rendered for a constraint manager — when AI is off or unconfigured it says
 * why instead of hiding the capability — and it never writes the effective config.
 */
const props = defineProps<{
  semesterId: number | null
  types: TypeDraft[]
  teachers: OptionItem[]
  courses: OptionItem[]
  canReadAll: boolean
  canManage: boolean
}>()

const {
  availability,
  text,
  generating,
  run,
  proposals,
  selectedProposals,
  selectedIds,
  counts,
  error,
  history,
  detail,
  scopeAll,
  audits,
  checkAvailability,
  generate,
  cancel,
  loadHistory,
  loadAudits,
  openRun,
  setScopeAll,
  toggleSelected,
  clearSelection,
  resolveProposal,
} = useConstraintAi({
  semesterId: computed(() => props.semesterId),
  canReadAll: () => props.canReadAll,
})

const emit = defineEmits<{
  add: [payload: { runId: number; proposals: ConstraintProposal[] }]
}>()

async function onResolve(proposalId: string, overrides: Record<string, unknown>) {
  await resolveProposal(proposalId, overrides)
}

function onAdd() {
  const current = run.value
  if (!current || selectedProposals.value.length === 0) {
    return
  }
  emit('add', { runId: current.id, proposals: selectedProposals.value })
}

defineExpose({ loadAudits, clearSelection })

const elapsed = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function startTimer() {
  elapsed.value = 0
  stopTimer()
  timer = setInterval(() => {
    elapsed.value += 1
  }, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function onGenerate() {
  startTimer()
  try {
    await generate()
  } finally {
    stopTimer()
  }
}

const teacherNames = computed(
  () => new Map(props.teachers.map((teacher) => [teacher.id, teacher.name])),
)
const courseNames = computed(
  () => new Map(props.courses.map((course) => [course.id, course.name])),
)

function statusText(status: string): string {
  switch (status) {
    case 'SUCCEEDED':
      return '已完成'
    case 'FAILED':
      return '失败'
    case 'TIMEOUT':
      return '超时'
    case 'CANCELLED':
      return '已取消'
    default:
      return '生成中'
  }
}

function formatTime(value: string): string {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

watch(
  () => props.semesterId,
  () => {
    checkAvailability()
    loadHistory()
    loadAudits()
  },
  { immediate: true },
)

onBeforeUnmount(stopTimer)
</script>

<style scoped>
.ai-card {
  margin-bottom: 14px;
}

.ai-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.card-title {
  font-weight: 600;
}

.ai-readonly {
  font-size: 12px;
  color: var(--paiban-muted);
}

.ai-copy {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ai-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.ai-wait {
  font-size: 12px;
  color: var(--paiban-muted);
}

.ai-message {
  margin-top: 10px;
}

.ai-run {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.ai-counts {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.ai-empty {
  margin: 0;
  font-size: 12px;
  color: var(--paiban-muted);
}

.ai-add {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ai-note-inline {
  font-size: 12px;
  color: var(--paiban-muted);
}

.audit-list {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid #eef1f6;
}

.audit-title {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 600;
}

.ai-note {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f7f8fa;
  border: 1px dashed #dcdfe6;
  font-size: 12px;
  color: var(--paiban-muted);
}

.ai-history {
  margin-top: 14px;
}

.history-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}

.history-list {
  margin: 8px 0 0;
  padding-left: 0;
  list-style: none;
}

.history-detail {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #eef1f6;
}

.raw {
  margin: 8px 0 0;
  padding: 10px;
  max-height: 240px;
  overflow: auto;
  border-radius: 6px;
  background: #f7f8fa;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
