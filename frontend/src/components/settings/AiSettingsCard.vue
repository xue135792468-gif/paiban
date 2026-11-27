<template>
  <el-card shadow="never" data-testid="ai-settings-card">
    <template #header>
      <span class="card-title">AI 设置 <InfoTooltip field="settings.aiProvider" /></span>
    </template>
    <div class="ai-row">
      <div>
        <div class="ai-name">AI 供应商与模型管理</div>
        <div class="hint">配置供应商与模型，并分配各 AI 任务使用的模型。</div>
      </div>
      <el-button plain @click="router.push('/ai')">去配置</el-button>
    </div>
    <div class="ai-row">
      <div>
        <div class="ai-name">数据导入低置信度阈值</div>
        <div class="hint">低于该值的单元格在预览中标黄，默认 0.8</div>
      </div>
      <el-input-number
        :model-value="confidenceThreshold"
        :min="0"
        :max="1"
        :step="0.05"
        :precision="2"
        @update:model-value="
          (value?: number) => (form['ai.import.confidence-threshold'] = String(value ?? 0.8))
        "
      />
    </div>
    <div class="ai-row">
      <div>
        <div class="ai-name">自然语言生成约束</div>
        <div class="hint">用一句话描述排课规则，自动转换为约束配置</div>
      </div>
      <el-switch
        :model-value="form['ai.features.natural-language-constraints'] === 'true'"
        data-testid="settings-natural-language-switch"
        @update:model-value="
          (value: string | number | boolean) =>
            (form['ai.features.natural-language-constraints'] = value ? 'true' : 'false')
        "
      />
    </div>
    <el-button type="primary" :loading="saving" data-testid="ai-save" @click="save('AI 设置已保存')">
      保存 AI 设置
    </el-button>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import InfoTooltip from '@/components/InfoTooltip.vue'
import { useSettingsSection } from './useSettingsSection'

const form = defineModel<Record<string, string>>({ required: true })
const router = useRouter()
const confidenceThreshold = computed(() => Number(form.value['ai.import.confidence-threshold'] ?? 0.8))
const { saving, save } = useSettingsSection(form, [
  'ai.import.confidence-threshold',
  'ai.features.natural-language-constraints',
])
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.ai-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px dashed #eef0f5;
}
.ai-row > div:first-child {
  flex: 1;
}
.ai-name {
  font-weight: 600;
  font-size: 14px;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 6px;
}
</style>
