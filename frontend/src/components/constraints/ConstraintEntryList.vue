<template>
  <div class="entry-list" data-testid="constraint-entry-list">
    <p v-if="row.entries.length === 0" class="entry-empty" data-testid="constraint-entry-empty">
      空列表＝该类型未配置（不会自动补一条规则）
    </p>

    <ConstraintEntryCard
      v-for="(entry, index) in row.entries"
      :key="entry.key"
      :row="row"
      :entry="entry"
      :index="index"
      :editable="editable"
      :is-junior="isJunior"
      :teachers="teachers"
      :courses="courses"
      :server-errors="entryErrorsOf(row, index, entryErrors)"
      :duplicates="duplicates[index] ?? []"
      :conflicts="conflicts[index] ?? []"
      @update="(name: string, value: unknown) => emit('update', entry.key, name, value)"
      @all-target="(name: string, all: boolean) => emit('all-target', entry.key, name, all)"
      @remove="emit('remove', entry.key)"
      @add-slot="emit('add-slot', entry.key)"
      @remove-slot="(slotKey: string) => emit('remove-slot', entry.key, slotKey)"
      @update-slot="(slotKey: string, field: string, value: unknown) => emit('update-slot', entry.key, slotKey, field, value)"
      @set-enabled="(value: boolean) => emit('set-entry-enabled', entry.key, value)"
      @set-hard="(value: boolean | null) => emit('set-entry-hard', entry.key, value)"
      @set-weight="(value: number | null) => emit('set-entry-weight', entry.key, value)"
    />

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
import { ElButton } from 'element-plus'
import ConstraintEntryCard from './ConstraintEntryCard.vue'
import { duplicateMessages, entryErrorsOf, scalarConflictsByCard } from './entryChecks'
import type { OptionItem, TypeDraft } from './types'

/**
 * The entry list of one constraint type: one card per object/condition, an explicit
 * empty state, and the bottom "add" action. Duplicates and server errors are located
 * per card here, so the view never has to know how entries map to request positions.
 */
const props = defineProps<{
  row: TypeDraft
  editable: boolean
  isJunior: boolean
  teachers: OptionItem[]
  courses: OptionItem[]
  entryErrors: Record<number, string[]>
}>()

const emit = defineEmits<{
  add: []
  remove: [key: string]
  update: [key: string, name: string, value: unknown]
  'all-target': [key: string, name: string, all: boolean]
  'add-slot': [key: string]
  'remove-slot': [key: string, slotKey: string]
  'update-slot': [key: string, slotKey: string, field: string, value: unknown]
  'set-entry-enabled': [key: string, value: boolean]
  'set-entry-hard': [key: string, value: boolean | null]
  'set-entry-weight': [key: string, value: number | null]
}>()

const duplicates = computed(() => duplicateMessages(props.row))

// 互斥条件（同一课程两个教室类型等）也落在卡片上，用户一眼看到该改哪一条。
const conflicts = computed(() =>
  scalarConflictsByCard(props.row, {
    teacherNames: new Map(),
    courseNames: new Map(props.courses.map((course) => [course.id, course.name])),
  }),
)
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

.add-entry {
  align-self: flex-start;
}
</style>
