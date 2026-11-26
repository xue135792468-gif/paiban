<template>
  <div class="grade-scope-picker" data-testid="grade-scope-picker">
    <el-select
      v-model="selected"
      multiple
      collapse-tags
      collapse-tags-tooltip
      placeholder="选择本次排课的年级"
      :disabled="grades.length === 0"
      style="width: 100%"
    >
      <el-option v-for="grade in grades" :key="grade.id" :label="grade.name" :value="grade.id" />
    </el-select>
    <span
      class="grade-scope-hint"
      :class="{ 'is-warning': partialBlocked }"
      data-testid="grade-scope-hint"
    >
      {{ hint }}
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { GradeOption } from './types'

const props = withDefaults(
  defineProps<{
    modelValue: number[]
    grades: GradeOption[]
    partialAllowed?: boolean
  }>(),
  {
    partialAllowed: true,
  },
)

const emit = defineEmits<{ 'update:modelValue': [value: number[]] }>()

const selected = computed({
  get: () => props.modelValue,
  set: (value: number[]) => emit('update:modelValue', value),
})

const isPartial = computed(() => {
  const total = props.grades.length
  const count = selected.value.length
  return total > 1 && count > 0 && count < total
})

const partialBlocked = computed(() => !props.partialAllowed && isPartial.value)

const hint = computed(() => {
  const total = props.grades.length
  if (total === 0) return '本学期还没有年级'
  const count = selected.value.length
  if (count === 0 || count >= total) {
    if (!props.partialAllowed && total > 1) {
      return `本次排全部 ${total} 个年级（其他年级未发布，暂不可单独排部分年级）`
    }
    return `本次排全部 ${total} 个年级`
  }
  if (!props.partialAllowed) {
    return '其他年级尚未发布课表，当前仅支持全校排课，请全选全部年级'
  }
  return `本次排 ${count} 个年级 / 其他 ${total - count} 个年级保持不动`
})
</script>

<style scoped>
.grade-scope-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 380px;
}

.grade-scope-hint {
  flex: 1;
  min-width: 170px;
  color: var(--paiban-muted);
  font-size: 12px;
  line-height: 1.3;
}

.grade-scope-hint.is-warning {
  color: #b45309;
  font-weight: 500;
}
</style>
