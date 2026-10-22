<template>
  <div class="history-page">
    <div class="toolbar">
      <div>
        <h2>AI 操作历史</h2>
        <p class="subtitle">追溯每次 AI 导入与调代推荐：谁、何时、用了什么模型、最终结果。</p>
      </div>
      <div class="actions">
        <el-button :loading="exporting === 'xlsx'" @click="exportHistory('xlsx')">导出 Excel</el-button>
        <el-button :loading="exporting === 'pdf'" @click="exportHistory('pdf')">导出 PDF</el-button>
      </div>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="filters">
        <el-date-picker
          v-model="filters.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 260px"
        />
        <el-input v-model="filters.username" clearable placeholder="操作人账号" style="width: 160px" />
        <el-select v-model="filters.type" clearable placeholder="记录类型" style="width: 140px">
          <el-option label="全部" value="" />
          <el-option label="导入" value="import" />
          <el-option label="调用" value="call" />
        </el-select>
        <el-select v-model="filters.taskType" clearable placeholder="任务类型" style="width: 160px">
          <el-option label="数据导入" value="data_import" />
          <el-option label="调代推荐" value="substitution_recommend" />
        </el-select>
        <el-select v-model="filters.providerId" clearable placeholder="供应商" style="width: 160px">
          <el-option v-for="provider in providers" :key="provider.id" :label="provider.name" :value="provider.id" />
        </el-select>
        <el-button type="primary" @click="query">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'import' ? 'success' : 'info'">
              {{ row.type === 'import' ? '导入' : '调用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务" width="110">
          <template #default="{ row }">{{ taskLabel(row.taskType) }}</template>
        </el-table-column>
        <el-table-column prop="username" label="操作人" width="120" />
        <el-table-column label="供应商 / 模型" min-width="170">
          <template #default="{ row }">
            <span v-if="row.providerName || row.modelName">
              {{ row.providerName || '—' }} / {{ row.modelName || '—' }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.success ? 'success' : 'danger'">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="没有符合条件的 AI 操作记录" />
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="760px" @closed="clearDetail">
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <template v-if="importDetail">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="来源">{{ importDetail.fileName || '粘贴文本' }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ importStatus(importDetail.status) }}</el-descriptions-item>
              <el-descriptions-item label="行数">{{ importDetail.rowCount }}</el-descriptions-item>
              <el-descriptions-item label="入库数">{{ importDetail.importedCount }}</el-descriptions-item>
              <el-descriptions-item label="操作人">{{ importDetail.username }}</el-descriptions-item>
              <el-descriptions-item label="时间">{{ formatTime(importDetail.createdAt) }}</el-descriptions-item>
            </el-descriptions>

            <div class="detail-actions">
              <el-button :disabled="!importDetail.fileName" :loading="downloading" @click="downloadFile">
                下载原始文件
              </el-button>
            </div>
            <div v-if="imageUrl" class="image-preview">
              <img :src="imageUrl" alt="上传的原始图片">
            </div>

            <p class="explanation">{{ importDetail.explanation || '无 AI 说明' }}</p>

            <el-tabs v-model="importTab">
              <el-tab-pane label="AI 解析预览" name="preview">
                <pre class="json">{{ pretty(importDetail.preview) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="用户确认" name="confirmed">
                <pre class="json">{{ pretty(importDetail.confirmed) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="入库结果" name="result">
                <pre class="json">{{ pretty(importDetail.result) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="AI 原始响应" name="raw">
                <pre class="json">{{ pretty(importDetail.aiRawResponse) }}</pre>
              </el-tab-pane>
            </el-tabs>
          </template>

          <template v-else-if="callDetail">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="任务">{{ taskLabel(callDetail.taskType) }}</el-descriptions-item>
              <el-descriptions-item label="结果">
                <el-tag size="small" :type="callDetail.success ? 'success' : 'danger'">
                  {{ callDetail.success ? '成功' : '失败' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="操作人">{{ callDetail.username || '—' }}</el-descriptions-item>
              <el-descriptions-item label="供应商">{{ callDetail.providerName || '—' }}</el-descriptions-item>
              <el-descriptions-item label="模型">{{ callDetail.modelName || '—' }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ callDetail.latencyMs ?? '—' }} ms</el-descriptions-item>
              <el-descriptions-item label="输入 tokens">{{ callDetail.inputTokens ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="输出 tokens">{{ callDetail.outputTokens ?? '—' }}</el-descriptions-item>
              <el-descriptions-item v-if="callDetail.errorCode" label="错误码">
                {{ callDetail.errorCode }}
              </el-descriptions-item>
              <el-descriptions-item v-if="callDetail.errorMessage" label="错误信息" :span="2">
                {{ callDetail.errorMessage }}
              </el-descriptions-item>
            </el-descriptions>

            <template v-if="callDetail.context">
              <h4>调代上下文</h4>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="请假教师">
                  {{ callDetail.context.originalTeacher || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="课程">{{ callDetail.context.courseName || '—' }}</el-descriptions-item>
                <el-descriptions-item label="班级">{{ callDetail.context.className || '—' }}</el-descriptions-item>
                <el-descriptions-item label="日期">{{ callDetail.context.effectiveDate || '—' }}</el-descriptions-item>
                <el-descriptions-item label="节次">第 {{ callDetail.context.periodNo }} 节</el-descriptions-item>
                <el-descriptions-item label="最终代课教师">
                  {{ callDetail.context.finalSubstituteTeacher || '尚未确定' }}
                </el-descriptions-item>
              </el-descriptions>

              <h4>AI 推荐</h4>
              <el-empty
                v-if="!callDetail.context.recommendations?.length"
                description="本次调用没有解析到推荐结果"
                :image-size="60"
              />
              <div v-else class="recommend-list">
                <div
                  v-for="item in callDetail.context.recommendations"
                  :key="item.teacherId ?? item.name"
                  class="recommend-card"
                >
                  <div class="recommend-head">
                    <strong>{{ item.name || item.teacherId }}</strong>
                    <el-tag v-if="item.score !== null && item.score !== undefined" size="small" type="success">
                      {{ item.score }} 分
                    </el-tag>
                  </div>
                  <div class="recommend-reason">{{ item.reason }}</div>
                </div>
              </div>
            </template>

            <el-tabs v-model="callTab">
              <el-tab-pane label="请求上下文" name="request">
                <pre class="json">{{ pretty(callDetail.request) }}</pre>
              </el-tab-pane>
              <el-tab-pane label="AI 原始响应" name="response">
                <pre class="json">{{ pretty(callDetail.response) }}</pre>
              </el-tab-pane>
            </el-tabs>
          </template>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

interface HistoryRow {
  type: 'import' | 'call'
  id: number
  taskType: string
  username?: string
  createdAt: string
  summary: string
  success: boolean
  providerName?: string
  modelName?: string
  refId?: number
}

interface ImportDetail {
  id: number
  importType: string
  targetType: string
  username?: string
  fileName?: string
  fileSize?: number
  status: string
  rowCount: number
  importedCount: number
  explanation?: string
  preview?: unknown
  confirmed?: unknown
  result?: unknown
  aiRawResponse?: string
  createdAt: string
  updatedAt: string
}

interface Candidate {
  teacherId?: number
  name?: string
  reason?: string
  score?: number
}

interface CallDetail {
  id: number
  taskType: string
  username?: string
  providerName?: string
  modelName?: string
  success: boolean
  errorCode?: string
  errorMessage?: string
  inputTokens?: number
  outputTokens?: number
  latencyMs?: number
  request?: unknown
  response?: unknown
  createdAt: string
  context?: {
    requestId?: number
    originalTeacher?: string
    courseName?: string
    className?: string
    effectiveDate?: string
    periodNo?: number
    status?: string
    finalSubstituteTeacher?: string
    recommendations?: Candidate[]
  }
}

interface Provider {
  id: number
  name: string
}

const filters = reactive({
  range: [] as string[],
  username: '',
  type: '',
  taskType: '',
  providerId: null as number | null,
})

const rows = ref<HistoryRow[]>([])
const providers = ref<Provider[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const exporting = ref<'' | 'xlsx' | 'pdf'>('')

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<HistoryRow | null>(null)
const importDetail = ref<ImportDetail | null>(null)
const callDetail = ref<CallDetail | null>(null)
const importTab = ref('preview')
const callTab = ref('request')
const downloading = ref(false)
const imageUrl = ref('')

const detailTitle = computed(() => {
  if (!detail.value) return '详情'
  return `${detail.value.type === 'import' ? '导入' : '调用'}详情 #${detail.value.id}`
})

onMounted(async () => {
  await Promise.all([load(), loadProviders()])
})

onBeforeUnmount(() => {
  revokeImage()
})

function params() {
  const [from, to] = filters.range ?? []
  return {
    from: from || undefined,
    to: to || undefined,
    username: filters.username || undefined,
    type: filters.type || undefined,
    taskType: filters.taskType || undefined,
    providerId: filters.providerId ?? undefined,
    page: page.value - 1,
    size,
  }
}

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/ai/history', { params: params() })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (error) {
    ElMessage.error(serverMessage(error, '操作失败，请稍后重试'))
  } finally {
    loading.value = false
  }
}

async function loadProviders() {
  try {
    const { data } = await http.get<Provider[]>('/ai-providers')
    providers.value = data ?? []
  } catch {
    providers.value = []
  }
}

function query() {
  page.value = 1
  load()
}

function reset() {
  filters.range = []
  filters.username = ''
  filters.type = ''
  filters.taskType = ''
  filters.providerId = null
  page.value = 1
  load()
}

async function openDetail(row: HistoryRow) {
  detail.value = row
  detailVisible.value = true
  detailLoading.value = true
  importDetail.value = null
  callDetail.value = null
  revokeImage()
  try {
    if (row.type === 'import') {
      const { data } = await http.get<ImportDetail>(`/ai/history/imports/${row.id}`)
      importDetail.value = data
      importTab.value = 'preview'
      if (data.fileName && /\.(png|jpe?g|webp)$/i.test(data.fileName)) {
        await loadImage(data.id)
      }
    } else {
      const { data } = await http.get<CallDetail>(`/ai/history/calls/${row.id}`)
      callDetail.value = data
      callTab.value = 'request'
    }
  } catch (error) {
    ElMessage.error(serverMessage(error, '操作失败，请稍后重试'))
  } finally {
    detailLoading.value = false
  }
}

async function loadImage(sessionId: number) {
  try {
    const { data } = await http.get(`/ai/import/sessions/${sessionId}/file`, { responseType: 'blob' })
    imageUrl.value = URL.createObjectURL(data as Blob)
  } catch {
    imageUrl.value = ''
  }
}

async function downloadFile() {
  if (!importDetail.value?.fileName) return
  downloading.value = true
  try {
    const { data } = await http.get(`/ai/import/sessions/${importDetail.value.id}/file`, {
      responseType: 'blob',
    })
    downloadBlob(data as Blob, importDetail.value.fileName)
  } catch (error) {
    ElMessage.error(serverMessage(error, '操作失败，请稍后重试'))
  } finally {
    downloading.value = false
  }
}

async function exportHistory(format: 'xlsx' | 'pdf') {
  exporting.value = format
  try {
    const { data } = await http.get('/ai/history/export', {
      params: { ...params(), page: undefined, size: undefined, format },
      responseType: 'blob',
    })
    downloadBlob(data as Blob, `ai-history.${format}`)
  } catch (error) {
    ElMessage.error(serverMessage(error, '操作失败，请稍后重试'))
  } finally {
    exporting.value = ''
  }
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

function clearDetail() {
  detail.value = null
  importDetail.value = null
  callDetail.value = null
  revokeImage()
}

function revokeImage() {
  if (imageUrl.value) {
    URL.revokeObjectURL(imageUrl.value)
    imageUrl.value = ''
  }
}

function taskLabel(task?: string) {
  if (task === 'data_import') return '数据导入'
  if (task === 'substitution_recommend') return '调代推荐'
  return task || '—'
}

function importStatus(status: string) {
  const labels: Record<string, string> = {
    PREVIEW: '待确认',
    EDITED: '已微调',
    COMMITTED: '已入库',
    FAILED: '解析失败',
  }
  return labels[status] ?? status
}

function pretty(value: unknown) {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  return JSON.stringify(value, null, 2)
}

function formatTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

</script>

<style scoped>
.history-page {
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

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.detail {
  min-height: 200px;
}

.detail-actions {
  margin: 12px 0;
}

.image-preview img {
  max-width: 100%;
  max-height: 260px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

.explanation {
  color: #374151;
  line-height: 1.6;
}

.json {
  max-height: 420px;
  overflow: auto;
  padding: 12px;
  border-radius: 6px;
  background: #f8fafc;
  border: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

h4 {
  margin: 18px 0 8px;
}

.recommend-list {
  display: grid;
  gap: 8px;
}

.recommend-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 10px 12px;
}

.recommend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.recommend-reason {
  margin-top: 6px;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.5;
}
</style>
