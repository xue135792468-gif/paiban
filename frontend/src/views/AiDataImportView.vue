<template>
  <div class="ai-import-page">
    <div class="toolbar">
      <div>
        <h2>AI 数据导入</h2>
        <p class="subtitle">
          上传 Excel / CSV、粘贴文本或上传图片（图片导入需视觉模型），AI 解析后先预览，确认无误再写入系统。
        </p>
      </div>
      <el-tag v-if="!configured" type="warning" effect="plain">未配置 AI 供应商</el-tag>
      <el-tag v-else type="success" effect="plain">AI 已就绪</el-tag>
    </div>

    <el-alert
      v-if="!configured"
      title="请先在「设置中心 → AI 供应商」中配置供应商与模型，然后回到本页导入。"
      type="warning"
      show-icon
      :closable="false"
      class="notice"
    />

    <LastImportCard
      v-if="!preview && lastSession && !isAbandoned(lastSession.status)"
      :session="lastSession"
      :loading="restoring"
      @continue="continueLastSession"
    />

    <ImportSourcePanel
      v-if="!preview"
      v-model:input-mode="inputMode"
      v-model:text="text"
      :configured="configured"
      :vision-capable="visionCapable"
      :selected-target="selectedTarget"
      :can-parse="canParse"
      :parsing="parsing"
      :selected-file-name="selectedFile?.name ?? ''"
      :image-file-name="imageFile?.name ?? ''"
      @select-target="onSelectTarget"
      @excel-change="onExcelChange"
      @image-change="onImageChange"
      @parse="parse"
    />

    <template v-else>
      <el-alert
        v-if="suggestedTarget"
        data-testid="suggested-target-hint"
        type="info"
        show-icon
        :closable="false"
        class="suggested-target"
      >
        <template #title>
          <span>AI 认为这更像{{ targetTitle(suggestedTarget) }}，切换到{{ targetTitle(suggestedTarget) }}导入？</span>
          <el-button link type="primary" @click="switchToSuggestedTarget">切换目标</el-button>
        </template>
      </el-alert>

      <ImportProgressPanel
        v-if="isParsing"
        :progress="preview.progress"
        :stopping="stopping"
        :abandoning="abandoning"
        :kept-for-retry="isWorkbook"
        @stop="stopParsing"
        @abandon="abandonImport"
      />

      <el-card v-if="!isParsing" shadow="never">
        <template #header>
          <div class="card-head">
            <span class="card-title">{{ previewTitle }}</span>
            <div class="head-actions">
              <el-tag size="small" type="info" effect="plain" data-testid="preview-target">
                目标：{{ targetTitle(preview.targetType) }}
              </el-tag>
              <el-tag size="small" :type="statusType" data-testid="import-status">{{ statusLabel }}</el-tag>
              <el-button link type="primary" @click="reset">重新导入</el-button>
            </div>
          </div>
        </template>

        <PartialResultPanel
          v-if="isPartial"
          :rows="preview.rows"
          :failed-batches="preview.failedBatches ?? []"
          :progress="preview.progress"
          :retrying="retrying"
          :abandoning="abandoning"
          @retry="retryFailed"
          @abandon="abandonImport"
        />

        <el-table
          v-else-if="!isTimetable"
          data-testid="preview-rows-table"
          :data="preview.rows"
          border
          size="small"
          max-height="520"
        >
          <el-table-column type="index" label="#" width="52" />
          <el-table-column
            v-for="column in preview.columns"
            :key="column.field"
            :label="column.label + (column.required ? ' *' : '')"
            :min-width="column.field === 'name' ? 150 : 130"
          >
            <template #default="{ row }">
              <el-input
                v-if="editing && !isCommittedPreview"
                v-model="row.values[column.field]"
                size="small"
              />
              <span
                v-else
                class="cell"
                :class="{ 'low-confidence': row.lowConfidence?.includes(column.field) }"
                :title="confidenceTitle(row, column.field)"
              >
                {{ row.values[column.field] || '—' }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <TimetablePreviewGrid
          v-else
          :rows="preview.rows"
          :issues="previewIssues"
          :summary="preview.summary"
          :teacher-warnings="teacherWarnings"
        />

        <div v-if="!isTimetable && !isPartial" class="legend">
          <span class="swatch" /> 浅黄表示 AI 置信度低于
          {{ Math.round(preview.threshold * 100) }}%，建议重点核对
        </div>
      </el-card>

      <ImportFailureNotice
        v-if="isFailedPreview"
        :explanation="preview.explanation"
        :workbook="isWorkbook"
        :reparsing="reparsing"
        @replan="reparse"
      />

      <ImportBlockerActions :issues="previewIssues" @reload="reloadPreview" />

      <MissingPeriodsPanel
        v-if="isWorkbook && !isCommittedPreview && !isParsing && preview"
        :session-id="preview.sessionId"
        @applied="reloadPreview"
      />

      <WorkbookSummaryPanel
        v-if="preview.workbook"
        :workbook="preview.workbook"
        :warnings="preview.warnings"
        :teacher-warnings="isCommittedPreview ? [] : teacherWarnings"
        :tool-trace="preview.toolTrace"
        :columns="preview.columns"
      />

      <TimetableMappingStep
        v-if="showMappingStep"
        :session-id="preview.sessionId"
        :rows="preview.rows"
        :issues="mappableIssues"
        @updated="setPreview"
      />

      <CommitResultSummary
        v-if="committedCategories.length"
        :categories="committedCategories"
        :draft-id="commitResult?.draftId ?? null"
        :teacher-warnings="teacherWarnings"
      />

      <ImportCommittedAlert
        v-if="isCommittedPreview && !isWorkbook"
        :target-type="preview.targetType"
        :imported-count="importedCount"
        @reset="reset"
      />

      <ImportExplanationCard
        v-if="!isParsing"
        :explanation="preview.explanation"
        :mappings="preview.mappings"
      />

      <el-alert
        v-if="commitErrors.length"
        type="error"
        show-icon
        :closable="false"
        title="入库失败，请修正标红数据后重试"
        class="errors"
      >
        <div v-for="error in commitErrors" :key="error.row + '-' + error.field" class="error-line">
          第 {{ error.row }} 行 · {{ fieldLabel(error.field) }}：{{ error.message }}
        </div>
      </el-alert>

      <div v-if="!isFailedPreview && !isParsing" class="footer-actions">
        <template v-if="isTimetable">
          <el-button
            type="primary"
            :loading="committing"
            :disabled="commitDisabled"
            @click="commit"
          >
            {{ commitLabel }}
          </el-button>
          <el-button
            :loading="reparsing"
            :disabled="isCommittedPreview || isPartial"
            @click="reparse"
          >
            {{ isWorkbook ? '重新规划' : '打回重析' }}
          </el-button>
        </template>
        <template v-else-if="!editing">
          <el-button
            type="primary"
            :loading="committing"
            :disabled="isCommittedPreview"
            @click="commit"
          >
            全部接受并入库
          </el-button>
          <el-button :disabled="isCommittedPreview" @click="editing = true">手动微调</el-button>
          <el-button :loading="reparsing" :disabled="isCommittedPreview" @click="reparse">
            打回重析
          </el-button>
        </template>
        <template v-else>
          <el-button type="primary" :loading="committing" @click="commit">直接入库</el-button>
          <el-button :loading="continuing" @click="continueWithAi">AI 继续</el-button>
          <el-button @click="editing = false">取消微调</el-button>
          <el-button :loading="reparsing" @click="reparse">打回重析</el-button>
        </template>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type UploadFile } from 'element-plus'
import { AI_IMPORT_TIMEOUT_MS, http } from '@/api/http'
import { aiParseErrorMessage } from '@/api/errors'
import ImportSourcePanel from '@/components/aiImport/ImportSourcePanel.vue'
import ImportProgressPanel from '@/components/aiImport/ImportProgressPanel.vue'
import LastImportCard from '@/components/aiImport/LastImportCard.vue'
import PartialResultPanel from '@/components/aiImport/PartialResultPanel.vue'
import TimetableMappingStep from '@/components/aiImport/TimetableMappingStep.vue'
import WorkbookSummaryPanel from '@/components/aiImport/WorkbookSummaryPanel.vue'
import MissingPeriodsPanel from '@/components/aiImport/MissingPeriodsPanel.vue'
import CommitResultSummary from '@/components/aiImport/CommitResultSummary.vue'
import ImportBlockerActions from '@/components/aiImport/ImportBlockerActions.vue'
import ImportExplanationCard from '@/components/aiImport/ImportExplanationCard.vue'
import ImportCommittedAlert from '@/components/aiImport/ImportCommittedAlert.vue'
import ImportFailureNotice from '@/components/aiImport/ImportFailureNotice.vue'
import TimetablePreviewGrid from '@/components/aiImport/TimetablePreviewGrid.vue'
import { useImportSession } from '@/components/aiImport/useImportSession'
import { useImportTarget } from '@/components/aiImport/useImportTarget'
import { type ImportPreview, type PreviewRow } from '@/components/aiImport/timetablePreview'
import { targetTitle } from '@/components/aiImport/importTargets'
import { isAbandoned, isEditable } from '@/components/aiImport/importStatus'

const router = useRouter()
const route = useRoute()
const configured = ref(false)
const visionCapable = ref(false)
const inputMode = ref<'excel' | 'text' | 'image'>('excel')
const {
  selectedTarget,
  requestTarget,
  selectTarget,
  resetTarget,
  applySuggestedTarget,
  restoreTarget,
} = useImportTarget(route.query.target, inputMode)
const selectedFile = ref<File | null>(null)
const imageFile = ref<File | null>(null)
const text = ref('')
const parsing = ref(false)

const {
  preview,
  editing,
  committing,
  continuing,
  reparsing,
  stopping,
  retrying,
  abandoning,
  commitErrors,
  importedCount,
  commitResult,
  lastSession,
  isTimetable,
  isWorkbook,
  isParsing,
  isPartial,
  isCommittedPreview,
  isFailedPreview,
  previewIssues,
  blockingIssues,
  commitDisabled,
  commitLabel,
  statusLabel,
  statusType,
  setPreview,
  rememberSession,
  restore,
  dropSessionFromUrl,
  commit,
  continueWithAi,
  reparse,
  stopParsing,
  retryFailed,
  abandon,
  clear,
} = useImportSession()
const restoring = ref(false)

const mappableIssues = computed(() => previewIssues.value.filter((issue) => issue.row > 0))
/** What a commit wrote: from this page, or from a restored committed session. */
const committedCategories = computed(
  () => commitResult.value?.categories ?? preview.value?.workbook?.committed ?? [],
)
/**
 * The one aggregated list of suspicious teacher values. Both the preview panel and the
 * result card read it, so the counts they show can never disagree.
 */
const teacherWarnings = computed(
  () => commitResult.value?.teacherWarnings ?? preview.value?.teacherWarnings ?? [],
)
/** A partial parse shows the classes it did read, not a preview of a complete timetable. */
const previewTitle = computed(() => {
  const rows = preview.value?.rows.length ?? 0
  if (isPartial.value) return `部分结果（已解析 ${rows} 节课）`
  if (isWorkbook.value) {
    return `识别预览（${preview.value?.workbook?.sheets.length ?? 0} 张工作表，${rows} 节课）`
  }
  return `解析预览（${rows} 行）`
})
const suggestedTarget = computed(() => {
  const suggested = preview.value?.suggestedTarget
  return suggested && suggested !== preview.value?.targetType ? suggested : ''
})
const showMappingStep = computed(
  () =>
    isTimetable.value &&
    !isWorkbook.value &&
    isEditable(preview.value?.status) &&
    (mappableIssues.value.length > 0 || blockingIssues.value.length === 0),
)

const canParse = computed(() => {
  if (inputMode.value === 'excel') return selectedFile.value !== null
  if (inputMode.value === 'image') return imageFile.value !== null
  return text.value.trim().length > 0
})

onMounted(async () => {
  const session = route.query.session
  if (typeof session === 'string' && session) {
    await restoreFrom(session)
  }
  try {
    const { data } = await http.get('/ai/status')
    configured.value = Boolean(data?.tasks?.data_import?.configured)
    visionCapable.value = Boolean(data?.tasks?.data_import?.visionCapable)
  } catch {
    configured.value = false
  }
})

/** Restores a session and lines the target picker up with what was restored. */
async function restoreFrom(sessionId: string) {
  restoring.value = true
  try {
    const outcome = await restore(sessionId)
    if (outcome === 'restored' && preview.value) {
      restoreTarget(preview.value.targetType)
    }
  } finally {
    restoring.value = false
  }
}

/** Re-reads the session so a newly generated 作息 clears the blocker. */
async function reloadPreview() {
  if (!preview.value) return
  await restoreFrom(String(preview.value.sessionId))
}

async function continueLastSession() {
  if (!lastSession.value) return
  await restoreFrom(String(lastSession.value.id))
}

function onSelectTarget(code: string) {
  selectTarget(code)
  commitErrors.value = []
}

function onExcelChange(file: UploadFile) {
  selectedFile.value = file.raw ?? null
  commitErrors.value = []
}

function onImageChange(file: UploadFile) {
  imageFile.value = file.raw ?? null
  commitErrors.value = []
}

async function parse() {
  if (!configured.value) return
  dropSessionFromUrl()
  parsing.value = true
  commitErrors.value = []
  // Excel defaults to the whole-workbook path; the target card is an explicit
  // single-target choice. Text and images never carry the workbook shape.
  const config = { params: { target: requestTarget.value }, timeout: AI_IMPORT_TIMEOUT_MS }
  try {
    let response
    if (inputMode.value === 'excel') {
      const form = new FormData()
      form.append('file', selectedFile.value as File)
      response = await http.post<ImportPreview>('/ai/import/excel', form, config)
    } else if (inputMode.value === 'image') {
      const form = new FormData()
      form.append('file', imageFile.value as File)
      response = await http.post<ImportPreview>('/ai/import/image', form, config)
    } else {
      response = await http.post<ImportPreview>('/ai/import/text', { text: text.value }, config)
    }
    setPreview(response.data)
    editing.value = false
    rememberSession(response.data, uploadedFileName())
    await router.replace({
      query: {
        ...route.query,
        session: String(response.data.sessionId),
        target: response.data.targetType,
      },
    })
  } catch (error) {
    ElMessage.error(aiParseErrorMessage(error))
  } finally {
    parsing.value = false
  }
}

/** The label the 「上次导入」 card shows for this parse. */
function uploadedFileName(): string {
  if (inputMode.value === 'excel') return selectedFile.value?.name ?? ''
  if (inputMode.value === 'image') return imageFile.value?.name ?? ''
  return '粘贴文本'
}

/** Switches the target the AI suggested, keeping the chosen file for a re-parse. */
function switchToSuggestedTarget() {
  if (!suggestedTarget.value) return
  applySuggestedTarget(suggestedTarget.value)
  clear()
  ElMessage.info(`已切换到${targetTitle(selectedTarget.value)}，请重新解析`)
}

function reset() {
  clear()
  resetTarget()
  selectedFile.value = null
  imageFile.value = null
  text.value = ''
}

/** Abandoning drops the import entirely, so the file inputs are cleared too. */
async function abandonImport() {
  const done = await abandon()
  if (!done) return
  selectedFile.value = null
  imageFile.value = null
  text.value = ''
}

function confidenceTitle(row: PreviewRow, field: string) {
  const value = row.confidence?.[field]
  return value === undefined ? undefined : `AI 置信度 ${Math.round(value * 100)}%`
}

function fieldLabel(field: string) {
  return preview.value?.columns.find((column) => column.field === field)?.label ?? field
}
</script>

<style scoped>
.ai-import-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.toolbar h2 {
  margin: 0;
}

.subtitle {
  margin: 6px 0 0;
  color: #6b7280;
}

.notice,
.suggested-target {
  margin-bottom: 4px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-weight: 600;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cell {
  display: inline-block;
  padding: 2px 6px;
  border-radius: 3px;
}

.low-confidence {
  background: #fff7cc;
}

.legend {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  color: #6b7280;
  font-size: 12px;
}

.swatch {
  width: 14px;
  height: 14px;
  border: 1px solid #f0e0a0;
  background: #fff7cc;
  border-radius: 3px;
}

.errors {
  margin-top: 4px;
}

.error-line {
  margin-top: 4px;
  font-size: 13px;
}

.footer-actions {
  display: flex;
  gap: 12px;
}
</style>
