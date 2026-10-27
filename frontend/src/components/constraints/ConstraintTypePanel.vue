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

    <template v-else-if="isEntryType(row)">
      <p v-if="isSlotSetType(row)" class="panel-hint" data-testid="constraint-slot-note">
        同一条要求可包含多个时段，整体编辑、暂停或删除；每个时段共用这条要求的必须/尽量与重要程度。
      </p>
      <ConstraintEntryList
        :row="row"
        :editable="canManage"
        :is-junior="isJunior"
        :teachers="teachers"
        :courses="courses"
        :entry-errors="entryErrors"
        @add="emit('add-entry')"
        @remove="(key: string) => emit('remove-entry', key)"
        @update="(key: string, name: string, value: unknown) => emit('update-entry', key, name, value)"
        @all-target="(key: string, name: string, all: boolean) => emit('set-all-target', key, name, all)"
        @add-slot="(key: string) => emit('add-slot', key)"
        @remove-slot="(key: string, slotKey: string) => emit('remove-slot', key, slotKey)"
        @update-slot="(key: string, slotKey: string, field: string, value: unknown) => emit('update-slot', key, slotKey, field, value)"
        @set-entry-enabled="(key: string, value: boolean) => emit('set-entry-enabled', key, value)"
        @set-entry-hard="(key: string, value: boolean | null) => emit('set-entry-hard', key, value)"
        @set-entry-weight="(key: string, value: number | null) => emit('set-entry-weight', key, value)"
      />
    </template>

    <ConstraintThresholdPanel
      v-else-if="isThresholdType(row)"
      :row="row"
      :editable="canManage"
      :object-options="thresholdOptions"
      :errors="entryErrors"
      @set-global="(value: number | null) => emit('set-threshold-global', value)"
      @add-global="emit('add-threshold-global')"
      @add-exception="emit('add-threshold-exception')"
      @remove-exception="(key: string) => emit('remove-threshold-exception', key)"
      @set-exception-object="(key: string, value: unknown) => emit('set-threshold-object', key, value)"
      @set-exception-value="(key: string, value: unknown) => emit('set-threshold-value', key, value)"
      @set-global-enabled="(value: boolean) => emit('set-threshold-global-enabled', value)"
      @set-global-hard="(value: boolean | null) => emit('set-threshold-global-hard', value)"
      @set-global-weight="(value: number | null) => emit('set-threshold-global-weight', value)"
      @set-exception-enabled="(key: string, value: boolean) => emit('set-threshold-exception-enabled', key, value)"
      @set-exception-hard="(key: string, value: boolean | null) => emit('set-threshold-exception-hard', key, value)"
      @set-exception-weight="(key: string, value: number | null) => emit('set-threshold-exception-weight', key, value)"
    />
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElRadioButton, ElRadioGroup, ElSlider, ElSwitch } from 'element-plus'
import InfoTooltip from '@/components/InfoTooltip.vue'
import ConstraintEntryList from './ConstraintEntryList.vue'
import ConstraintThresholdPanel from './ConstraintThresholdPanel.vue'
import { isEntryType, isSlotSetType, isSwitchType, isThresholdType } from './entryModel'
import { thresholdShape } from './thresholdModel'
import type { ObjectOptions, OptionItem, TypeDraft } from './types'

const props = defineProps<{
  row: TypeDraft
  canManage: boolean
  isJunior: boolean
  teachers: OptionItem[]
  courses: OptionItem[]
  /** The objects each entry field may name, kept per field (never merged into one list). */
  objectOptions: ObjectOptions
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
  'set-all-target': [key: string, name: string, all: boolean]
  'add-slot': [key: string]
  'remove-slot': [key: string, slotKey: string]
  'update-slot': [key: string, slotKey: string, field: string, value: unknown]
  'set-threshold-global': [value: number | null]
  'add-threshold-global': []
  'add-threshold-exception': []
  'remove-threshold-exception': [key: string]
  'set-threshold-object': [key: string, value: unknown]
  'set-threshold-value': [key: string, value: unknown]
  'set-entry-enabled': [key: string, value: boolean]
  'set-entry-hard': [key: string, value: boolean | null]
  'set-entry-weight': [key: string, value: number | null]
  'set-threshold-global-enabled': [value: boolean]
  'set-threshold-global-hard': [value: boolean | null]
  'set-threshold-global-weight': [value: number | null]
  'set-threshold-exception-enabled': [key: string, value: boolean]
  'set-threshold-exception-hard': [key: string, value: boolean | null]
  'set-threshold-exception-weight': [key: string, value: number | null]
}>()

/**
 * The objects a threshold type may name: only the applicable catalogue. A teacher
 * limit offers teachers, a class limit offers the semester's administrative classes,
 * a course limit offers courses — never a list the type cannot use, and never a merged
 * list in which a course and a teacher could share an id.
 */
const thresholdOptions = computed<OptionItem[]>(() => {
  const field = thresholdShape(props.row)?.objectField
  if (field === 'teacherId' || field === 'courseId' || field === 'classId') {
    return props.objectOptions[field]
  }
  return []
})

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
