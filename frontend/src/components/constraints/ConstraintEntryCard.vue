<template>
  <article
    class="entry-card"
    :class="{ 'is-paused': entry.enabled === false }"
    data-testid="constraint-entry"
    :data-index="index"
  >
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

    <div class="entry-controls" data-testid="constraint-entry-controls">
      <el-switch
        :model-value="entry.enabled ?? true"
        size="small"
        active-text="启用"
        :disabled="!editable"
        data-testid="constraint-entry-enabled"
        @update:model-value="(value: string | number | boolean | undefined) => emit('set-enabled', Boolean(value))"
      />
      <el-select
        :model-value="hardChoice"
        size="small"
        :disabled="!editable"
        class="hard-choice"
        data-testid="constraint-entry-hard"
        @update:model-value="onHardChoice"
      >
        <el-option value="inherit" :label="`继承类型（${row.hard ? '必须' : '尽量'}）`" />
        <el-option value="hard" label="必须" />
        <el-option value="soft" label="尽量" />
      </el-select>
      <template v-if="effectiveHard === false">
        <el-checkbox
          :model-value="entry.weight !== null && entry.weight !== undefined"
          :disabled="!editable"
          data-testid="constraint-entry-weight-override"
          @update:model-value="(value: string | number | boolean) => emit('set-weight', value ? row.weight : null)"
        >
          自定义重要程度
        </el-checkbox>
        <template v-if="entry.weight !== null && entry.weight !== undefined">
          <el-slider
            :model-value="entry.weight ?? row.weight"
            :min="0"
            :max="100"
            size="small"
            class="weight-slider"
            :disabled="!editable"
            data-testid="constraint-entry-weight"
            @update:model-value="(value: number | number[]) => emit('set-weight', Array.isArray(value) ? value[0] : value)"
          />
          <span class="entry-meta">{{ entry.weight }}</span>
        </template>
        <span v-else class="entry-meta" data-testid="constraint-entry-weight-inherited">
          继承 {{ row.weight }}
        </span>
      </template>
      <span class="entry-effective" data-testid="constraint-entry-effective">{{ effectiveText }}</span>
    </div>

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
        v-for="param in heads"
        :key="param.name"
        :param="param"
        :values="entry.values"
        :teachers="teachers"
        :courses="courses"
        :disabled="!editable"
        :test-id="`constraint-entry-${fieldTestId(param.name)}`"
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
import { ElButton, ElCheckbox, ElMessageBox, ElOption, ElSelect, ElSlider, ElSwitch } from 'element-plus'
import ConstraintSlotRow from './ConstraintSlotRow.vue'
import ParamField from './ParamField.vue'
import {
  allTargetLabel,
  declaresAllTarget,
  headParams,
  isSlotSetType,
} from './entryModel'
import { formatEntrySummary, slotCardSummary } from './entrySummary'
import { fieldTestId } from './paramValues'
import type { EntryDraft, OptionItem, TypeDraft } from './types'

/**
 * One editable requirement. Its controls (pause, must/prefer, weight) belong to the
 * whole requirement; a slot-grouped card stores several rows that all carry them.
 * Overriding nothing shows the type default in the same line, so the page never hides
 * which value is in force.
 */
const props = defineProps<{
  row: TypeDraft
  entry: EntryDraft
  index: number
  editable: boolean
  isJunior: boolean
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
  'set-enabled': [value: boolean]
  'set-hard': [value: boolean | null]
  'set-weight': [value: number | null]
}>()

const slotSet = computed(() => isSlotSetType(props.row))
const heads = computed(() => headParams(props.row))

const hardChoice = computed(() => {
  if (props.entry.hard == null) {
    return 'inherit'
  }
  return props.entry.hard ? 'hard' : 'soft'
})

const effectiveHard = computed(() => props.entry.hard ?? props.row.hard)
const effectiveWeight = computed(() => props.entry.weight ?? props.row.weight)

const effectiveText = computed(() => {
  const strength = effectiveHard.value
    ? props.isJunior ? '必须' : '硬约束'
    : props.isJunior ? '尽量' : '软约束'
  if (effectiveHard.value) {
    return `实际：${strength}`
  }
  const inherited = props.entry.weight == null ? '（继承）' : ''
  return `实际：${strength} · 重要程度 ${effectiveWeight.value}${inherited}`
})

function onHardChoice(value: string | number | boolean | undefined) {
  if (value === 'inherit') {
    emit('set-hard', null)
  } else if (value === 'hard') {
    emit('set-hard', true)
  } else if (value === 'soft') {
    emit('set-hard', false)
  }
}

const ctx = computed(() => ({
  teacherNames: new Map(props.teachers.map((teacher) => [teacher.id, teacher.name])),
  courseNames: new Map(props.courses.map((course) => [course.id, course.name])),
}))

const summary = computed(() =>
  slotSet.value
    ? slotCardSummary(props.row, props.entry, ctx.value)
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

.entry-card.is-paused {
  background: #fafafa;
}

.entry-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
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

.entry-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.hard-choice {
  width: 150px;
}

.weight-slider {
  width: 110px;
}

.entry-meta {
  font-size: 12px;
  color: var(--paiban-muted);
}

.entry-effective {
  font-size: 12px;
  color: var(--el-color-primary, #4f6ef7);
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
