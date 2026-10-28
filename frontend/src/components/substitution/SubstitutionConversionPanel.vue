<template>
  <div class="conversion">
    <el-alert
      v-if="statusInfo && !statusInfo.converted"
      type="info"
      :closable="false"
      show-icon
      title="历史调代记录仍按旧语义读取（自生效日起持续覆盖）"
      description="未通过数据确认门前不会切换历史解释；确认前一切照旧。"
    />
    <el-alert
      v-else-if="statusInfo && statusInfo.converted"
      type="success"
      :closable="false"
      show-icon
      title="历史调代记录已全部按实际授课日期解释"
      description="转换经确认后生效，批次与逐条依据可在下方追溯。"
    />

    <div class="actions">
      <el-button :loading="statusLoading" @click="loadStatus">刷新状态</el-button>
      <el-button type="primary" :loading="precheckLoading" @click="runPrecheck">
        生成预检报告
      </el-button>
      <el-tooltip
        :disabled="confirmEnabled"
        :content="confirmDisabledReason"
        placement="top"
      >
        <span>
          <el-button
            v-if="canApprove"
            type="warning"
            :disabled="!confirmEnabled"
            :loading="confirmLoading"
            @click="onConfirm"
          >
            确认转换
          </el-button>
        </span>
      </el-tooltip>
    </div>

    <div v-if="report" class="summary">
      <el-tag>旧行总数 {{ report.summary.totalRows }}</el-tag>
      <el-tag type="success">可转换 {{ report.summary.convertible }}</el-tag>
      <el-tag type="danger">阻塞 {{ report.summary.blocked }}</el-tag>
      <el-tag type="info">已转换 {{ report.summary.alreadyConverted }}</el-tag>
      <el-tag>日期课表差异 {{ report.summary.changes }}</el-tag>
      <span class="generated">报告时间 {{ formatTime(report.generatedAt) }}</span>
    </div>

    <el-table v-if="report" :data="report.items" size="small">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand">
            <p v-if="row.affectedDates.length === 0" class="muted">
              该记录转换后不产生课表差异{{ row.status === 'BLOCKED' ? '（阻塞行保持旧语义）' : '' }}。
            </p>
            <el-table v-else :data="row.affectedDates" size="small">
              <el-table-column prop="date" label="日期" width="110" />
              <el-table-column label="转换前" min-width="180">
                <template #default="{ row: change }">{{ lessonText(change.before) }}</template>
              </el-table-column>
              <el-table-column label="转换后" min-width="180">
                <template #default="{ row: change }">
                  {{ change.after ? lessonText(change.after) : '该日无课（恢复常规安排）' }}
                </template>
              </el-table-column>
              <el-table-column label="说明" width="140">
                <template #default="{ row: change }">
                  <el-tag v-if="change.restoredToBaseline" type="success" size="small">恢复常规安排</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="className" label="行政班" min-width="100" />
      <el-table-column prop="courseName" label="课程" min-width="90" />
      <el-table-column prop="fromPeriodLabel" label="原节次" width="110" />
      <el-table-column label="结论" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">
            {{ conversionItemStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="实例日期依据 / 阻塞原因" min-width="260">
        <template #default="{ row }">
          <span v-if="row.status === 'BLOCKED'" class="blocked">
            {{ conversionBlockerLabel(row.blockerCode) }}：{{ row.blockerReason }}
          </span>
          <span v-else>{{ row.basis }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="点击「生成预检报告」查看逐条转换依据与差异" :image-size="60" />

    <h3>确认记录</h3>
    <el-table :data="statusInfo?.conversions ?? []" size="small">
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'CONVERTED' ? 'success' : 'danger'" size="small">
            {{ row.status === 'CONVERTED' ? '已转换' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="confirmedBy" label="确认人" width="120" />
      <el-table-column label="确认时间" width="160">
        <template #default="{ row }">{{ formatTime(row.confirmedAt) }}</template>
      </el-table-column>
      <el-table-column prop="convertedCount" label="转换行数" width="90" />
      <el-table-column prop="confirmationComment" label="备注" min-width="160" />
      <el-table-column label="失败原因" min-width="200">
        <template #default="{ row }">{{ row.failureReason ?? '' }}</template>
      </el-table-column>
    </el-table>
    <el-empty
      v-if="(statusInfo?.conversions.length ?? 0) === 0"
      description="尚无确认记录"
      :image-size="50"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import {
  conversionBlockerLabel,
  conversionItemStatusLabel,
  formatTime,
  typeLabel,
  type ConversionLesson,
  type ConversionReport,
  type ConversionStatusInfo,
} from '@/components/substitution/types'

const auth = useAuthStore()
const canApprove = computed(() => auth.hasPermission('substitution:approve'))

const statusInfo = ref<ConversionStatusInfo | null>(null)
const report = ref<ConversionReport | null>(null)
const statusLoading = ref(false)
const precheckLoading = ref(false)
const confirmLoading = ref(false)

const confirmEnabled = computed(
  () =>
    !!statusInfo.value?.canConfirm
    && (report.value?.summary.convertible ?? 0) > 0
    && (report.value?.summary.blocked ?? 0) === 0,
)
const confirmDisabledReason = computed(() => {
  if (!statusInfo.value) return '先加载转换状态'
  if (statusInfo.value.converted) return '没有旧格式记录需要转换'
  if (statusInfo.value.blockedCount > 0) return '存在阻塞记录，需人工处理后才能确认'
  return '请先生成并核对预检报告'
})

async function loadStatus() {
  statusLoading.value = true
  try {
    const { data } = await http.get<ConversionStatusInfo>('/substitution-conversion/status')
    statusInfo.value = data
  } finally {
    statusLoading.value = false
  }
}

async function runPrecheck() {
  precheckLoading.value = true
  try {
    const { data } = await http.get<ConversionReport>('/substitution-conversion/precheck')
    report.value = data
    await loadStatus()
  } finally {
    precheckLoading.value = false
  }
}

async function onConfirm() {
  confirmLoading.value = true
  try {
    const { data } = await http.post('/substitution-conversion/confirm', { comment: '' })
    if (data.alreadyConverted) {
      ElMessage.info('没有待转换的旧记录，未产生新的转换')
    } else {
      ElMessage.success(`转换完成：${data.convertedCount} 条记录已按实际授课日期解释`)
    }
    report.value = null
    await loadStatus()
  } finally {
    confirmLoading.value = false
  }
}

function lessonText(lesson: ConversionLesson | null): string {
  if (!lesson) return '该日无课'
  const room = lesson.roomName ? `（${lesson.roomName}）` : ''
  return `${lesson.teacherName ?? ''} ${lesson.periodLabel}${room}`
}

function statusTagType(status: string): 'success' | 'danger' | 'info' {
  if (status === 'CONVERTIBLE') return 'success'
  if (status === 'BLOCKED') return 'danger'
  return 'info'
}

onMounted(loadStatus)
</script>

<style scoped>
.conversion {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.actions {
  display: flex;
  gap: 8px;
}
.summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.generated {
  color: var(--paiban-muted);
  font-size: 12px;
}
.expand {
  padding: 8px 16px;
}
.muted {
  color: var(--paiban-muted);
  font-size: 12px;
}
.blocked {
  color: var(--el-color-danger);
}
h3 {
  margin: 8px 0 0;
  font-size: 15px;
}
</style>
