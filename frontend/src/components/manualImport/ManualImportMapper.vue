<template>
  <div class="mapper" data-testid="manual-mapper">
    <div class="row">
      <label class="field">
        <span>工作表</span>
        <el-select
          :model-value="mapping.sheetName || sheet?.name"
          data-testid="manual-sheet"
          @update:model-value="selectSheet"
        >
          <el-option v-for="item in sheets" :key="item.name" :label="item.name" :value="item.name" />
        </el-select>
      </label>
      <label class="field">
        <span>表头行</span>
        <el-select
          :model-value="mapping.headerRow ?? -1"
          data-testid="manual-header-row"
          @update:model-value="(value: number) => update({ headerRow: value })"
        >
          <el-option label="无表头（第一行就是数据）" :value="-1" />
          <el-option
            v-for="index in headerOptions"
            :key="index"
            :label="`第 ${index + 1} 行`"
            :value="index"
          />
        </el-select>
      </label>
      <label class="field">
        <span>课程列</span>
        <el-select
          :model-value="mapping.courseColumn ?? null"
          clearable
          data-testid="manual-course-column"
          @update:model-value="(value: number | null) => update({ courseColumn: value })"
        >
          <el-option v-for="column in columnOptions" :key="column.value" :label="column.label" :value="column.value" />
        </el-select>
      </label>
      <label class="field">
        <span>教师列</span>
        <el-select
          :model-value="mapping.teacherColumn ?? null"
          clearable
          data-testid="manual-teacher-column"
          @update:model-value="(value: number | null) => update({ teacherColumn: value })"
        >
          <el-option v-for="column in columnOptions" :key="column.value" :label="column.label" :value="column.value" />
        </el-select>
      </label>
    </div>

    <el-radio-group
      :model-value="mapping.keyColumn != null ? 'composite' : 'separate'"
      class="mode"
      data-testid="manual-key-mode"
      @update:model-value="switchMode"
    >
      <el-radio-button value="composite">复合键（班级|星期|节次）</el-radio-button>
      <el-radio-button value="separate">班级/星期/节次分列</el-radio-button>
    </el-radio-group>

    <div v-if="mapping.keyColumn != null" class="row">
      <label class="field">
        <span>复合键列</span>
        <el-select
          :model-value="mapping.keyColumn"
          data-testid="manual-key-column"
          @update:model-value="(value: number) => update({ keyColumn: value })"
        >
          <el-option v-for="column in columnOptions" :key="column.value" :label="column.label" :value="column.value" />
        </el-select>
      </label>
      <label class="field">
        <span>分隔符</span>
        <el-input
          :model-value="mapping.delimiter || '|'"
          data-testid="manual-delimiter"
          maxlength="2"
          @update:model-value="(value: string) => update({ delimiter: value })"
        />
      </label>
      <p class="hint">按分隔符拆成班级、星期、节次三部分。</p>
    </div>

    <div v-else class="row">
      <label class="field">
        <span>班级列</span>
        <el-select
          :model-value="mapping.classNameColumn ?? null"
          clearable
          data-testid="manual-class-column"
          @update:model-value="(value: number | null) => update({ classNameColumn: value })"
        >
          <el-option v-for="column in columnOptions" :key="column.value" :label="column.label" :value="column.value" />
        </el-select>
      </label>
      <label class="field">
        <span>星期列</span>
        <el-select
          :model-value="mapping.dayOfWeekColumn ?? null"
          clearable
          data-testid="manual-day-column"
          @update:model-value="(value: number | null) => update({ dayOfWeekColumn: value })"
        >
          <el-option v-for="column in columnOptions" :key="column.value" :label="column.label" :value="column.value" />
        </el-select>
      </label>
      <label class="field">
        <span>节次列</span>
        <el-select
          :model-value="mapping.periodNoColumn ?? null"
          clearable
          data-testid="manual-period-column"
          @update:model-value="(value: number | null) => update({ periodNoColumn: value })"
        >
          <el-option v-for="column in columnOptions" :key="column.value" :label="column.label" :value="column.value" />
        </el-select>
      </label>
    </div>

    <el-checkbox
      :model-value="mapping.ignoreBlankRows !== false"
      data-testid="manual-ignore-blank"
      @update:model-value="(value: boolean) => update({ ignoreBlankRows: value })"
    >
      忽略完全空白的行
    </el-checkbox>

    <el-table v-if="sheet" :data="tableRows" size="small" class="sample" max-height="240">
      <el-table-column
        v-for="(header, index) in displayHeaders"
        :key="index"
        :label="header"
        :min-width="90"
      >
        <template #default="{ row }">{{ row.cells[index] ?? '' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ManualMapping, ManualSheet } from '@/api/imports'

const props = defineProps<{ sheets: ManualSheet[]; modelValue: ManualMapping }>()
const emit = defineEmits<{ 'update:modelValue': [value: ManualMapping] }>()

const mapping = computed(() => props.modelValue)
const sheet = computed(() => props.sheets.find((item) => item.name === mapping.value.sheetName) ?? props.sheets[0])

const columnOptions = computed(() => {
  const headers = sheet.value?.headers ?? []
  return headers.map((label, value) => ({ label: `${label}（第${value + 1}列）`, value }))
})

const headerOptions = computed(() => {
  const count = Math.min(sheet.value?.rowCount ?? 0, 20)
  return Array.from({ length: count }, (_, index) => index)
})

const displayHeaders = computed(() => sheet.value?.headers ?? [])

const tableRows = computed(() => {
  const rows = sheet.value?.sampleRows ?? []
  return rows.slice(0, 6).map((cells, index) => ({ key: index, cells }))
})

function update(patch: Partial<ManualMapping>) {
  emit('update:modelValue', { ...props.modelValue, ...patch })
}

function selectSheet(name: string) {
  const next = props.sheets.find((item) => item.name === name)
  if (!next) return
  emit('update:modelValue', { ...next.suggestedMapping, sheetName: next.name })
}

function switchMode(mode: string) {
  if (mode === 'composite') {
    update({ keyColumn: mapping.value.keyColumn ?? 0, keyParts: ['className', 'dayOfWeek', 'periodNo'] })
    return
  }
  update({ keyColumn: null, keyParts: ['className', 'dayOfWeek', 'periodNo'] })
}
</script>

<style scoped>
.mapper {
  display: grid;
  gap: 12px;
}
.row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--paiban-muted);
}
.field :deep(.el-select) {
  width: 220px;
}
.hint {
  margin: 0;
  font-size: 12px;
  color: var(--paiban-muted);
}
.mode {
  margin-top: 4px;
}
.sample {
  width: 100%;
}
</style>
