<template>
  <div class="threshold-panel" data-testid="constraint-threshold-panel">
    <div class="threshold-global" data-testid="constraint-threshold-global">
      <span class="row-label">全局默认</span>
      <template v-if="hasGlobal">
        <el-input-number
          :model-value="row.threshold?.globalValue ?? null"
          :min="shape?.valueParam.min ?? 0"
          :max="shape?.valueParam.max ?? 100"
          controls-position="right"
          :disabled="!editable"
          data-testid="constraint-threshold-global-value"
          @update:model-value="(value: number | null | undefined) => emit('set-global', value ?? null)"
        />
        <span class="row-unit">{{ shape?.valueParam.label }}</span>
        <el-button
          v-if="editable"
          link
          type="danger"
          data-testid="constraint-threshold-global-remove"
          @click="emit('set-global', null)"
        >
          删除
        </el-button>
      </template>
      <template v-else>
        <span class="row-hint" data-testid="constraint-threshold-global-empty">未设置全局默认</span>
        <el-button
          v-if="editable"
          link
          type="primary"
          data-testid="constraint-threshold-add-global"
          @click="emit('add-global')"
        >
          设置全局默认
        </el-button>
      </template>
    </div>

    <p
      v-if="!hasGlobal"
      class="panel-hint"
      data-testid="constraint-threshold-no-global-hint"
    >
      没有全局默认时，未在下面列出的{{ objectNoun }}不设上限。
    </p>

    <p
      v-if="row.threshold && row.threshold.exceptions.length === 0"
      class="threshold-empty"
      data-testid="constraint-threshold-empty"
    >
      还没有对象例外：该类型当前只按上面的全局默认限制。
    </p>

    <div
      v-for="(exception, index) in row.threshold?.exceptions ?? []"
      :key="exception.key"
      class="threshold-row"
      data-testid="constraint-threshold-exception"
    >
      <span class="row-index">{{ index + 1 }}</span>
      <el-select
        :model-value="exception.objectId"
        filterable
        clearable
        :placeholder="`请选择${objectNoun}`"
        :disabled="!editable"
        data-testid="constraint-threshold-object"
        class="row-object"
        @update:model-value="(value: unknown) => emit('set-exception-object', exception.key, value)"
      >
        <el-option
          v-for="option in objectOptions"
          :key="option.id"
          :label="option.name"
          :value="option.id"
        />
      </el-select>
      <el-input-number
        :model-value="exception.value"
        :min="shape?.valueParam.min ?? 0"
        :max="shape?.valueParam.max ?? 100"
        controls-position="right"
        :disabled="!editable"
        data-testid="constraint-threshold-value"
        @update:model-value="(value: number | null | undefined) => emit('set-exception-value', exception.key, value ?? null)"
      />
      <el-tag
        v-if="hasGlobal"
        size="small"
        type="warning"
        effect="plain"
        data-testid="constraint-threshold-override"
      >
        覆盖全局 {{ row.threshold?.globalValue }}
      </el-tag>
      <span v-else class="row-hint" data-testid="constraint-threshold-no-global-mark">
        仅该{{ objectNoun }}
      </span>
      <el-button
        v-if="editable"
        link
        type="danger"
        data-testid="constraint-threshold-remove"
        @click="emit('remove-exception', exception.key)"
      >
        删除
      </el-button>
    </div>

    <p
      v-for="(message, index) in messages"
      :key="`error-${index}`"
      class="row-error"
      data-testid="constraint-threshold-error"
    >
      {{ message }}
    </p>

    <el-button
      v-if="editable"
      class="add-exception"
      data-testid="constraint-threshold-add-exception"
      @click="emit('add-exception')"
    >
      ＋ 添加例外
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElButton, ElInputNumber, ElOption, ElSelect, ElTag } from 'element-plus'
import { hasValue } from './paramValues'
import { thresholdEntries, thresholdShape } from './thresholdModel'
import type { OptionItem, TypeDraft } from './types'

/**
 * The editor of one threshold type: one global default plus per-object exceptions.
 *
 * The two rows are not symmetric on purpose. The global default applies to every
 * object the exceptions do not name, so it is shown once, at the top; an exception
 * replaces it for a single object, so each exception row carries the value it
 * overrides. Without a global default the panel says out loud that the unlisted
 * objects are unlimited — the server does not invent a default either.
 */
const props = defineProps<{
  row: TypeDraft
  editable: boolean
  objectOptions: OptionItem[]
  /** Server messages located by request position, as the view receives them. */
  errors: Record<number, string[]>
}>()

const emit = defineEmits<{
  'set-global': [value: number | null]
  'add-global': []
  'add-exception': []
  'remove-exception': [key: string]
  'set-exception-object': [key: string, value: unknown]
  'set-exception-value': [key: string, value: unknown]
}>()

const shape = computed(() => thresholdShape(props.row))
const hasGlobal = computed(() => hasValue(props.row.threshold?.globalValue))

/** 「教师 / 行政班 / 课程」 from the catalogue label, without the parenthetical. */
const objectNoun = computed(() =>
  (shape.value?.objectParam.label ?? '对象').replace(/（[^）]*）/g, ''),
)

/**
 * Server messages for the whole type, located on the row they came from. The response
 * counts request positions, so the mapping goes through the same `thresholdEntries`
 * the payload used: 0 is the global default, i+1 is the i-th exception.
 */
const messages = computed(() => {
  const byCard = new Map<number, string[]>()
  thresholdEntries(props.row).forEach((entry) => {
    ;(props.errors[entry.position] ?? []).forEach((message) => {
      const bucket = byCard.get(entry.cardIndex) ?? []
      bucket.push(message)
      byCard.set(entry.cardIndex, bucket)
    })
  })
  const collected: string[] = []
  byCard.forEach((bucket) => collected.push(...bucket))
  return collected
})
</script>

<style scoped>
.threshold-panel {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.threshold-global,
.threshold-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fbfcfe;
  border: 1px solid #eef1f6;
}

.row-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--paiban-ink);
  min-width: 64px;
}

.row-index {
  font-size: 12px;
  color: var(--paiban-muted);
  min-width: 14px;
}

.row-unit,
.row-hint {
  font-size: 12px;
  color: var(--paiban-muted);
}

.row-object {
  width: 180px;
}

.threshold-empty {
  margin: 0;
  padding: 12px 14px;
  border-radius: 6px;
  background: #f7f8fa;
  border: 1px dashed #dcdfe6;
  font-size: 12px;
  color: var(--paiban-muted);
}

.panel-hint {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f7f8fa;
  border: 1px dashed #dcdfe6;
  font-size: 12px;
  color: var(--paiban-muted);
}

.row-error {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger, #f56c6c);
  line-height: 1.5;
}

.add-exception {
  align-self: flex-start;
}
</style>
