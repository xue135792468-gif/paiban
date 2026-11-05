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
    <span class="grade-scope-hint" data-testid="grade-scope-hint">{{ hint }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { GradeOption } from './types'

const props = defineProps<{
  modelValue: number[]
  grades: GradeOption[]
}>()

const emit = defineEmits<{ 'update:modelValue': [value: number[]] }>()

const selected = computed({
  get: () => props.modelValue,
  set: (value: number[]) => emit('update:modelValue', value),
})

const hint = computed(() => {
  const total = props.grades.length
  if (total === 0) return '本学期还没有年级'
  const count = selected.value.length
  if (count === 0 || count >= total) return `本次排全部 ${total} 个年级`
  return `本次排 ${count} 个年级 / 其他 ${total - count} 个年级保持不动`
})
</script>

<style scoped>
.grade-scope-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 340px;
}

.grade-scope-hint {
  flex: 1;
  min-width: 150px;
  color: var(--paiban-muted);
  font-size: 12px;
  line-height: 1.3;
}
</style>
