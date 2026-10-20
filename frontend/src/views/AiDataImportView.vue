<template>
  <div class="ai-import-page">
    <div class="toolbar">
      <div>
        <h2>AI 数据导入</h2>
        <p class="subtitle">
          上传 Excel / CSV、粘贴文本或上传图片，AI 解析后先预览，确认无误再写入教师档案。
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
              <div class="el-upload__tip">支持格式混乱的表格，AI 会自动识别姓名、学科、电话等列。</div>
            </template>
          </el-upload>
          <div v-if="selectedFile" class="file-hint">已选择：{{ selectedFile.name }}</div>
        </el-tab-pane>

        <el-tab-pane label="粘贴文本" name="text">
          <el-input
            v-model="text"
            type="textarea"
            :rows="9"
            placeholder="例如：张老师教语文，电话 13800000001；李老师教数学，工号 T002……"
          />
          <div class="hint">可以直接从微信聊天记录、通知里复制粘贴。</div>
        </el-tab-pane>

        <el-tab-pane label="上传图片" name="image">
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

      <div class="parse-actions">
        <el-button type="primary" :loading="parsing" :disabled="!configured || !canParse" @click="parse">
          开始解析
        </el-button>
        <span class="hint">AI 只生成预览，点击「入库」才会写入数据。</span>
      </div>
    </el-card>

    <template v-else>
      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span class="card-title">解析预览（{{ preview.rows.length }} 行）</span>
            <div class="head-actions">
              <el-tag size="small" :type="statusType">{{ statusLabel }}</el-tag>
              <el-button link type="primary" @click="reset">重新导入</el-button>
            </div>
          </div>
        </template>

        <el-table :data="preview.rows" border size="small" max-height="520">
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

        <div class="legend">
          <span class="swatch" /> 浅黄表示 AI 置信度低于
          {{ Math.round(preview.threshold * 100) }}%，建议重点核对
        </div>
      </el-card>

      <el-alert
        v-if="preview.status === 'COMMITTED'"
        type="success"
        show-icon
        :closable="false"
        class="result-alert"
      >
        <template #title>已成功入库 {{ importedCount }} 条教师数据</template>
        <div class="result-actions">
          <el-button link type="primary" @click="goTeachers">查看教师</el-button>
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
        <template v-if="!editing">
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
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { http } from '@/api/http'

interface Column {
  field: string
  label: string
  required: boolean
}

interface Row {
  values: Record<string, string>
  confidence: Record<string, number>
  lowConfidence?: string[]
}

interface Mapping {
  source: string
  target: string
  note: string
}

interface Preview {
  sessionId: number
  targetType: string
  columns: Column[]
  rows: Row[]
  mappings: Mapping[]
  explanation: string
  threshold: number
  status: string
}

interface RowError {
  row: number
  field: string
  message: string
}

const router = useRouter()
const configured = ref(false)
const importedCount = ref(0)
const inputMode = ref<'excel' | 'text' | 'image'>('excel')
const selectedFile = ref<File | null>(null)
const imageFile = ref<File | null>(null)
const text = ref('')
const preview = ref<Preview | null>(null)
const editing = ref(false)
const parsing = ref(false)
const committing = ref(false)
const continuing = ref(false)
const reparsing = ref(false)
const commitErrors = ref<RowError[]>([])

const canParse = computed(() => {
  if (inputMode.value === 'excel') return selectedFile.value !== null
  if (inputMode.value === 'image') return imageFile.value !== null
  return text.value.trim().length > 0
})

const statusLabel = computed(() => {
  if (!preview.value) return ''
  return (
    {
      PREVIEW: '待确认',
      EDITED: '已微调',
      COMMITTED: '已入库',
      FAILED: '解析失败',
    }[preview.value.status] ?? preview.value.status
  )
})

const statusType = computed(() => {
  if (!preview.value) return 'info'
  if (preview.value.status === 'COMMITTED') return 'success'
  if (preview.value.status === 'FAILED') return 'danger'
  return 'info'
})

onMounted(async () => {
  try {
    const { data } = await http.get('/ai/status')
    configured.value = Boolean(data?.tasks?.data_import?.configured)
  } catch {
    configured.value = false
  }
})

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
  try {
    let response
    if (inputMode.value === 'excel') {
      const form = new FormData()
      form.append('file', selectedFile.value as File)
      response = await http.post<Preview>('/ai/import/excel', form)
    } else if (inputMode.value === 'image') {
      const form = new FormData()
      form.append('file', imageFile.value as File)
      response = await http.post<Preview>('/ai/import/image', form)
    } else {
      response = await http.post<Preview>('/ai/import/text', { text: text.value })
    }
    preview.value = response.data
    editing.value = false
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    parsing.value = false
  }
}

async function saveRows() {
  if (!preview.value) return
  const { data } = await http.put<Preview>(`/ai/import/sessions/${preview.value.sessionId}/rows`, {
    rows: preview.value.rows,
  })
  preview.value = data
}

async function commit() {
  if (!preview.value) return
  committing.value = true
  commitErrors.value = []
  try {
    if (editing.value) {
      await saveRows()
    }
    const { data } = await http.post<{ imported: number }>(
      `/ai/import/sessions/${preview.value.sessionId}/commit`,
    )
    importedCount.value = data.imported
    preview.value = { ...preview.value, status: 'COMMITTED' }
    ElMessage.success(`已入库 ${data.imported} 条教师数据`)
  } catch (error) {
    const errors = (error as { response?: { data?: { errors?: RowError[] } } }).response?.data?.errors
    if (errors?.length) {
      commitErrors.value = errors
      editing.value = true
      ElMessage.error('有数据未通过校验，请修正后再入库')
    } else {
      ElMessage.error(messageOf(error))
    }
  } finally {
    committing.value = false
  }
}

async function continueWithAi() {
  if (!preview.value) return
  continuing.value = true
  try {
    await saveRows()
    const { data } = await http.post<Preview>(
      `/ai/import/sessions/${preview.value.sessionId}/continue`,
    )
    preview.value = data
    ElMessage.success('AI 已基于你的修正继续解析')
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    continuing.value = false
  }
}

async function reparse() {
  if (!preview.value) return
  try {
    await ElMessageBox.confirm('将丢弃当前预览并重新调用 AI 解析原始文件，确定继续？', '打回重析', {
      type: 'warning',
    })
  } catch {
    return
  }
  reparsing.value = true
  try {
    const { data } = await http.post<Preview>(`/ai/import/sessions/${preview.value.sessionId}/reparse`)
    preview.value = data
    editing.value = false
    commitErrors.value = []
    ElMessage.success('已重新解析')
  } catch (error) {
    ElMessage.error(messageOf(error))
  } finally {
    reparsing.value = false
  }
}

function reset() {
  preview.value = null
  editing.value = false
  commitErrors.value = []
  importedCount.value = 0
  selectedFile.value = null
  imageFile.value = null
  text.value = ''
}

function goTeachers() {
  router.push('/admin/teachers')
}

function confidenceTitle(row: Row, field: string) {
  const value = row.confidence?.[field]
  return value === undefined ? undefined : `AI 置信度 ${Math.round(value * 100)}%`
}

function fieldLabel(field: string) {
  return preview.value?.columns.find((column) => column.field === field)?.label ?? field
}

function messageOf(error: unknown) {
  const response = (error as { response?: { data?: { message?: string } } }).response
  return response?.data?.message ?? '操作失败，请稍后重试'
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
