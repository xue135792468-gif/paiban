<template>
  <div class="params-box" data-testid="constraint-param-form">
    <div v-for="param in params" :key="param.name" class="param-field">
      <span class="param-label">
        <span v-if="param.required" class="required-star">*</span>
        {{ param.label }}：
        <InfoTooltip field="constraint.param" />
      </span>
      <ParamField
        :param="param"
        :values="values"
        :teachers="teachers"
        :courses="courses"
        :disabled="disabled"
        :test-id="`constraint-param-${fieldTestId(param.name)}`"
        @update="(name: string, value: unknown) => emit('update', name, value)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import InfoTooltip from '@/components/InfoTooltip.vue'
import ParamField from './ParamField.vue'
import { fieldTestId } from './paramValues'
import type { OptionItem, ParamDef } from './types'

/**
 * The single-value form, kept for the threshold types (one number per type, still
 * type-level). Entry-backed types render `ConstraintEntryList` instead.
 */
defineProps<{
  params: ParamDef[]
  values: Record<string, unknown>
  teachers: OptionItem[]
  courses: OptionItem[]
  disabled: boolean
}>()

const emit = defineEmits<{ update: [name: string, value: unknown] }>()
</script>

<style scoped>
.params-box {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
}

.param-field {
  display: flex;
  align-items: center;
  gap: 6px;
}

.param-label {
  font-size: 13px;
  color: #4b5563;
}

.required-star {
  color: var(--el-color-danger, #f56c6c);
  margin-right: 2px;
  font-weight: bold;
}
</style>
