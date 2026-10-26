<template>
  <article class="entry-card" data-testid="constraint-entry" :data-index="index">
    <header class="entry-card-head">
      <span class="entry-index">#{{ index + 1 }}</span>
      <span class="entry-summary" data-testid="constraint-entry-summary">{{ summary }}</span>
      <el-button
        link
        type="danger"
        :disabled="!editable"
        data-testid="constraint-entry-remove"
        @click="confirmRemove"
      >
        删除
      </el-button>
    </header>

    <p
      v-for="(message, errorIndex) in serverErrors"
      :key="`error-${errorIndex}`"
      class="entry-error"
      data-testid="constraint-entry-error"
    >
      {{ message }}
    </p>
    <p
      v-for="(message, conflictIndex) in conflicts"
      :key="`conflict-${conflictIndex}`"
      class="entry-error"
      data-testid="constraint-entry-conflict"
    >
      {{ message }}
    </p>
    <p
      v-for="(message, duplicateIndex) in duplicates"
      :key="`duplicate-${duplicateIndex}`"
      class="entry-error"
      data-testid="constraint-entry-duplicate"
    >
      {{ message }}
    </p>

    <div v-if="slotSet" class="entry-fields" data-testid="constraint-entry-slot-set">
      <ParamField
        :param="teacherParam"
        :values="entry.values"
        :teachers="teachers"
        :courses="courses"
        :disabled="!editable"
        test-id="constraint-entry-teacher"
        @update="(name: string, value: unknown) => emit('update', name, value)"
      />
      <div class="slot-list">
        <ConstraintSlotRow
          v-for="(slot, slotIndex) in entry.slots ?? []"
          :key="slot.key"
          :index="slotIndex"
          :params="row.params"
          :draft="slot"
          :disabled="!editable"
          :removable="(entry.slots?.length ?? 0) > 1"
          :teachers="teachers"
          :courses="courses"
          @update="(field: string, value: unknown) => emit('update-slot', slot.key, field, value)"
          @remove="emit('remove-slot', slot.key)"
        />
        <el-button
          class="slot-add"
          :disabled="!editable"
          data-testid="constraint-entry-slot-add"
          @click="emit('add-slot')"
        >
          ＋ 添加时段
        </el-button>
      </div>
    </div>

    <div v-else class="entry-fields">
      <template v-for="param in row.params" :key="param.name">
        <el-checkbox
          v-if="!param.required"
          :model-value="declaresAllTarget(entry, param)"
          :disabled="!editable"
          data-testid="constraint-entry-all-target"
          @update:model-value="(value: string | number | boolean) => emit('all-target', param.name, Boolean(value))"
        >
          适用于{{ allTargetLabel(param) }}
        </el-checkbox>
        <ParamField
          :param="param"
          :values="entry.values"
          :teachers="teachers"
          :courses="courses"
          :disabled="!editable || declaresAllTarget(entry, param)"
          :test-id="`constraint-entry-${fieldTestId(param.name)}`"
          @update="(name: string, value: unknown) => emit('update', name, value)"
        />
      </template>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElCheckbox, ElMessageBox } from 'element-plus'
import ConstraintSlotRow from './ConstraintSlotRow.vue'
import ParamField from './ParamField.vue'
import { allTargetLabel, declaresAllTarget, isSlotSetType } from './entryModel'
import { formatEntrySummary, slotCardSummary } from './entrySummary'
import { fieldTestId } from './paramValues'
import type { EntryDraft, OptionItem, ParamDef, TypeDraft } from './types'

/**
 * One editable card of an entry-backed type: a one-line summary plus the object and
 * condition fields the catalogue declares. Enable/hard/weight stay type-level, so a
 * card never carries them.
 */
const props = defineProps<{
  row: TypeDraft
  entry: EntryDraft
  index: number
  editable: boolean
  teachers: OptionItem[]
  courses: OptionItem[]
  serverErrors: string[]
  duplicates: string[]
  conflicts: string[]
}>()

const emit = defineEmits<{
  update: [name: string, value: unknown]
  'all-target': [name: string, all: boolean]
  remove: []
  'add-slot': []
  'remove-slot': [slotKey: string]
  'update-slot': [slotKey: string, field: string, value: unknown]
}>()

const slotSet = computed(() => isSlotSetType(props.row))

const teacherParam = computed<ParamDef>(
  () =>
    props.row.params.find((param) => param.name === 'teacherId') ?? {
      name: 'teacherId',
      label: '教师',
      type: 'ID',
      required: true,
    },
)

const ctx = computed(() => ({
  teacherNames: new Map(props.teachers.map((teacher) => [teacher.id, teacher.name])),
  courseNames: new Map(props.courses.map((course) => [course.id, course.name])),
}))

const summary = computed(() =>
  slotSet.value
    ? slotCardSummary(props.entry, ctx.value)
    : formatEntrySummary(props.row.params, props.entry.values, ctx.value),
)

async function confirmRemove() {
  try {
    await ElMessageBox.confirm('删除后该条不再生效，确定删除？', '删除条目', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  emit('remove')
}
</script>

<style scoped>
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

.slot-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.slot-add {
  align-self: flex-start;
}
</style>
