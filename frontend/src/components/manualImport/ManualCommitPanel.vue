<template>
  <div class="commit" data-testid="manual-commit-panel">
    <div class="row">
      <label class="field">
        <span>目标学期</span>
        <el-select
          :model-value="semesterId"
          data-testid="manual-semester"
          @update:model-value="(value: number) => emit('update:semesterId', value)"
        >
          <el-option v-for="item in semesters" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </label>
      <label class="field grow">
        <span>草稿名称</span>
        <el-input
          :model-value="draftName"
          data-testid="manual-draft-name"
          placeholder="例如：八年级课表（导入）"
          @update:model-value="(value: string) => emit('update:draftName', value)"
        />
      </label>
    </div>

    <el-checkbox
      v-if="preview.suggestedRule"
      :model-value="applyRule"
      data-testid="manual-apply-rule"
      @update:model-value="(value: boolean) => emit('update:applyRule', value)"
    >
      同时生成规则：{{ preview.suggestedRule.text }}
    </el-checkbox>

    <div v-if="preview.teacherWarnings.length" class="mappings">
      <h4>占位教师映射（不选则按占位名创建教师）</h4>
      <div v-for="warning in preview.teacherWarnings" :key="warning.value" class="mapping-row">
        <el-tag type="warning" effect="plain">{{ warning.value }}</el-tag>
        <el-select
          :model-value="teacherMappings[warning.value] ?? ''"
          clearable
          placeholder="保留占位名"
          data-testid="manual-teacher-mapping"
          @update:model-value="(value: string | null) => setMapping(warning.value, value)"
        >
          <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="String(teacher.id)" />
        </el-select>
      </div>
    </div>

    <div class="actions">
      <el-button type="primary" :loading="committing" data-testid="manual-commit" @click="emit('commit')">
        确认入库生成草稿
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ManualPreview } from '@/api/imports'

const props = defineProps<{
  preview: ManualPreview
  semesters: { id: number; name: string }[]
  teachers: { id: number; name: string }[]
  semesterId: number | null
  draftName: string
  applyRule: boolean
  teacherMappings: Record<string, string>
  committing: boolean
}>()

const emit = defineEmits<{
  'update:semesterId': [value: number]
  'update:draftName': [value: string]
  'update:applyRule': [value: boolean]
  'update:teacherMappings': [value: Record<string, string>]
  commit: []
}>()

function setMapping(source: string, target: string | null) {
  const next = { ...props.teacherMappings }
  if (target) {
    next[source] = target
  } else {
    delete next[source]
  }
  emit('update:teacherMappings', next)
}
</script>

<style scoped>
.commit {
  display: grid;
  gap: 12px;
  padding-top: 8px;
  border-top: 1px dashed #e5e7ef;
}
.row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--paiban-muted);
}
.grow {
  flex: 1;
  min-width: 240px;
}
.mappings {
  display: grid;
  gap: 8px;
}
.mappings h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: #b26a00;
}
.mapping-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mapping-row :deep(.el-select) {
  width: 240px;
}
.actions {
  display: flex;
  justify-content: flex-end;
}
</style>
