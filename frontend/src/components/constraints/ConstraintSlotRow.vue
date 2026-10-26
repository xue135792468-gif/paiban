<template>
  <div class="slot-row" data-testid="constraint-entry-slot" :data-index="index">
    <span class="slot-index">{{ index + 1 }}</span>
    <ParamField
      :param="dayParam"
      :values="slotValues"
      :teachers="teachers"
      :courses="courses"
      :disabled="disabled"
      test-id="constraint-entry-slot-day"
      @update="(name: string, value: unknown) => emit('update', name, value)"
    />
    <ParamField
      v-if="periodParam"
      :param="periodParam"
      :values="slotValues"
      :teachers="teachers"
      :courses="courses"
      :disabled="disabled"
      test-id="constraint-entry-slot-period"
      @update="(name: string, value: unknown) => emit('update', name, value)"
    />
    <el-button
      link
      type="danger"
      :disabled="disabled || removable === false"
      data-testid="constraint-entry-slot-remove"
      @click="emit('remove')"
    >
      移除
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElButton } from 'element-plus'
import ParamField from './ParamField.vue'
import type { OptionItem, ParamDef, SlotDraft } from './types'

/**
 * One weekday/period pair inside a preference-slot card. Each row becomes its own
 * entry on the server (one slot per entry), so this is the finest editable unit.
 */
const props = defineProps<{
  params: ParamDef[]
  draft: SlotDraft
  index: number
  disabled: boolean
  removable?: boolean
  teachers: OptionItem[]
  courses: OptionItem[]
}>()

const emit = defineEmits<{
  update: [field: string, value: unknown]
  remove: []
}>()

const dayParam = computed<ParamDef>(
  () =>
    props.params.find((param) => param.type === 'DAY') ?? {
      name: 'dayOfWeek',
      label: '星期',
      type: 'DAY',
      required: true,
    },
)

const periodParam = computed<ParamDef | null>(
  () => props.params.find((param) => param.type === 'PERIOD') ?? null,
)

const slotValues = computed(() => ({
  dayOfWeek: props.draft.dayOfWeek,
  periodNo: props.draft.periodNo,
}))
</script>

<style scoped>
.slot-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.slot-index {
  font-size: 11px;
  color: #9ca3af;
  font-variant-numeric: tabular-nums;
}
</style>
