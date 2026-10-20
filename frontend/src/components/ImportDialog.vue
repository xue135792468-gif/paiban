<template>
  <el-dialog
    :model-value="modelValue"
    :title="`导入${entityLabel}`"
    width="760px"
    destroy-on-close
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="steps">
      <el-steps :active="step" finish-status="success" simple>
        <el-step title="选择文件" />
        <el-step title="预览校验" />
        <el-step title="确认入库" />
      </el-steps>
    </div>

    <div v-if="step === 0" class="upload-panel">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx,.xls"
        :on-change="onFileChange"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="el-upload__text">把 Excel 拖到这里，或<em>点击选择</em></div>
        <template #tip>
          <div class="upload-tip">支持 .xlsx / .xls，仅读取第一个工作表。导入前会先做校验，不会直接写库。</div>
        </template>
      </el-upload>
      <el-button link type="primary" class="template-link" :loading="downloading" @click="downloadTemplate">
        <el-icon><Download /></el-icon>
        <span>下载导入模板</span>
      </el-button>
    </div>

    <div v-else>
      <div class="summary">
        <el-tag type="info">共 {{ report?.totalRows ?? 0 }} 行</el-tag>
        <el-tag type="success">可导入 {{ report?.validRows ?? 0 }} 行</el-tag>
        <el-tag v-if="(report?.errorCount ?? 0) > 0" type="danger">错误 {{ report?.errorCount }} 处</el-tag>
        <el-tag v-if="step === 2" type="success">已导入 {{ report?.importedRows ?? 0 }} 行</el-tag>
      </div>

      <el-alert
        v-if="step === 1 && (report?.errorCount ?? 0) > 0"
        title="存在校验错误：本次不会导入任何数据。请修正后重新上传。"
        type="error"
        :closable="false"
        show-icon
        class="warning"
      />
      <el-alert
        v-if="step === 2"
        :title="`导入完成：成功 ${report?.importedRows ?? 0} 行`"
        type="success"
        :closable="false"
        show-icon
        class="warning"
      />

      <el-alert
        v-for="(warning, index) in report?.warnings ?? []"
        :key="index"
        :title="warning"
        type="warning"
        :closable="false"
        show-icon
        class="warning"
      />

      <el-table
        v-if="(report?.errors?.length ?? 0) > 0"
        :data="report?.errors ?? []"
        size="small"
        max-height="240"
        class="error-table"
        border
      >
        <el-table-column prop="row" label="行号" width="80" />
        <el-table-column prop="field" label="字段" width="140" />
        <el-table-column prop="message" label="错误原因" />
      </el-table>

      <el-table
        v-if="step === 1 && (report?.rows?.length ?? 0) > 0"
        :data="report?.rows ?? []"
        size="small"
        max-height="240"
        border
      >
        <el-table-column
          v-for="header in previewHeaders"
          :key="header"
          :prop="header"
          :label="header"
          min-width="110"
          show-overflow-tooltip
        />
      </el-table>
    </div>

    <template #footer>
      <el-button @click="close">关闭</el-button>
      <el-button v-if="step === 0" type="primary" :disabled="!file" :loading="previewing" @click="doPreview">
        预览校验
      </el-button>
      <template v-else-if="step === 1">
        <el-button @click="reset">重新选择</el-button>
        <el-button
          v-if="(report?.errors?.length ?? 0) > 0"
          :loading="exportingErrors"
          @click="exportErrors"
        >
          导出错误清单
        </el-button>
        <el-button
          type="primary"
          :disabled="(report?.validRows ?? 0) === 0 || (report?.errorCount ?? 0) > 0"
          :loading="committing"
          @click="doCommit"
        >
          确认导入 {{ report?.validRows ?? 0 }} 行
        </el-button>
      </template>
      <el-button v-else type="primary" @click="close">完成</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { Download, UploadFilled } from '@element-plus/icons-vue'
import { http } from '@/api/http'

interface ImportError {
  row: number
  field: string
  message: string
}

interface ImportReport {
  entity: string
  displayName: string
  totalRows: number
  validRows: number
  errorCount: number
  importedRows: number
  errors: ImportError[]
  warnings: string[]
  rows: Record<string, string>[]
}

const props = defineProps<{
  modelValue: boolean
  entity: string
  entityLabel: string
  semesterId?: number | null
  previewUrl?: string
  commitUrl?: string
  templateUrl?: string
}>()

const previewEndpoint = computed(() => props.previewUrl ?? `/import/${props.entity}/preview`)
const commitEndpoint = computed(() => props.commitUrl ?? `/import/${props.entity}/commit`)
const templateEndpoint = computed(() => props.templateUrl ?? `/import/${props.entity}/template`)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  imported: []
}>()

const step = ref(0)
const file = ref<File | null>(null)
const report = ref<ImportReport | null>(null)
const previewing = ref(false)
const committing = ref(false)
const downloading = ref(false)
const exportingErrors = ref(false)

const previewHeaders = computed(() => {
  const first = report.value?.rows?.[0]
  return first ? Object.keys(first) : []
})

function reset() {
  step.value = 0
  file.value = null
  report.value = null
}

function close() {
  emit('update:modelValue', false)
  if (step.value === 2) emit('imported')
  setTimeout(reset, 200)
}

function onFileChange(upload: UploadFile) {
  file.value = upload.raw ?? null
  report.value = null
}

function formData() {
  const data = new FormData()
  data.append('file', file.value as File)
  return data
}

function params() {
  return props.semesterId ? { semesterId: props.semesterId } : undefined
}

async function doPreview() {
  if (!file.value) return
  previewing.value = true
  try {
    const { data } = await http.post(previewEndpoint.value, formData(), { params: params() })
    report.value = data
    step.value = 1
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '校验失败')
  } finally {
    previewing.value = false
  }
}

async function doCommit() {
  committing.value = true
  try {
    const { data } = await http.post(commitEndpoint.value, formData(), { params: params() })
    report.value = data
    if ((data.errorCount ?? 0) > 0 || (data.errors?.length ?? 0) > 0) {
      step.value = 1
      ElMessage.error(`有 ${data.errorCount ?? data.errors?.length} 处错误，本次未导入任何数据`)
    } else {
      step.value = 2
      ElMessage.success(`成功导入 ${data.importedRows} 行`)
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '导入失败')
  } finally {
    committing.value = false
  }
}

async function exportErrors() {
  if (!report.value?.errors?.length) return
  exportingErrors.value = true
  try {
    const { data } = await http.post(
      '/import/errors/export',
      { entity: props.entity, errors: report.value.errors },
      { responseType: 'blob' },
    )
    const url = URL.createObjectURL(data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${props.entityLabel}导入错误清单.xlsx`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('导出错误清单失败')
  } finally {
    exportingErrors.value = false
  }
}

async function downloadTemplate() {
  downloading.value = true
  try {
    const { data } = await http.get(templateEndpoint.value, { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${props.entityLabel}导入模板.xlsx`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('模板下载失败')
  } finally {
    downloading.value = false
  }
}
</script>

<style scoped>
.steps {
  margin-bottom: 20px;
}
.upload-panel {
  display: grid;
  gap: 8px;
}
.upload-icon {
  font-size: 44px;
  color: #a3b1d8;
  margin-bottom: 8px;
}
.upload-tip {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 8px;
}
.template-link {
  justify-self: start;
}
.summary {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.warning {
  margin-bottom: 10px;
}
.error-table {
  margin-bottom: 12px;
}
</style>
