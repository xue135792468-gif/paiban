<template>
  <el-dialog
    :model-value="true" title="导入任课表 Excel" width="min(840px, 96vw)"
    :show-close="!busy" :close-on-click-modal="false" :close-on-press-escape="!busy" @close="$emit('close')"
  >
    <div class="upload">
      <label>
        选择 Excel 文件
        <input type="file" accept=".xlsx,.xls" :disabled="busy" @change="selectFile">
      </label>
      <el-button v-if="!report" :disabled="!file" :loading="busy" @click="run('preview')">预演</el-button>
    </div>
    <p class="target">导入到 {{ target }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="report">
      <ul v-if="report.errors.length" class="issues" aria-label="阻断错误">
        <li v-for="(issue, index) in report.errors" :key="index">
          {{ issue.row > 0 ? `第 ${issue.row} 行，` : '' }}{{ issue.field }}：{{ issue.message }}
        </li>
      </ul>
      <ul v-if="report.warnings.length" class="warnings" aria-label="容量预警">
        <li v-for="warning in report.warnings" :key="warning.row">
          第 {{ warning.row }} 行 {{ warning.className }}每周需 {{ warning.requested }} 节，作息只有 {{ warning.available }} 节。
        </li>
      </ul>

      <div class="summary" aria-label="导入统计">
        <span v-for="[key, label] in entities" :key="key">
          <strong>{{ label }}</strong>
          新建 {{ report.summary[key].created }} · 更新 {{ report.summary[key].updated }} · 不变 {{ report.summary[key].unchanged }}
        </span>
      </div>
      <details v-if="report.teachers.length" :open="report.summary.teachers.created > 0">
        <summary>教师明细</summary>
        <ul>
          <li v-for="teacher in report.teachers" :key="teacher.name">
            {{ teacher.name }} · {{ actionLabel[teacher.action] }}
          </li>
        </ul>
      </details>

      <div v-if="report.courses.length" class="table-scroll">
        <table>
          <caption>课程及周课时数</caption>
          <thead><tr><th scope="col">课程</th><th scope="col">动作</th><th scope="col">课程默认</th><th scope="col">本次覆盖</th></tr></thead>
          <tbody>
            <tr v-for="course in report.courses" :key="course.name">
              <th scope="row">{{ course.name }}</th>
              <td>{{ actionLabel[course.action] }}</td>
              <td>{{ course.defaultHours ?? '—' }}</td>
              <td>
                <el-input
                  v-model="overrides[course.name]" inputmode="numeric" :disabled="busy"
                  :aria-label="`${course.name}本次周课时数`" :placeholder="String(course.defaultHours ?? '1–20')"
                />
                <small v-if="!isValidHours(overrides[course.name])" class="issues">请输入 1–20 的整数</small>
                <small v-else-if="course.missingHours && !overrides[course.name]" class="issues">待填写周课时数</small>
              </td>
            </tr>
          </tbody>
        </table>
        <el-checkbox v-model="saveAsDefaults" :disabled="busy">同步保存为课程默认周课时</el-checkbox>
      </div>

      <div v-if="report.rows.length" class="table-scroll preview-rows">
        <table>
          <caption>班级与授课任务</caption>
          <thead>
            <tr>
              <th scope="col">行号</th><th scope="col">班级</th><th scope="col">人数</th>
              <th scope="col">班主任</th><th scope="col">授课任务</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in report.rows" :key="row.row">
              <td>{{ row.row }}</td>
              <th scope="row">{{ row.className }} <small>{{ actionLabel[row.action] }}</small></th>
              <td>{{ row.studentCount }}</td><td>{{ row.headTeacher ?? '—' }}</td>
              <td>
                <ul>
                  <li v-for="cell in row.assignments" :key="cell.course">
                    {{ cell.course }} · {{ cell.teacher }} · {{ cell.weeklyLessons ?? '待补' }} 节 · {{ actionLabel[cell.action] }}
                  </li>
                </ul>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template #footer>
      <el-button :disabled="busy" @click="$emit('close')">取消</el-button>
      <el-button v-if="report" :disabled="!dirty || !validHours" :loading="busy" @click="run('preview')">更新预演</el-button>
      <el-button type="primary" :disabled="!canCommit" :loading="busy" @click="run('commit')">确认导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import { actionLabel, requestMatrixImport, type MatrixContext, type MatrixOptions, type MatrixReport } from './importApi'

const props = defineProps<{ context: MatrixContext; target: string }>()
const emit = defineEmits<{ close: []; imported: [] }>()
const file = ref<File>()
const busy = ref(false)
const error = ref('')
const report = ref<MatrixReport>()
const overrides = ref<Record<string, string>>({})
const saveAsDefaults = ref(false)
const lastPreview = ref('')
const entities = [
  ['classes', '行政班'], ['teachers', '教师'], ['courses', '课程'], ['assignments', '授课任务'],
] as const

function isValidHours(hours?: string) {
  return !hours || /^(?:[1-9]|1[0-9]|20)$/.test(hours)
}
const validHours = computed(() => Object.values(overrides.value).every(isValidHours))
const options = computed<MatrixOptions>(() => ({
  weeklyHours: Object.fromEntries(Object.entries(overrides.value)
    .filter(([, hours]) => hours).map(([course, hours]) => [course, Number(hours)])),
  saveAsDefaults: saveAsDefaults.value,
}))
const dirty = computed(() => JSON.stringify(options.value) !== lastPreview.value)
const canCommit = computed(() => report.value && !dirty.value && validHours.value && !busy.value
  && !report.value.errors.length && !report.value.courses.some((course) => course.missingHours))

function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0]
  report.value = undefined
  error.value = ''
  overrides.value = Object.create(null) as Record<string, string>
  saveAsDefaults.value = false
  lastPreview.value = ''
}

async function run(phase: 'preview' | 'commit') {
  if (!file.value || busy.value || (phase === 'commit' && !canCommit.value)) return
  const requested = options.value
  busy.value = true
  error.value = ''
  try {
    const { data, status } = await requestMatrixImport(phase, props.context, file.value, requested)
    if (!('summary' in data)) {
      error.value = data.message ?? '处理任课表失败'
      return
    }
    report.value = data
    if (phase === 'preview') {
      lastPreview.value = JSON.stringify(requested)
    } else if (status === 200) {
      ElMessage.success('任课表已导入')
      emit('imported')
    } else {
      error.value = '导入未通过校验，未写入任课数据。'
    }
  } catch (cause) {
    error.value = serverMessage(cause, '处理任课表失败')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.upload, .summary { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.upload input { display: block; margin-top: 4px; }
.target, small { color: var(--el-text-color-secondary); }
.summary span { flex: 1 0 145px; padding: 8px; background: var(--el-fill-color-light); }
.summary strong { display: block; }
.issues { color: var(--el-color-danger-dark-2); }
.warnings { color: var(--el-color-warning-dark-2); }
.table-scroll { overflow: auto; margin-top: 12px; }
.preview-rows { max-height: 280px; }
table { width: 100%; border-collapse: collapse; }
caption { text-align: left; font-weight: 600; padding: 4px 0; }
th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid var(--el-border-color-lighter); }
td ul { margin: 0; padding-left: 18px; }
td .el-input { max-width: 110px; }
</style>
