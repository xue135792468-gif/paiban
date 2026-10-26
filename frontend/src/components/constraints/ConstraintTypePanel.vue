<template>
  <section class="constraint-card" :class="{ 'is-disabled': !row.enabled }" data-testid="constraint-panel">
    <header class="constraint-head">
      <div class="constraint-main">
        <div class="constraint-info">
          <span class="constraint-name" data-testid="constraint-panel-title">{{ row.name }}</span>
          <span class="constraint-desc" data-testid="constraint-panel-desc">{{ row.description }}</span>
        </div>
      </div>

      <div class="constraint-controls">
        <el-switch
          :model-value="row.enabled"
          active-text="启用"
          :disabled="!canManage"
          data-testid="constraint-enabled-switch"
          @update:model-value="(value: string | number | boolean | undefined) => emit('toggle-enabled', Boolean(value))"
        />
        <el-radio-group
          :model-value="row.hard"
          size="small"
          :disabled="!canManage || !row.enabled"
          data-testid="constraint-hard-group"
          @update:model-value="(value: string | number | boolean | undefined) => emit('set-hard', Boolean(value))"
        >
          <el-radio-button :value="true">{{ isJunior ? '必须' : '硬约束' }}</el-radio-button>
          <el-radio-button :value="false">{{ isJunior ? '尽量' : '软约束' }}</el-radio-button>
        </el-radio-group>
        <InfoTooltip field="constraint.hard" />

        <!-- 硬约束的权重没有意义：ADR-0028 把硬/软与权重留在类型级，只在软约束上显示权重。 -->
        <div v-if="!isJunior && !row.hard" class="weight-box" data-testid="constraint-weight">
          <span class="weight-label">
            权重 {{ row.weight }}
            <InfoTooltip field="constraint.weight" />
          </span>
          <el-slider
            :model-value="row.weight"
            :min="0"
            :max="100"
            :disabled="!canManage || !row.enabled"
            style="width: 130px"
            @update:model-value="onWeight"
          />
        </div>
      </div>
    </header>

    <p v-if="typeErrors.length > 0" class="type-error" data-testid="constraint-type-error">
      {{ typeErrors.join('；') }}
    </p>

    <p v-if="!row.enabled" class="panel-hint" data-testid="constraint-disabled-hint">
      该类型当前关闭：下面保留的条件不会被评估，重新启用后照旧生效。
    </p>

    <div v-if="isSwitchType(row)" class="switch-panel" data-testid="constraint-switch-note">
      <div class="switch-copy">
        <span class="switch-title">开关类型</span>
        <span>启用即一条生效规则；没有条目可增删，也没有对象条件。</span>
      </div>
      <div class="switch-fact">{{ row.enabled ? '当前：1 条生效规则' : '当前：0 条生效规则' }}</div>
    </div>

    <ConstraintEntryList
      v-else-if="isMultiEntryType(row)"
      :row="row"
      :editable="canManage"
      :teachers="teachers"
      :entry-errors="entryErrors"
      @add="emit('add-entry')"
      @remove="(key: string) => emit('remove-entry', key)"
      @update="(key: string, name: string, value: unknown) => emit('update-entry', key, name, value)"
    />

    <ConstraintParamForm
      v-else
      :params="row.params"
      :values="row.values"
      :teachers="teachers"
      :courses="courses"
      :disabled="!canManage"
      @update="(name: string, value: unknown) => emit('update-value', name, value)"
    />
  </section>
</template>

<script setup lang="ts">
import { ElRadioButton, ElRadioGroup, ElSlider, ElSwitch } from 'element-plus'
import InfoTooltip from '@/components/InfoTooltip.vue'
import ConstraintEntryList from './ConstraintEntryList.vue'
import ConstraintParamForm from './ConstraintParamForm.vue'
import { isMultiEntryType, isSwitchType } from './draftRules'
import type { OptionItem, TypeDraft } from './types'

defineProps<{
  row: TypeDraft
  canManage: boolean
  isJunior: boolean
  teachers: OptionItem[]
  courses: OptionItem[]
  entryErrors: Record<number, string[]>
  typeErrors: string[]
}>()

const emit = defineEmits<{
  'toggle-enabled': [value: boolean]
  'set-hard': [value: boolean]
  'set-weight': [value: number]
  'update-value': [name: string, value: unknown]
  'update-entry': [key: string, name: string, value: unknown]
  'add-entry': []
  'remove-entry': [key: string]
}>()

function onWeight(value: number | number[]) {
  emit('set-weight', Array.isArray(value) ? value[0] : value)
}
</script>

<style scoped>
.constraint-card {
  padding: 16px 18px;
  border-radius: var(--paiban-radius, 8px);
  background: #ffffff;
  border: 1px solid #ebeef5;
  transition: all 0.2s ease;
}

.constraint-card:hover {
  border-color: #dcdfe6;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.constraint-card.is-disabled {
  background: #fafafa;
}

.constraint-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.constraint-main {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1;
  min-width: 240px;
}

.constraint-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.constraint-name {
  font-weight: 600;
  font-size: 16px;
  color: var(--paiban-ink);
}

.constraint-desc {
  font-size: 12px;
  color: var(--paiban-muted);
  line-height: 1.4;
}

.constraint-controls {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.weight-box {
  display: flex;
  align-items: center;
  gap: 8px;
}

.weight-label {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: var(--paiban-muted);
  white-space: nowrap;
  flex-shrink: 0;
}

.type-error {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #fef0f0;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
  line-height: 1.5;
}

.panel-hint {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f7f8fa;
  border: 1px dashed #dcdfe6;
  font-size: 12px;
  color: var(--paiban-muted);
}

.switch-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 14px;
  padding: 14px;
  border-radius: 8px;
  background: #fbfcfe;
  border: 1px solid #eef1f6;
}

.switch-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--paiban-muted);
}

.switch-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--paiban-ink);
}

.switch-fact {
  font-size: 13px;
  color: var(--el-color-primary, #4f6ef7);
  font-weight: 600;
}
</style>
