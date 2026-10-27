<template>
  <el-alert
    v-if="stale"
    type="warning"
    :closable="false"
    show-icon
    class="error-bar"
    data-testid="constraint-workspace-stale"
  >
    <template #title>生效配置已被他人更新</template>
    <p class="error-copy">恢复的未保存修改仍保留；保存时会要求你核对最新配置后再写入。</p>
  </el-alert>

  <el-alert
    v-if="issues.length > 0"
    type="warning"
    :closable="false"
    show-icon
    class="error-bar"
    data-testid="constraint-workspace-issues"
  >
    <template #title>恢复的未保存内容需要复核</template>
    <p v-for="(issue, index) in issues" :key="index" class="error-copy">{{ issue }}</p>
  </el-alert>

  <el-alert
    v-if="error"
    type="error"
    :closable="false"
    show-icon
    class="error-bar"
    data-testid="constraint-workspace-error"
  >
    <template #title>暂存失败，跨设备恢复尚未更新</template>
    <p class="error-copy">{{ error }}</p>
  </el-alert>

  <el-alert
    v-if="conflict"
    type="warning"
    :closable="false"
    show-icon
    class="error-bar"
    data-testid="constraint-workspace-conflict"
  >
    <template #title>另一台设备更新了未保存工作</template>
    <p class="error-copy">
      本地内容仍保留。请选择继续本地版本，或加载另一台设备的未保存版本。
    </p>
    <div class="conflict-actions">
      <el-button size="small" data-testid="constraint-workspace-keep" @click="emit('keep-local')">
        保留我的
      </el-button>
      <el-button
        size="small"
        type="primary"
        data-testid="constraint-workspace-remote"
        @click="emit('load-remote')"
      >
        加载另一台设备
      </el-button>
    </div>
  </el-alert>

  <el-alert
    v-if="outcomes.length > 0"
    :type="outcomeType"
    :closable="true"
    show-icon
    class="error-bar"
    data-testid="constraint-ai-outcomes"
    @close="emit('clear-outcomes')"
  >
    <template #title>AI 建议加入结果</template>
    <p v-for="(outcome, index) in outcomes" :key="index" class="error-copy">
      {{ outcome.message }}
    </p>
  </el-alert>
</template>

<script setup lang="ts">
import { ElAlert, ElButton } from 'element-plus'
import type { WorkspaceDto } from './useConstraintWorkspace'

/**
 * The page-level warnings about cross-device work and AI proposal additions. They are
 * one presentational block so the constraint view stays a thin rendering shell.
 */
defineProps<{
  stale: boolean
  issues: string[]
  error: string | null
  conflict: WorkspaceDto | null
  outcomes: { proposalId: string; action: string; message: string }[]
  outcomeType: 'success' | 'warning'
}>()

const emit = defineEmits<{
  'keep-local': []
  'load-remote': []
  'clear-outcomes': []
}>()
</script>

<style scoped>
.error-bar {
  margin-bottom: 14px;
}

.error-copy {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.conflict-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
</style>
