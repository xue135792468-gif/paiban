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

    <div v-if="preview.teacherWarnings.length" class="mappings" data-testid="placeholder-decisions">
      <h4>占位教师怎么处理（每个都要选）</h4>
      <div
        v-for="warning in preview.teacherWarnings"
        :key="warning.value"
        class="mapping-row"
        data-testid="placeholder-row"
      >
        <el-tag type="warning" effect="plain">{{ warning.value }}</el-tag>
        <template v-if="needsDecision(warning.value)">
          <el-radio-group
            :model-value="choiceOf(warning.value)"
            data-testid="placeholder-choice"
            @update:model-value="(value: string | number | boolean) => setChoice(warning.value, String(value))"
          >
            <el-radio value="map">映射到已有教师</el-radio>
            <el-radio value="keep">保留为教师</el-radio>
          </el-radio-group>
          <el-select
            v-if="choiceOf(warning.value) === 'map'"
            :model-value="teacherMappings[warning.value] ?? ''"
            clearable
            placeholder="选择教师"
            data-testid="manual-teacher-mapping"
            @update:model-value="(value: string | null) => setMapping(warning.value, value)"
          >
            <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="String(teacher.id)" />
          </el-select>
        </template>
        <span v-else class="hint existing">系统里已有同名教师，直接使用</span>
      </div>
      <p v-if="pendingTeachers.length" class="hint pending" data-testid="placeholder-pending">
        还有 {{ pendingTeachers.length }} 个占位教师没有选择：{{ pendingTeachers.join('、') }}。
        不选就不入库，避免悄悄多出一位教师。
      </p>
    </div>

    <div class="actions">
      <el-button
        type="primary"
        :loading="committing"
        :disabled="pendingTeachers.length > 0"
        data-testid="manual-commit"
        @click="emit('commit')"
      >
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
  keptPlaceholders: string[]
  pendingTeachers: string[]
  committing: boolean
}>()

const emit = defineEmits<{
  'update:semesterId': [value: number]
  'update:draftName': [value: string]
  'update:applyRule': [value: boolean]
  'update:teacherMappings': [value: Record<string, string>]
  'update:keptPlaceholders': [value: string[]]
  commit: []
}>()

/** '' = 还没选；'map' = 映射到已有教师；'keep' = 保留占位名并创建教师。 */
function choiceOf(source: string): string {
  if (props.keptPlaceholders.includes(source)) return 'keep'
  return props.teacherMappings[source] ? 'map' : ''
}

/** 只有“入库会新建教师”的占位名才需要用户拍板。 */
function needsDecision(source: string): boolean {
  return props.preview.pendingTeachers.includes(source)
}

function setChoice(source: string, choice: string) {
  if (choice === 'keep') {
    setMapping(source, null)
    emit('update:keptPlaceholders', [...props.keptPlaceholders.filter((name) => name !== source), source])
    return
  }
  emit('update:keptPlaceholders', props.keptPlaceholders.filter((name) => name !== source))
  if (choice !== 'map') {
    setMapping(source, null)
  }
}

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
  flex-wrap: wrap;
}
.pending {
  color: #b26a00;
}
.mapping-row :deep(.el-select) {
  width: 240px;
}
.actions {
  display: flex;
  justify-content: flex-end;
}
</style>
