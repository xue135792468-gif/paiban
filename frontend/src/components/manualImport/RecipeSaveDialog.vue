<template>
  <el-dialog
    :model-value="modelValue"
    title="保存为配方"
    width="480px"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <el-input
      :model-value="name"
      data-testid="recipe-name"
      placeholder="例如：八年级长表"
      maxlength="60"
      @update:model-value="(value: string) => emit('update:name', value)"
    />
    <div class="sample-pick">
      <span class="sample-label">样例文件</span>
      <input
        type="file"
        accept=".xlsx,.csv"
        data-testid="recipe-sample-file"
        @change="onSampleChange"
      >
      <span class="file-name">{{ sampleFileName }}</span>
    </div>
    <p class="hint">
      保存时服务端会用样例文件重跑一遍；数字与下方预览不一致时不会保存。
    </p>
    <el-alert
      v-if="differences.length"
      type="error"
      :closable="false"
      show-icon
      class="result"
      data-testid="recipe-save-differences"
    >
      <p>样例测试未通过，配方没有保存：</p>
      <ul>
        <li v-for="difference in differences" :key="difference.field">
          {{ difference.label }}：预期 {{ difference.expected }}，实际 {{ difference.actual }}
        </li>
      </ul>
    </el-alert>
    <template #footer>
      <el-button data-testid="cancel-save-recipe" @click="emit('update:modelValue', false)">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        data-testid="save-recipe-button"
        @click="save"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverDetails, serverMessage } from '@/api/errors'
import {
  saveImportRecipe,
  type ImportRecipe,
  type ManualMapping,
  type ManualPreview,
  type RecipeCountDifference,
} from '@/api/imports'

/**
 * 保存配方弹窗：收集名字与可选样例文件，把当前预览的映射/统计原样交给后端重跑。
 * 表单状态仍归父页面所有（v-model:name / v-model:sample-file），因为重新识别文件时
 * 父页面要能一键清空它们。
 */
const props = defineProps<{
  modelValue: boolean
  name: string
  sampleFile: File | null
  /** 正在导入的文件：没有单独选样例时，它就是样例。 */
  fallbackFile: File | null
  preview: ManualPreview | null
  mapping: ManualMapping | null
  /** Ticket 07 provenance，只在采纳了 AI 建议时有内容。 */
  aiSource: {
    aiSuggested?: boolean
    aiRawResponse?: string
    aiSuggestedMapping?: ManualMapping
  }
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  'update:name': [string]
  'update:sampleFile': [File | null]
  saved: [ImportRecipe]
}>()

const saving = ref(false)
const differences = ref<RecipeCountDifference[]>([])

const sampleFileName = computed(
  () => props.sampleFile?.name ?? props.fallbackFile?.name ?? '未选择',
)

function onSampleChange(event: Event) {
  const input = event.target as HTMLInputElement
  emit('update:sampleFile', input.files?.[0] ?? null)
}

/**
 * 保存配方：服务端会用样例文件重跑一遍，数字对不上就不保存。差异逐项展示，
 * 因为“哪一项不一致”比一句失败更能让人改对。
 */
async function save() {
  if (!props.preview || !props.mapping) return
  if (!props.name.trim()) {
    ElMessage.warning('请先给配方起个名字')
    return
  }
  saving.value = true
  differences.value = []
  try {
    const recipe = await saveImportRecipe(
      {
        name: props.name.trim(),
        layout: props.preview.layout,
        fingerprint: props.preview.fingerprint,
        columns: {
          ...props.mapping,
          delimiter: props.mapping.delimiter || '|',
          ignoreBlankRows: props.mapping.ignoreBlankRows !== false,
        },
        options: {
          delimiter: props.mapping.delimiter || '|',
          ignoreBlankRows: props.mapping.ignoreBlankRows !== false,
        },
        expected: {
          slotCount: props.preview.slotCount,
          lessonCount: props.preview.lessonCount,
          emptySlotCount: props.preview.emptySlotCount,
          teacherCount: props.preview.teacherCount,
          courseCount: props.preview.courseCount,
        },
        ...props.aiSource,
      },
      props.sampleFile ?? props.fallbackFile,
    )
    emit('update:modelValue', false)
    emit('saved', recipe)
    ElMessage.success('配方已保存，样例测试通过')
  } catch (error) {
    const details = serverDetails(error)
    differences.value = Array.isArray(details.differences)
      ? (details.differences as RecipeCountDifference[])
      : []
    ElMessage.error(serverMessage(error, '保存配方失败'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.sample-pick {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  font-size: 13px;
}
.sample-label {
  color: var(--paiban-muted);
}
.file-name {
  font-size: 13px;
  color: var(--paiban-ink);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--paiban-muted);
}
.result {
  margin-top: 12px;
}
.result ul {
  margin: 4px 0 0;
  padding-left: 18px;
}
</style>
