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
        <div class="scope-controls">
          <el-switch
            :model-value="row.threshold?.globalEnabled ?? true"
            size="small"
            active-text="启用"
            :disabled="!editable"
            data-testid="constraint-threshold-global-enabled"
            @update:model-value="(value: string | number | boolean | undefined) => emit('set-global-enabled', Boolean(value))"
          />
          <el-select
            :model-value="hardChoice(row.threshold?.globalHard ?? null)"
            size="small"
            :disabled="!editable"
            class="hard-choice"
            data-testid="constraint-threshold-global-hard"
            @update:model-value="(value: unknown) => emit('set-global-hard', hardOf(value))"
          >
            <el-option value="inherit" :label="`继承类型（${row.hard ? '必须' : '尽量'}）`" />
            <el-option value="hard" label="必须" />
            <el-option value="soft" label="尽量" />
          </el-select>
          <template v-if="effectiveHard(row.threshold?.globalHard ?? null) === false">
            <el-checkbox
              :model-value="hasOverride(row.threshold?.globalWeight ?? null)"
              :disabled="!editable"
              data-testid="constraint-threshold-global-weight-override"
              @update:model-value="(value: string | number | boolean) => emit('set-global-weight', value ? row.weight : null)"
            >
              自定义重要程度
            </el-checkbox>
            <template v-if="hasOverride(row.threshold?.globalWeight ?? null)">
              <el-slider
                :model-value="effectiveWeight(row.threshold?.globalWeight ?? null)"
                :min="0"
                :max="100"
                size="small"
                class="weight-slider"
                :disabled="!editable"
                data-testid="constraint-threshold-global-weight"
                @update:model-value="(value: number | number[]) => emit('set-global-weight', Array.isArray(value) ? value[0] : value)"
              />
            </template>
            <span v-else class="row-hint">继承 {{ row.weight }}</span>
          </template>
          <span class="row-effective" data-testid="constraint-threshold-global-effective">
            实际：{{ effectiveText(row.threshold?.globalHard ?? null, row.threshold?.globalWeight ?? null) }}
          </span>
        </div>
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
      <div class="scope-controls">
        <el-switch
          :model-value="exception.enabled ?? true"
          size="small"
          active-text="启用"
          :disabled="!editable"
          data-testid="constraint-threshold-exception-enabled"
          @update:model-value="(value: string | number | boolean | undefined) => emit('set-exception-enabled', exception.key, Boolean(value))"
        />
        <el-select
          :model-value="hardChoice(exception.hard ?? null)"
          size="small"
          :disabled="!editable"
          class="hard-choice"
          data-testid="constraint-threshold-exception-hard"
          @update:model-value="(value: unknown) => emit('set-exception-hard', exception.key, hardOf(value))"
        >
          <el-option value="inherit" :label="`继承类型（${row.hard ? '必须' : '尽量'}）`" />
          <el-option value="hard" label="必须" />
          <el-option value="soft" label="尽量" />
        </el-select>
        <template v-if="effectiveHard(exception.hard ?? null) === false">
          <el-checkbox
            :model-value="hasOverride(exception.weight ?? null)"
            :disabled="!editable"
            data-testid="constraint-threshold-exception-weight-override"
            @update:model-value="(value: string | number | boolean) => emit('set-exception-weight', exception.key, value ? row.weight : null)"
          >
            自定义重要程度
          </el-checkbox>
          <template v-if="hasOverride(exception.weight ?? null)">
            <el-slider
              :model-value="effectiveWeight(exception.weight ?? null)"
              :min="0"
              :max="100"
              size="small"
              class="weight-slider"
              :disabled="!editable"
              data-testid="constraint-threshold-exception-weight"
              @update:model-value="(value: number | number[]) => emit('set-exception-weight', exception.key, Array.isArray(value) ? value[0] : value)"
            />
          </template>
        </template>
        <el-tag
          v-if="relaxes(exception)"
          size="small"
          type="warning"
          effect="plain"
          data-testid="constraint-threshold-relaxed"
        >
          比全局放宽
        </el-tag>
        <el-tag
          v-else-if="hasGlobal"
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
        <span class="row-effective">
          实际：{{ effectiveText(exception.hard ?? null, exception.weight ?? null) }}
        </span>
      </div>
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
import { ElButton, ElCheckbox, ElInputNumber, ElOption, ElSelect, ElSlider, ElSwitch, ElTag } from 'element-plus'
import { hasValue } from './paramValues'
import { thresholdEntries, thresholdShape } from './thresholdModel'
import type { OptionItem, ThresholdExceptionDraft, TypeDraft } from './types'

/**
 * The editor of one threshold type: one global default plus per-object exceptions.
 *
 * Both scopes carry their own controls (pause, must/prefer, soft weight) since ticket
 * 14/05. An exception replaces the global for its object, including the strength, so
 * the panel says which value actually runs and warns when the exception loosens the
 * global limit.
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
  'set-global-enabled': [value: boolean]
  'set-global-hard': [value: boolean | null]
  'set-global-weight': [value: number | null]
  'set-exception-enabled': [key: string, value: boolean]
  'set-exception-hard': [key: string, value: boolean | null]
  'set-exception-weight': [key: string, value: number | null]
}>()

const shape = computed(() => thresholdShape(props.row))
const hasGlobal = computed(() => hasValue(props.row.threshold?.globalValue))

/** 「教师 / 行政班 / 课程」 from the catalogue label, without the parenthetical. */
const objectNoun = computed(() =>
  (shape.value?.objectParam.label ?? '对象').replace(/（[^）]*）/g, ''),
)

function hardChoice(hard: boolean | null): string {
  return hard === null ? 'inherit' : hard ? 'hard' : 'soft'
}

function hardOf(value: unknown): boolean | null {
  if (value === 'inherit') {
    return null
  }
  return value === 'hard'
}

function effectiveHard(hard: boolean | null): boolean {
  return hard ?? props.row.hard
}

function effectiveWeight(weight: number | null): number {
  return weight ?? props.row.weight
}

function hasOverride(value: number | null): boolean {
  return value !== null && value !== undefined
}

function effectiveText(hard: boolean | null, weight: number | null): string {
  const strength = effectiveHard(hard) ? '必须' : '尽量'
  if (effectiveHard(hard)) {
    return strength
  }
  const inherited = hasOverride(weight) ? '' : '（继承）'
  return `${strength} · 重要程度 ${effectiveWeight(weight)}${inherited}`
}

/**
 * A looser exception: a larger number than the global, or hard global → soft exception.
 * The save flow asks for confirmation with the same comparison.
 */
function relaxes(exception: ThresholdExceptionDraft): boolean {
  if (!hasGlobal.value || exception.objectId === null || !hasValue(exception.value)) {
    return false
  }
  const globalHard = props.row.threshold?.globalHard ?? props.row.hard
  const hard = exception.hard ?? props.row.hard
  return Number(exception.value) > Number(props.row.threshold?.globalValue)
    || (globalHard && !hard)
}

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
  flex-wrap: wrap;
}

.scope-controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.hard-choice {
  width: 140px;
}

.weight-slider {
  width: 96px;
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

.row-effective {
  font-size: 12px;
  color: var(--el-color-primary, #4f6ef7);
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
