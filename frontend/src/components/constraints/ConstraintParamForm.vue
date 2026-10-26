<template>
  <div class="params-box" data-testid="constraint-param-form">
    <div v-for="param in params" :key="param.name" class="param-field">
      <span class="param-label">
        <span v-if="param.required" class="required-star">*</span>
        {{ param.label }}：
        <InfoTooltip field="constraint.param" />
      </span>

      <el-select
        v-if="param.type === 'ID'"
        :model-value="selectValue(values, param.name)"
        filterable
        clearable
        :placeholder="`请选择${param.label}`"
        :disabled="disabled"
        style="width: 180px"
        @update:model-value="(value: unknown) => emit('update', param.name, value)"
      >
        <el-option
          v-for="option in idOptions(param)"
          :key="option.id"
          :label="option.name"
          :value="option.id"
        />
      </el-select>

      <el-select
        v-else-if="param.type === 'DAY'"
        :model-value="selectValue(values, param.name)"
        placeholder="选择星期"
        clearable
        :disabled="disabled"
        style="width: 130px"
        @update:model-value="(value: unknown) => emit('update', param.name, value)"
      >
        <el-option v-for="day in DAY_OPTIONS" :key="day.value" :label="day.label" :value="day.value" />
      </el-select>

      <el-input-number
        v-else-if="param.type === 'PERIOD'"
        :model-value="numberValue(values, param.name)"
        :min="1"
        :max="20"
        controls-position="right"
        :disabled="disabled"
        style="width: 120px"
        @update:model-value="(value: number | null | undefined) => emit('update', param.name, value)"
      />

      <el-input-number
        v-else-if="param.type === 'INT'"
        :model-value="numberValue(values, param.name)"
        :min="param.min ?? 0"
        :max="param.max ?? 100"
        controls-position="right"
        :disabled="disabled"
        style="width: 120px"
        @update:model-value="(value: number | null | undefined) => emit('update', param.name, value)"
      />

      <el-select
        v-else-if="param.type === 'ROOM_TYPE'"
        :model-value="selectValue(values, param.name)"
        placeholder="选择教室类型"
        clearable
        :disabled="disabled"
        style="width: 160px"
        @update:model-value="(value: unknown) => emit('update', param.name, value)"
      >
        <el-option
          v-for="option in ROOM_TYPE_OPTIONS"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>

      <el-input
        v-else
        :model-value="textValue(values, param.name)"
        :placeholder="`请输入${param.label}`"
        clearable
        :disabled="disabled"
        style="width: 180px"
        @update:model-value="(value: string) => emit('update', param.name, value)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElInput, ElInputNumber, ElOption, ElSelect } from 'element-plus'
import InfoTooltip from '@/components/InfoTooltip.vue'
import {
  DAY_OPTIONS,
  ROOM_TYPE_OPTIONS,
  numberValue,
  selectValue,
  textValue,
} from './draftRules'
import type { OptionItem, ParamDef } from './types'

const props = defineProps<{
  params: ParamDef[]
  values: Record<string, unknown>
  teachers: OptionItem[]
  courses: OptionItem[]
  disabled: boolean
}>()

const emit = defineEmits<{ update: [name: string, value: unknown] }>()

function idOptions(param: ParamDef): OptionItem[] {
  const lower = param.name.toLowerCase()
  if (lower.includes('teacher')) {
    return props.teachers
  }
  if (lower.includes('course')) {
    return props.courses
  }
  return []
}
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
