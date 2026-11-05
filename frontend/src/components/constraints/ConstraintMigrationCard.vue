<template>
  <el-card
    v-if="semesterId"
    class="migration-card"
    shadow="never"
    data-testid="constraint-migration-card"
  >
    <template #header>
      <div class="migration-head">
        <div>
          <h3 class="migration-title">旧规则迁移</h3>
          <p class="migration-sub">
            把旧版 26 类规则迁到新版 18 类。删除的类型只停用，旧参数保留可查，不会物理删除。
          </p>
        </div>
        <el-button
          type="primary"
          data-testid="constraint-migration-run"
          :loading="running"
          :disabled="!canRun"
          @click="onRun"
        >
          运行迁移
        </el-button>
      </div>
    </template>

    <p v-if="loading" class="migration-copy" data-testid="constraint-migration-loading">
      正在读取旧规则…
    </p>

    <el-alert
      v-else-if="error"
      type="error"
      :closable="false"
      show-icon
      data-testid="constraint-migration-error"
    >
      <template #title>无法读取旧规则迁移信息</template>
      <p class="migration-copy">{{ error }}</p>
    </el-alert>

    <template v-else>
      <el-empty
        v-if="isEmpty"
        description="当前学期没有需要迁移的旧规则"
        data-testid="constraint-migration-empty"
      />

      <template v-else>
        <div v-if="preview" class="counts" data-testid="constraint-migration-counts">
          <span class="count"><b>{{ preview.retained }}</b> 保留</span>
          <span class="count"><b>{{ preview.converted }}</b> 转换</span>
          <span class="count"><b>{{ preview.disabled }}</b> 停用</span>
          <span class="count" :class="{ 'count-warn': preview.review > 0 }">
            <b>{{ preview.review }}</b> 待确认
          </span>
        </div>

        <p v-if="preview" class="migration-copy" data-testid="constraint-migration-source">
          共 {{ preview.configCount }} 条类型配置、{{ preview.entryCount }} 条参数条目。
          <template v-if="preview.review > 0">
            其中 {{ preview.review }} 条无法自动映射，迁移只做标记、不改内容，请运行后在报告中
            按后端给出的原因逐条确认。
          </template>
        </p>

        <div v-if="report" class="report" data-testid="constraint-migration-report">
          <h4 class="report-title">
            最近一次运行
            <span class="report-meta">
              {{ report.status === 'DONE' ? '已完成' : report.status }}
              · {{ formatTime(report.finishedAt || report.startedAt) }}
              · {{ report.runBy }}
            </span>
          </h4>

          <p class="migration-copy" data-testid="constraint-migration-report-summary">
            保留 {{ report.retained }} · 转换 {{ report.converted }} · 停用 {{ report.disabled }} ·
            待确认 {{ report.review }}
          </p>

          <h5 class="report-subtitle">草稿校验对比</h5>
          <p
            v-if="report.before.skipped || report.after.skipped"
            class="migration-copy"
            data-testid="constraint-migration-comparison-skipped"
          >
            该学期还没有草稿，本次只记录规则计数，未做违规与扣分对比。
          </p>
          <table
            v-else
            class="migration-table"
            data-testid="constraint-migration-comparison"
          >
            <thead>
              <tr>
                <th>指标</th>
                <th>迁移前</th>
                <th>迁移后</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in comparisonRows" :key="row.label">
                <td>{{ row.label }}</td>
                <td>{{ row.before }}</td>
                <td>{{ row.after }}</td>
              </tr>
            </tbody>
          </table>

          <h5 class="report-subtitle">逐条处理清单</h5>
          <table class="migration-table" data-testid="constraint-migration-report-rows">
            <thead>
              <tr>
                <th>规则</th>
                <th>条目</th>
                <th>处理</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in reportRows" :key="`${row.oldType}-${row.oldEntryId}`">
                <td>{{ row.oldTypeName }}</td>
                <td>{{ row.oldEntryId === 0 ? '类型级' : `#${row.oldEntryId}` }}</td>
                <td>
                  <span class="action-tag" :class="`action-${row.action.toLowerCase()}`">
                    {{ actionLabel(row.action) }}
                  </span>
                </td>
                <td>{{ row.note }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  actionLabel,
  fetchMigrationPreview,
  fetchMigrationReport,
  runConstraintMigration,
  type MigrationPreview,
  type MigrationReport,
  type MigrationRow,
} from '@/api/constraintMigration'
import { serverMessage } from '@/api/errors'

const props = defineProps<{
  semesterId: number | null
  canManage?: boolean
}>()

const emit = defineEmits<{ migrated: [] }>()

const loading = ref(false)
const running = ref(false)
const preview = ref<MigrationPreview | null>(null)
const report = ref<MigrationReport | null>(null)
const error = ref('')

const canRun = computed(
  () => !!props.semesterId && !!props.canManage && !!preview.value && preview.value.rows.length > 0,
)
const isEmpty = computed(
  () => (!preview.value || preview.value.rows.length === 0) && !report.value,
)

/**
 * Review first, then paused, then converted/retained: the rows a human has to act on
 * are the reason the card exists, so they must not be buried under 19 retained rows.
 */
const ACTION_ORDER: Record<string, number> = {
  REVIEW: 0,
  DISABLE: 1,
  CONVERT: 2,
  RETAIN: 3,
}

const reportRows = computed<MigrationRow[]>(() => {
  if (!report.value) return []
  return [...report.value.rows].sort((left, right) => {
    const byAction = (ACTION_ORDER[left.action] ?? 9) - (ACTION_ORDER[right.action] ?? 9)
    if (byAction !== 0) return byAction
    return left.oldTypeName.localeCompare(right.oldTypeName)
  })
})

const comparisonRows = computed(() => {
  if (!report.value) return []
  return [
    rowOf('课节数', report.value.before.lessons, report.value.after.lessons),
    rowOf('硬冲突', report.value.before.hardConflicts, report.value.after.hardConflicts),
    rowOf('软扣分', report.value.before.softPenalty, report.value.after.softPenalty),
    rowOf(
      '启用配置',
      `${report.value.before.enabledConfigCount}/${report.value.before.configCount}`,
      `${report.value.after.enabledConfigCount}/${report.value.after.configCount}`,
    ),
    rowOf(
      '启用条目',
      `${report.value.before.enabledEntryCount}/${report.value.before.entryCount}`,
      `${report.value.after.enabledEntryCount}/${report.value.after.entryCount}`,
    ),
    rowOf('保留行数', report.value.before.retainedRows, report.value.after.retainedRows),
    rowOf('规则指纹', shortFingerprint(report.value.before.settingsFingerprint),
      shortFingerprint(report.value.after.settingsFingerprint)),
    rowOf('保留摘要', shortFingerprint(report.value.before.retainedDigest),
      shortFingerprint(report.value.after.retainedDigest)),
  ]
})

function rowOf(label: string, before: number | string, after: number | string) {
  return { label, before: String(before), after: String(after) }
}

function shortFingerprint(value: string | null | undefined): string {
  return value ? value.slice(0, 8) : '—'
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

async function load() {
  if (!props.semesterId) {
    preview.value = null
    report.value = null
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [previewData, reportData] = await Promise.all([
      fetchMigrationPreview(props.semesterId),
      fetchMigrationReport(props.semesterId),
    ])
    preview.value = previewData
    report.value = reportData
  } catch (e: any) {
    error.value = serverMessage(e, '加载旧规则迁移信息失败')
  } finally {
    loading.value = false
  }
}

async function onRun() {
  if (!props.semesterId || !preview.value) return
  try {
    await ElMessageBox.confirm(
      `将保留 ${preview.value.retained} 类规则、停用 ${preview.value.disabled} 类已删除的规则，` +
        `${preview.value.review} 条规则需要人工确认。旧数据不会被删除，可以重复运行。是否继续？`,
      '运行旧规则迁移',
      { confirmButtonText: '运行迁移', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  running.value = true
  try {
    report.value = await runConstraintMigration(props.semesterId)
    preview.value = await fetchMigrationPreview(props.semesterId)
    ElMessage.success('旧规则迁移完成')
    emit('migrated')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '旧规则迁移失败'))
  } finally {
    running.value = false
  }
}

watch(() => props.semesterId, load, { immediate: true })
</script>

<style scoped>
.migration-card {
  margin-bottom: 16px;
}

.migration-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.migration-title {
  margin: 0 0 4px;
  font-size: 16px;
}

.migration-sub,
.migration-copy {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.counts {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  margin: 12px 0 8px;
  font-size: 14px;
}

.count b {
  font-size: 20px;
  margin-right: 4px;
}

.count-warn {
  color: var(--el-color-warning);
}

.report {
  margin-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 12px;
}

.report-title {
  margin: 0 0 6px;
  font-size: 14px;
}

.report-meta {
  margin-left: 8px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.report-subtitle {
  margin: 16px 0 8px;
  font-size: 13px;
}

.migration-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.migration-table th,
.migration-table td {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
}

.migration-table th {
  color: var(--el-text-color-secondary);
  font-weight: normal;
}

.action-tag {
  display: inline-block;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 20px;
  background: var(--el-fill-color-light);
}

.action-retain {
  color: var(--el-color-success);
}

.action-disable {
  color: var(--el-text-color-secondary);
}

.action-review {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}
</style>
