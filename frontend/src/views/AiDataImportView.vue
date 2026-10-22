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

    <el-card v-if="!preview" shadow="never">
      <template #header><span class="card-title">选择导入目标</span></template>
      <ImportTargetPicker
        :targets="IMPORT_TARGETS"
        :selected="selectedTarget"
        @select="onSelectTarget"
      />
    </el-card>

    <el-card v-if="!preview" shadow="never">
      <el-tabs v-model="inputMode">
        <el-tab-pane label="Excel / CSV" name="excel">
          <el-upload
            drag
            :auto-upload="false"
            :show-file-list="false"
            accept=".xlsx,.xls,.csv"
            :on-change="onExcelChange"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">把文件拖到这里，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">{{ currentTarget.excelHint }}</div>
            </template>
          </el-upload>
          <div v-if="selectedFile" class="file-hint">已选择：{{ selectedFile.name }}</div>
        </el-tab-pane>

        <el-tab-pane label="粘贴文本" name="text">
          <el-input
            v-model="text"
            type="textarea"
            :rows="9"
            :placeholder="currentTarget.textPlaceholder"
          />
          <div class="hint">可以直接从微信聊天记录、通知里复制粘贴。</div>
        </el-tab-pane>

        <el-tab-pane label="上传图片" name="image" :disabled="!visionCapable || selectedTarget !== 'teacher'">
          <template #label>
            <span data-testid="image-tab">上传图片</span>
          </template>
          <el-upload
            drag
            :auto-upload="false"
            :show-file-list="false"
            accept="image/png,image/jpeg,image/webp"
            :on-change="onImageChange"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">上传课表或名单照片，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">需要所选模型支持视觉（vision_capable）。</div>
            </template>
          </el-upload>
          <div v-if="imageFile" class="file-hint">已选择：{{ imageFile.name }}</div>
        </el-tab-pane>
      </el-tabs>

      <el-alert
        v-if="configured && !visionCapable"
        data-testid="vision-warning"
        title="当前模型不支持图片，请在设置中换用视觉模型"
        type="warning"
        show-icon
        :closable="false"
        class="vision-warning"
      />

      <div class="parse-actions">
        <el-button type="primary" :loading="parsing" :disabled="!configured || !canParse" @click="parse">
          开始解析
        </el-button>
        <span class="hint">AI 只生成预览，点击「入库」才会写入数据。</span>
      </div>

      <el-alert
        v-if="parsing"
        data-testid="parse-progress"
        title="AI 正在解析，大表会按班分批，可能需要几分钟"
        type="info"
        show-icon
        :closable="false"
        class="parse-progress"
      />
    </el-card>

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

      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span class="card-title">解析预览（{{ preview.rows.length }} 行）</span>
            <div class="head-actions">
              <el-tag size="small" type="info" effect="plain" data-testid="preview-target">
                目标：{{ targetTitle(preview.targetType) }}
              </el-tag>
              <el-tag size="small" :type="statusType">{{ statusLabel }}</el-tag>
              <el-button link type="primary" @click="reset">重新导入</el-button>
            </div>
          </div>
        </template>

        <el-table
          v-if="!isTimetable"
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
                v-if="editing && preview.status !== 'COMMITTED'"
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
        />

        <div v-if="!isTimetable" class="legend">
          <span class="swatch" /> 浅黄表示 AI 置信度低于
          {{ Math.round(preview.threshold * 100) }}%，建议重点核对
        </div>
      </el-card>

      <el-alert
        v-for="issue in blockingIssues"
        :key="issue.field + '-' + issue.message"
        data-testid="blocking-issue"
        type="warning"
        show-icon
        :closable="false"
        :title="issue.message || '当前数据无法入库，请先处理预览中的问题'"
      />

      <TimetableMappingStep
        v-if="showMappingStep"
        :session-id="preview.sessionId"
        :rows="preview.rows"
        :issues="mappableIssues"
        @updated="setPreview"
      />

      <el-alert
        v-if="preview.status === 'COMMITTED'"
        type="success"
        show-icon
        :closable="false"
        class="result-alert"
      >
        <template #title>
          已成功入库 {{ importedCount }} 条{{ targetTitle(preview.targetType) }}数据
        </template>
        <div class="result-actions">
          <el-button
            v-if="preview.targetType === 'teacher'"
            link
            type="primary"
            @click="goTeachers"
          >
            查看教师
          </el-button>
          <el-button link type="primary" @click="reset">继续导入</el-button>
        </div>
      </el-alert>

      <el-card shadow="never" class="explain-card">
        <template #header><span class="card-title">AI 说明</span></template>
        <p class="explanation">{{ preview.explanation || 'AI 未提供额外说明。' }}</p>
        <el-table :data="preview.mappings" size="small" border>
          <el-table-column prop="source" label="原始列 / 片段" min-width="160" />
          <el-table-column prop="target" label="系统字段" width="120" />
          <el-table-column prop="note" label="映射说明" min-width="200" />
        </el-table>
      </el-card>

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

      <div class="footer-actions">
        <template v-if="isTimetable">
          <el-button
            type="primary"
            :loading="committing"
            :disabled="commitDisabled"
            @click="commit"
          >
            {{ commitLabel }}
          </el-button>
          <el-button :loading="reparsing" :disabled="preview.status === 'COMMITTED'" @click="reparse">
            打回重析
          </el-button>
        </template>
        <template v-else-if="!editing">
          <el-button
            type="primary"
            :loading="committing"
            :disabled="preview.status === 'COMMITTED'"
            @click="commit"
          >
            全部接受并入库
          </el-button>
          <el-button :disabled="preview.status === 'COMMITTED'" @click="editing = true">手动微调</el-button>
          <el-button :loading="reparsing" :disabled="preview.status === 'COMMITTED'" @click="reparse">
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type UploadFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { AI_IMPORT_TIMEOUT_MS, http } from '@/api/http'
import { aiParseErrorMessage } from '@/api/errors'
import ImportTargetPicker from '@/components/aiImport/ImportTargetPicker.vue'
import TimetableMappingStep from '@/components/aiImport/TimetableMappingStep.vue'
import TimetablePreviewGrid from '@/components/aiImport/TimetablePreviewGrid.vue'
import { useImportSession } from '@/components/aiImport/useImportSession'
import { type ImportPreview, type PreviewRow } from '@/components/aiImport/timetablePreview'
import {
  IMPORT_TARGETS,
  preselectedTarget,
  targetCard,
  targetTitle,
} from '@/components/aiImport/importTargets'

const router = useRouter()
const route = useRoute()
const configured = ref(false)
const visionCapable = ref(false)
const inputMode = ref<'excel' | 'text' | 'image'>('excel')
const selectedTarget = ref(preselectedTarget(route.query.target))
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
  commitErrors,
  importedCount,
  isTimetable,
  previewIssues,
  commitDisabled,
  commitLabel,
  statusLabel,
  statusType,
  setPreview,
  commit,
  continueWithAi,
  reparse,
  clear,
} = useImportSession()

const currentTarget = computed(() => targetCard(selectedTarget.value) ?? IMPORT_TARGETS[0])
const mappableIssues = computed(() => previewIssues.value.filter((issue) => issue.row > 0))
const blockingIssues = computed(() => previewIssues.value.filter((issue) => issue.row === 0))
const suggestedTarget = computed(() => {
  const suggested = preview.value?.suggestedTarget
  return suggested && suggested !== preview.value?.targetType ? suggested : ''
})
const showMappingStep = computed(
  () =>
    isTimetable.value &&
    preview.value?.status !== 'COMMITTED' &&
    (mappableIssues.value.length > 0 || blockingIssues.value.length === 0),
)

const canParse = computed(() => {
  if (inputMode.value === 'excel') return selectedFile.value !== null
  if (inputMode.value === 'image') return imageFile.value !== null
  return text.value.trim().length > 0
})

// Image import is teacher-only; leaving the tab selected after switching target
// would send a request the backend rejects.
watch(selectedTarget, (target) => {
  if (target !== 'teacher' && inputMode.value === 'image') {
    inputMode.value = 'excel'
  }
})

onMounted(async () => {
  try {
    const { data } = await http.get('/ai/status')
    configured.value = Boolean(data?.tasks?.data_import?.configured)
    visionCapable.value = Boolean(data?.tasks?.data_import?.visionCapable)
  } catch {
    configured.value = false
  }
})

function onSelectTarget(code: string) {
  selectedTarget.value = code
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
  parsing.value = true
  commitErrors.value = []
  const config = { params: { target: selectedTarget.value }, timeout: AI_IMPORT_TIMEOUT_MS }
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
  } catch (error) {
    ElMessage.error(aiParseErrorMessage(error))
  } finally {
    parsing.value = false
  }
}

/** Switches the target the AI suggested, keeping the chosen file for a re-parse. */
function switchToSuggestedTarget() {
  if (!suggestedTarget.value) return
  selectedTarget.value = suggestedTarget.value
  clear()
  ElMessage.info(`已切换到${targetTitle(selectedTarget.value)}，请重新解析`)
}

function reset() {
  clear()
  selectedFile.value = null
  imageFile.value = null
  text.value = ''
}

function goTeachers() {
  router.push('/admin/teachers')
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

.notice {
  margin-bottom: 4px;
}

.file-hint {
  margin-top: 8px;
  color: #4b5563;
  font-size: 13px;
}

.hint {
  color: #6b7280;
  font-size: 12px;
}

.parse-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 16px;
}

.parse-progress {
  margin-top: 12px;
}

.vision-warning {
  margin-top: 8px;
}

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

.explain-card .explanation {
  margin: 0 0 12px;
  color: #374151;
}

.result-actions {
  margin-top: 6px;
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
