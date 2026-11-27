<template>
  <ConstraintAiPanel
    v-if="aiAvailable && showAi && semesterId !== null"
    :semester-id="semesterId"
    :types="types"
    :teachers="teachers"
    :courses="courses"
    :can-read-all="canReadAll"
    :can-manage="canManage"
    @add="emit('ai-add', $event)"
  />
  <el-alert
    v-if="aiNotice"
    type="info"
    :closable="false"
    show-icon
    class="alert"
    data-testid="rule-ai-notice"
    :title="aiNotice"
  />
  <el-alert
    v-if="conflict"
    type="warning"
    :closable="false"
    show-icon
    class="alert"
    data-testid="rule-conflict"
  >
    <template #title>规则已被他人修改（当前版本 {{ conflict.version }}）</template>
    <p class="alert-copy">{{ conflict.message }}</p>
    <el-button size="small" type="primary" data-testid="rule-conflict-reload" @click="emit('reload')">
      加载最新配置（放弃我的修改）
    </el-button>
  </el-alert>
  <el-alert
    v-if="errorMessage"
    type="error"
    :closable="false"
    show-icon
    class="alert"
    data-testid="rule-save-error"
  >
    <template #title>保存失败，已保留当前输入</template>
    <p class="alert-copy">{{ errorMessage }}</p>
  </el-alert>
</template>

<script setup lang="ts">
import ConstraintAiPanel from '@/components/constraints/ConstraintAiPanel.vue'
import type { TypeDraft } from '@/components/constraints/types'
import type { ConstraintProposal } from '@/components/constraints/useConstraintAi'
import type { OptionItem } from '@/components/constraints/types'

defineProps<{
  aiAvailable: boolean
  showAi: boolean
  semesterId: number | null
  types: TypeDraft[]
  teachers: OptionItem[]
  courses: OptionItem[]
  canReadAll: boolean
  canManage: boolean
  aiNotice: string
  conflict: { message: string; version: number } | null
  errorMessage: string | null
}>()

const emit = defineEmits<{
  'ai-add': [payload: { runId: number; proposals: ConstraintProposal[] }]
  reload: []
}>()
</script>

<style scoped>
.alert {
  margin-bottom: 14px;
}
.alert-copy {
  margin: 4px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}
</style>
