<template>
  <div class="template-bar" data-testid="constraint-template-bar">
    <div class="template-controls">
      <el-button
        data-testid="constraint-template-save"
        :disabled="!semesterId"
        :loading="saving"
        @click="onSaveAsTemplate"
      >
        存为模板
      </el-button>
      <el-select
        v-model="selectedId"
        data-testid="constraint-template-select"
        placeholder="选择模板"
        clearable
        style="width: 260px"
      >
        <el-option
          v-for="item in templates"
          :key="item.id"
          :label="templateOptionLabel(item)"
          :value="item.id"
        />
      </el-select>
      <el-button
        data-testid="constraint-template-apply"
        :disabled="!semesterId || !selectedId"
        :loading="applying || previewing"
        @click="onApply"
      >
        套用模板
      </el-button>
      <el-button
        data-testid="constraint-template-delete"
        type="danger"
        plain
        :disabled="!selectedId"
        :loading="deleting"
        @click="onDeleteTemplate"
      >
        删除模板
      </el-button>
    </div>

    <el-alert
      v-if="result"
      data-testid="constraint-template-result"
      type="success"
      :closable="true"
      show-icon
      class="template-alert"
      @close="clearReport"
    >
      <template #title>模板已套用</template>
      <p class="template-copy">{{ resultText }}</p>
    </el-alert>

    <el-alert
      v-if="blockedText"
      data-testid="constraint-template-error"
      type="error"
      :closable="false"
      show-icon
      class="template-alert"
    >
      <template #title>模板未能套用，当前配置未改动</template>
      <p class="template-copy">{{ blockedText }}</p>
    </el-alert>

    <!-- The apply is refused until every reference has a home in this semester. -->
    <el-dialog
      v-model="dialogVisible"
      title="模板中的引用需要处理"
      width="680px"
    >
      <div data-testid="constraint-template-issues">
        <p class="template-copy">
          模板来自其他学期。行政班只能重新绑定到当前学期的班级；教师与课程是全局资产，仍然有效时原样保留。
        </p>
        <div
          v-for="(row, index) in issueRows"
          :key="row.key"
          class="issue-row"
          :data-testid="`constraint-template-issue-${row.key}`"
        >
          <div class="issue-head">
            <span class="issue-type">{{ row.typeLabel }}</span>
            <span class="issue-where">{{ row.location }}</span>
            <span class="issue-field">「{{ row.fieldLabel }}」</span>
            <span class="issue-object">{{ row.objectLabel }}</span>
          </div>
          <p class="issue-reason">{{ row.reason }}</p>
          <el-select
            v-if="row.rebindable"
            v-model="selections[row.key]"
            :data-testid="`constraint-template-rebind-${index}`"
            placeholder="选择当前学期的班级"
            style="width: 100%"
          >
            <el-option
              v-for="item in classes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <p v-else class="issue-reason">该对象无法通过套用修复，请先在系统中恢复或另存模板。</p>
        </div>
        <p data-testid="constraint-template-issues-summary" class="template-copy">
          {{ blockingText }}
        </p>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-testid="constraint-template-issues-apply"
          :disabled="blockingCount > 0"
          :loading="applying"
          @click="onReboundApply"
        >
          按所选班级重新套用
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useConstraintTemplates } from './useConstraintTemplates'
import {
  applyResultText,
  blockingIssues,
  blockingSummary,
  classMappingOf,
  issueRows as rowsOf,
  previewBlockedReason,
  templateOptionLabel,
} from './templateModel'
import type { OptionItem } from './types'

const props = defineProps<{
  semesterId: number | null
  /** The classes of the selected semester: the only rebinding targets. */
  classes: OptionItem[]
  /** Reloads the page configuration after a successful apply. */
  reload: () => Promise<void>
}>()

const {
  templates,
  selectedId,
  selected,
  saving,
  applying,
  previewing,
  deleting,
  result,
  errorMessage,
  issues,
  preview,
  load,
  saveAs,
  runPreview,
  apply,
  remove,
  clearReport,
} = useConstraintTemplates({
  semesterId: computed(() => props.semesterId),
  reload: () => props.reload(),
})

const dialogVisible = ref(false)
const selections = ref<Record<string, number | null>>({})

const issueRows = computed(() => rowsOf(issues.value))
const blockingText = computed(() => blockingSummary(issues.value, selections.value))
const blockingCount = computed(() => blockingIssues(issues.value, selections.value).length)
const resultText = computed(() =>
  result.value ? applyResultText(result.value.name, result.value.counts) : '',
)
const blockedText = computed(() => previewBlockedReason(preview.value) ?? errorMessage.value)

// A fresh template or semester starts with an empty mapping: never reuse a class
// choice from a previous refusal.
watch(
  () => [issues.value, selectedId.value, props.semesterId],
  () => {
    selections.value = {}
  },
)

watch(
  () => props.semesterId,
  () => clearReport(),
)

void load()

async function onSaveAsTemplate() {
  if (!props.semesterId) {
    ElMessage.warning('请先选择学期')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请输入模板名称', '存为模板', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '模板名称不能为空',
      inputPlaceholder: '例如：标准学期模板',
    })
    if (!value || !value.trim()) {
      return
    }
    if (await saveAs(value.trim())) {
      ElMessage.success('模板保存成功')
    }
  } catch (e: unknown) {
    if (e !== 'cancel') {
      ElMessage.error('保存模板失败')
    }
  }
}

/**
 * Two steps on purpose: the dry run first, so an apply that cannot work is reported
 * before anything is replaced. An invalid class reference opens the rebinding dialog
 * instead of failing silently or guessing a same-named class.
 */
async function onApply() {
  const run = await runPreview()
  if (!run) {
    return
  }
  if (previewBlockedReason(run)) {
    ElMessage.error('模板无法套用到当前学期')
    return
  }
  if (run.invalidReferences.length > 0) {
    dialogVisible.value = true
    return
  }
  await confirmAndApply({})
}

/**
 * The dialog *is* the confirmation: the user has just picked a class for every row, so
 * the retry goes straight through without a second prompt.
 */
async function onReboundApply() {
  const mapping = classMappingOf(issues.value, selections.value)
  dialogVisible.value = false
  const applied = await apply(mapping)
  if (applied) {
    ElMessage.success('模板已套用')
  }
}

async function confirmAndApply(mapping: Record<string, number>) {
  const name = selected.value?.name ?? '所选模板'
  const counts = preview.value
    ? `将恢复 ${preview.value.configCount} 类配置、${preview.value.entryCount} 条条目，`
    : ''
  try {
    await ElMessageBox.confirm(
      `确定把模板「${name}」套用到当前学期吗？${counts}当前学期的约束配置会被覆盖。`,
      '套用模板',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const applied = await apply(mapping)
  if (applied) {
    ElMessage.success('模板已套用')
  }
}

async function onDeleteTemplate() {
  if (!selectedId.value) {
    ElMessage.warning('请选择要删除的模板')
    return
  }
  const name = selected.value?.name ?? '所选模板'
  try {
    await ElMessageBox.confirm(`确定要删除模板「${name}」吗？此操作不可撤销。`, '删除模板', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  if (await remove()) {
    ElMessage.success('模板已删除')
  }
}
</script>

<style scoped>
.template-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.template-alert {
  margin-top: 12px;
}

.template-copy {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.issue-row {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 12px;
  margin-top: 10px;
}

.issue-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
}

.issue-type {
  font-weight: 600;
}

.issue-where,
.issue-field {
  color: var(--paiban-muted, #909399);
}

.issue-object {
  color: var(--el-color-danger);
}

.issue-reason {
  margin: 6px 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--paiban-muted, #909399);
}
</style>
