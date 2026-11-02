<template>
  <div class="weekly-scope">
    <el-radio-group
      :model-value="scope" :disabled="disabled" aria-label="不上课适用范围"
      data-testid="constraint-entry-class-scope" @update:model-value="(value) => emit('update', 'classScope', value)"
    >
      <el-radio value="ALL">全校</el-radio>
      <el-radio value="SELECTED">指定班级</el-radio>
    </el-radio-group>
    <p v-if="scope === 'ALL'">适用于本学期所有班级，包括以后新增的班级。</p>
    <el-select
      v-else :model-value="selected" multiple collapse-tags collapse-tags-tooltip
      :disabled="disabled" placeholder="请选择至少一个班级" aria-label="选择不上课的班级"
      data-testid="constraint-entry-class" @update:model-value="(value) => emit('update', 'classIds', value)"
    >
      <el-option v-for="item in classes ?? []" :key="item.id" :value="item.id" :label="item.name" />
    </el-select>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElOption, ElRadio, ElRadioGroup, ElSelect } from 'element-plus'
import { weeklyClasses } from './weeklyScope'
import type { EntryDraft, OptionItem } from './types'
const props = defineProps<{ entry: EntryDraft; classes?: OptionItem[]; disabled: boolean }>()
const emit = defineEmits<{ update: [name: string, value: unknown] }>()
const scope = computed(() => String(props.entry.values.classScope ?? (props.entry.values.classId == null ? 'ALL' : 'SELECTED')))
const selected = computed(() => weeklyClasses(props.entry) ?? [])
</script>

<style scoped>
.weekly-scope { width: min(100%, 480px); }
.weekly-scope p { font-size: 12px; color: var(--paiban-muted); margin: 4px 0; }
.weekly-scope :deep(.el-select) { width: 100%; }
</style>
