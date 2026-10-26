<template>
  <el-select
    v-if="param.type === 'ID'"
    :model-value="selectValue(values, param.name)"
    filterable
    clearable
    :placeholder="`请选择${plainLabel(param)}`"
    :disabled="disabled"
    :data-testid="testId"
    class="field-id"
    @update:model-value="(value: unknown) => emit('update', param.name, value)"
  >
    <el-option
      v-for="option in idOptions(param, teachers, courses)"
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
    :data-testid="testId"
    class="field-day"
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
    :data-testid="testId"
    class="field-number"
    @update:model-value="(value: number | null | undefined) => emit('update', param.name, value)"
  />

  <el-input-number
    v-else-if="param.type === 'INT'"
    :model-value="numberValue(values, param.name)"
    :min="param.min ?? 0"
    :max="param.max ?? 100"
    controls-position="right"
    :disabled="disabled"
    :data-testid="testId"
    class="field-number"
    @update:model-value="(value: number | null | undefined) => emit('update', param.name, value)"
  />

  <el-select
    v-else-if="param.type === 'ROOM_TYPE'"
    :model-value="selectValue(values, param.name)"
    placeholder="选择教室类型"
    clearable
    :disabled="disabled"
    :data-testid="testId"
    class="field-room-type"
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
    :placeholder="`请输入${plainLabel(param)}`"
    clearable
    :disabled="disabled"
    :data-testid="testId"
    class="field-text"
    @update:model-value="(value: string) => emit('update', param.name, value)"
  />
</template>

<script setup lang="ts">
import { ElInput, ElInputNumber, ElOption, ElSelect } from 'element-plus'
import {
  DAY_OPTIONS,
  ROOM_TYPE_OPTIONS,
  idOptions,
  numberValue,
  plainLabel,
  selectValue,
  textValue,
} from './paramValues'
import type { OptionItem, ParamDef } from './types'

/**
 * One parameter control, chosen from the catalogue's declared type. Used by both the
 * single-value (threshold) form and the entry cards, so every type gets the same
 * widget for the same parameter.
 */
defineProps<{
  param: ParamDef
  values: Record<string, unknown>
  teachers: OptionItem[]
  courses: OptionItem[]
  disabled: boolean
  testId: string
}>()

const emit = defineEmits<{ update: [name: string, value: unknown] }>()
</script>

<style scoped>
.field-id {
  width: 180px;
}

.field-day {
  width: 130px;
}

.field-number {
  width: 130px;
}

.field-room-type {
  width: 160px;
}

.field-text {
  width: 180px;
}
</style>
