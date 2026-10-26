<template>
  <div class="entry-list" data-testid="constraint-entry-list">
    <p v-if="row.entries.length === 0" class="entry-empty" data-testid="constraint-entry-empty">
      空列表＝该类型没有生效条目，不会自动补一条规则。
    </p>

    <article
      v-for="(entry, index) in row.entries"
      :key="entry.key"
      class="entry-card"
      data-testid="constraint-entry"
      :data-index="index"
    >
      <header class="entry-card-head">
        <span class="entry-index">#{{ index + 1 }}</span>
        <span class="entry-summary" data-testid="constraint-entry-summary">{{ summaryOf(entry) }}</span>
        <el-button
          link
          type="danger"
          :disabled="!editable"
          data-testid="constraint-entry-remove"
          @click="remove(entry)"
        >
          删除
        </el-button>
      </header>

      <p v-if="entryErrors[index + 1]" class="entry-error" data-testid="constraint-entry-error">
        {{ entryErrors[index + 1].join('；') }}
      </p>
      <p
        v-else-if="duplicates[index] !== undefined"
        class="entry-error"
        data-testid="constraint-entry-duplicate"
      >
        与第 {{ duplicates[index] + 1 }} 条完全相同，保存会被拒绝。
      </p>

      <div class="entry-fields">
        <el-select
          :model-value="selectValue(entry.values, 'teacherId')"
          filterable
          clearable
          placeholder="请选择教师"
          :disabled="!editable"
          data-testid="constraint-entry-teacher"
          class="field-teacher"
          @update:model-value="(value: unknown) => emit('update', entry.key, 'teacherId', value)"
        >
          <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.name" :value="teacher.id" />
        </el-select>

        <el-select
          :model-value="selectValue(entry.values, 'dayOfWeek')"
          placeholder="选择星期"
          clearable
          :disabled="!editable"
          data-testid="constraint-entry-day"
          class="field-day"
          @update:model-value="(value: unknown) => emit('update', entry.key, 'dayOfWeek', value)"
        >
          <el-option v-for="day in DAY_OPTIONS" :key="day.value" :label="day.label" :value="day.value" />
        </el-select>

        <el-input-number
          :model-value="numberValue(entry.values, 'periodNo')"
          :min="1"
          :max="20"
          controls-position="right"
          :disabled="!editable"
          data-testid="constraint-entry-period"
          class="field-period"
          @update:model-value="(value: number | null | undefined) => emit('update', entry.key, 'periodNo', value)"
        />
      </div>
    </article>

    <el-button
      class="add-entry"
      :disabled="!editable"
      data-testid="constraint-entry-add"
      @click="emit('add')"
    >
      ＋ 添加条目
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElInputNumber, ElMessageBox, ElOption, ElSelect } from 'element-plus'
import {
  DAY_OPTIONS,
  duplicateEntryIndexes,
  entrySummary,
  numberValue,
  selectValue,
} from './draftRules'
import type { EntryDraft, OptionItem, TypeDraft } from './types'

const props = defineProps<{
  row: TypeDraft
  editable: boolean
  teachers: OptionItem[]
  entryErrors: Record<number, string[]>
}>()

const emit = defineEmits<{
  add: []
  remove: [key: string]
  update: [key: string, name: string, value: unknown]
}>()

const teacherNames = computed(() => new Map(props.teachers.map((teacher) => [teacher.id, teacher.name])))

const duplicates = computed(() => duplicateEntryIndexes(props.row.entries, props.row.params))

function summaryOf(entry: EntryDraft): string {
  return entrySummary(entry.values, teacherNames.value)
}

async function remove(entry: EntryDraft) {
  try {
    await ElMessageBox.confirm('删除后该条不再生效，确定删除？', '删除条目', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  emit('remove', entry.key)
}
</script>

<style scoped>
.entry-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
}

.entry-empty {
  margin: 0;
  padding: 12px 14px;
  border-radius: 6px;
  background: #f7f8fa;
  border: 1px dashed #dcdfe6;
  font-size: 12px;
  color: var(--paiban-muted);
}

.entry-card {
  padding: 12px 14px;
  border-radius: 8px;
  background: #fbfcfe;
  border: 1px solid #eef1f6;
}

.entry-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.entry-index {
  font-size: 11px;
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
}

.entry-summary {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: var(--paiban-ink);
}

.entry-error {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
}

.entry-fields {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.field-teacher {
  width: 180px;
}

.field-day {
  width: 130px;
}

.field-period {
  width: 130px;
}

.add-entry {
  align-self: flex-start;
}
</style>
