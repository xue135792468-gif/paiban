<template>
  <div class="org-sync-preview">
    <el-alert
      :title="preview.message"
      :type="preview.success ? 'info' : 'error'"
      :closable="false"
      show-icon
    />

    <template v-if="preview.success">
      <div class="summary-row">
        <el-tag type="success" size="small">新增 {{ preview.summary.create }}</el-tag>
        <el-tag type="primary" size="small">补齐 {{ preview.summary.merge }}</el-tag>
        <el-tag type="warning" size="small">疑似同名 {{ preview.summary.suspected }}</el-tag>
        <el-tag type="info" size="small">跳过 {{ preview.summary.skip }}</el-tag>
        <el-tag
          v-if="isScanComplete"
          type="danger"
          size="small"
          data-testid="dingtalk-deactivation-tag"
        >
          将停用 {{ selectedDeactivations.length }}
        </el-tag>
        <span class="spacer" />
        <el-button v-if="hasSuspectedCandidates" link type="primary" @click="mergeAll">
          全部合并同名教师
        </el-button>
        <el-button
          type="primary"
          size="small"
          :loading="confirming"
          :disabled="confirmCount === 0 && selectedDeactivations.length === 0"
          data-testid="dingtalk-confirm"
          @click="confirm"
        >
          {{ confirming ? '正在写入（约 1 分钟）' : confirmButtonLabel }}
        </el-button>
      </div>

      <p v-if="preview.skippedDepartmentNames.length" class="hint">
        已跳过特殊部门：{{ preview.skippedDepartmentNames.join('、') }}
      </p>
      <p v-if="preview.failedDepartments > 0" class="hint">
        有 {{ preview.failedDepartments }} 个部门无权限读取，已跳过。
      </p>

      <el-alert
        v-if="deactivationWarningMessage"
        :title="deactivationWarningMessage"
        type="warning"
        :closable="false"
        show-icon
        class="deactivation-warning"
        data-testid="dingtalk-deactivation-warning"
      />

      <div
        v-if="isScanComplete && deactivationCandidates.length > 0"
        class="deactivation-section"
        data-testid="dingtalk-deactivation-section"
      >
        <div class="deactivation-head">
          <span class="deactivation-title">
            将停用账号（已选 {{ selectedDeactivations.length }} / {{ deactivationCandidates.length }}）
          </span>
          <span class="muted">
            不在本次钉钉可见范围；停用后无法登录，不删除教师档案与课表，可在账号页重新启用。
          </span>
        </div>
        <el-table :data="deactivationCandidates" size="small" border>
          <el-table-column width="44" align="center">
            <template #default="{ row }">
              <el-checkbox
                :model-value="isDeactivationIncluded(row.accountId)"
                :data-testid="`dingtalk-deactivate-checkbox-${row.accountId}`"
                @change="toggleDeactivation(row.accountId)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="accountName" label="账号" width="140" />
          <el-table-column prop="displayName" label="姓名" width="120" />
          <el-table-column prop="teacherName" label="关联教师" width="120" />
          <el-table-column prop="dingtalkUserId" label="钉钉 UserId" width="160" />
          <el-table-column label="说明">
            <template #default>
              <span>不在本次钉钉可见范围，确认后停用账号</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-collapse v-if="preview.departments.length" :default-expanded-names="defaultExpanded" class="groups">
        <el-collapse-item
          v-for="group in preview.departments"
          :key="group.department"
          :name="group.department"
        >
          <template #title>
            <span class="group-head" @click.stop>
              <el-checkbox
                :model-value="!excludedDepartments.includes(group.department)"
                @change="toggleDepartment(group.department)"
              />
              <span class="group-name">{{ group.department }}</span>
              <span class="group-counts">{{ groupCounts(group.items) }}</span>
            </span>
          </template>

          <el-table :data="group.items" size="small" border>
            <el-table-column width="44" align="center">
              <template #default="{ row }">
                <el-checkbox
                  :model-value="included(row)"
                  :disabled="row.category === 'SKIP'"
                  @change="toggleUser(row)"
                />
              </template>
            </el-table-column>
            <el-table-column prop="name" label="姓名" width="120" />
            <el-table-column label="工号 / 手机" width="190">
              <template #default="{ row }">
                <div>{{ row.employeeNo || '—' }}</div>
                <div class="muted">{{ row.phone || '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="类别" width="104">
              <template #default="{ row }">
                <el-tag :type="tagType(row.category)" size="small">
                  {{ tagLabel(row.category) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明">
              <template #default="{ row }">
                <div>{{ row.reason }}</div>
                <div v-if="row.fills.length" class="fills">补齐：{{ changesText(row.fills) }}</div>
                <div v-if="row.conflicts.length" class="conflicts">
                  冲突未覆盖：{{ changesText(row.conflicts) }}
                </div>
                <div v-if="row.category === 'SUSPECTED'" class="decision">
                  <el-select
                    v-model="decisions[row.userId].action"
                    size="small"
                    style="width: 148px"
                  >
                    <el-option label="本次跳过" value="SKIP" />
                    <el-option label="合并到同名教师" value="MERGE" />
                    <el-option label="作为新教师导入" value="CREATE" />
                  </el-select>
                  <el-select
                    v-if="decisions[row.userId].action === 'MERGE'"
                    v-model="decisions[row.userId].teacherId"
                    size="small"
                    style="width: 260px"
                  >
                    <el-option
                      v-for="candidate in row.candidates"
                      :key="candidate.id"
                      :value="candidate.id"
                      :label="candidateLabel(candidate)"
                    />
                  </el-select>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>

      <p v-else-if="isScanComplete && deactivationCandidates.length > 0" class="hint">
        本次没有新增或补齐的教师，仅处理上方待停用账号。
      </p>
      <p v-else class="hint">没有可导入的用户。</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { isUserIncluded } from '@/api/integration'
import type {
  DeactivationCandidate,
  SyncCandidate,
  SyncConfirmRequest,
  SyncFieldChange,
  SyncItem,
  SyncPreview,
  SuspectedAction,
} from '@/api/integration'

const props = defineProps<{ preview: SyncPreview; confirming: boolean }>()
const emit = defineEmits<{ confirm: [request: SyncConfirmRequest] }>()

const excludedDepartments = ref<string[]>([])
const excludedUserIds = ref<string[]>([])
const excludedDeactivationAccountIds = ref<number[]>([])
const decisions = reactive<Record<string, { action: SuspectedAction; teacherId?: number }>>({})

watch(
  () => props.preview,
  (preview) => {
    excludedDepartments.value = []
    excludedUserIds.value = []
    excludedDeactivationAccountIds.value = []
    Object.keys(decisions).forEach((key) => delete decisions[key])
    for (const item of allItems(preview)) {
      if (item.category === 'SUSPECTED') {
        decisions[item.userId] = { action: 'SKIP', teacherId: item.candidates[0]?.id }
      }
    }
  },
  { immediate: true },
)

const isScanComplete = computed(
  () => props.preview.scanComplete === true && props.preview.failedDepartments === 0,
)

const deactivationWarningMessage = computed<string | null>(() => {
  const explicit = props.preview.deactivationWarning?.trim()
  if (explicit) return explicit
  if (!isScanComplete.value) {
    if (props.preview.failedDepartments > 0) {
      return `有 ${props.preview.failedDepartments} 个部门读取失败，通讯录扫描不完整，为防误停用，本次不生成待停用账号名单。`
    }
    return '通讯录扫描不完整，为防误停用，本次不生成待停用账号名单。'
  }
  return null
})

const deactivationCandidates = computed<DeactivationCandidate[]>(() =>
  isScanComplete.value ? (props.preview.deactivations ?? []) : [],
)

const selectedDeactivations = computed<DeactivationCandidate[]>(() =>
  deactivationCandidates.value.filter(
    (candidate) => !excludedDeactivationAccountIds.value.includes(candidate.accountId),
  ),
)

const hasSuspectedCandidates = computed(() =>
  allItems(props.preview).some(
    (item) => item.category === 'SUSPECTED' && item.candidates.length > 0,
  ),
)

const defaultExpanded = computed(() => props.preview.departments.map((group) => group.department))

const confirmCount = computed(() => {
  let total = 0
  for (const item of allItems(props.preview)) {
    if (!included(item)) continue
    if (item.category === 'CREATE' || item.category === 'MERGE') {
      total++
    } else if (item.category === 'SUSPECTED') {
      const decision = decisions[item.userId]
      if (decision && decision.action !== 'SKIP') total++
    }
  }
  return total
})

const confirmButtonLabel = computed(() => {
  const importTotal = confirmCount.value
  const deactivateTotal = selectedDeactivations.value.length
  if (importTotal > 0 && deactivateTotal > 0) {
    return `确认导入 ${importTotal} 人、停用 ${deactivateTotal} 个账号`
  }
  if (importTotal === 0 && deactivateTotal > 0) {
    return `确认停用 ${deactivateTotal} 个账号`
  }
  return `确认导入 ${importTotal} 人`
})

function allItems(preview: SyncPreview): SyncItem[] {
  return preview.departments.flatMap((group) => group.items)
}

function included(item: SyncItem): boolean {
  return isUserIncluded(item, excludedDepartments.value, excludedUserIds.value)
}

function toggleDepartment(department: string) {
  excludedDepartments.value = excludedDepartments.value.includes(department)
    ? excludedDepartments.value.filter((value) => value !== department)
    : [...excludedDepartments.value, department]
}

function toggleUser(item: SyncItem) {
  excludedUserIds.value = excludedUserIds.value.includes(item.userId)
    ? excludedUserIds.value.filter((value) => value !== item.userId)
    : [...excludedUserIds.value, item.userId]
}

function isDeactivationIncluded(accountId: number): boolean {
  return !excludedDeactivationAccountIds.value.includes(accountId)
}

function toggleDeactivation(accountId: number) {
  excludedDeactivationAccountIds.value = excludedDeactivationAccountIds.value.includes(accountId)
    ? excludedDeactivationAccountIds.value.filter((value) => value !== accountId)
    : [...excludedDeactivationAccountIds.value, accountId]
}

function mergeAll() {
  for (const item of allItems(props.preview)) {
    if (item.category === 'SUSPECTED' && item.candidates.length > 0) {
      decisions[item.userId] = { action: 'MERGE', teacherId: item.candidates[0].id }
    }
  }
}

async function confirm() {
  const currentDeactivations = selectedDeactivations.value.map((candidate) => ({
    accountId: candidate.accountId,
    teacherId: candidate.teacherId,
    dingtalkUserId: candidate.dingtalkUserId,
  }))
  if (currentDeactivations.length > 0) {
    try {
      await ElMessageBox.confirm(
        `将停用 ${currentDeactivations.length} 个账号。停用后无法登录，不删除教师档案、不改动课表，后续可在账号页重新启用。确定继续？`,
        '确认同步停用',
        {
          confirmButtonText: '确认停用',
          cancelButtonText: '取消',
          type: 'warning',
        },
      )
    } catch {
      return
    }
  }
  const suspectedDecisions = Object.entries(decisions).map(([userId, decision]) => ({
    userId,
    action: decision.action,
    teacherId: decision.teacherId,
  }))
  emit('confirm', {
    excludedDepartments: excludedDepartments.value,
    excludedUserIds: excludedUserIds.value,
    suspectedDecisions,
    deactivationSelections: currentDeactivations,
  })
}

function groupCounts(items: SyncItem[]): string {
  const count = (category: string) => items.filter((item) => item.category === category).length
  const parts = [
    `新增 ${count('CREATE')}`,
    `补齐 ${count('MERGE')}`,
    `疑似 ${count('SUSPECTED')}`,
    `跳过 ${count('SKIP')}`,
  ]
  return parts.join(' · ')
}

function tagType(category: string): 'success' | 'primary' | 'warning' | 'info' {
  if (category === 'CREATE') return 'success'
  if (category === 'MERGE') return 'primary'
  if (category === 'SUSPECTED') return 'warning'
  return 'info'
}

function tagLabel(category: string): string {
  if (category === 'CREATE') return '新增'
  if (category === 'MERGE') return '补齐'
  if (category === 'SUSPECTED') return '疑似同名'
  return '跳过'
}

function changesText(changes: SyncFieldChange[]): string {
  return changes
    .map((change) =>
      change.localValue == null
        ? `${change.label} ${change.remoteValue ?? ''}`
        : `${change.label}「${change.localValue}」→「${change.remoteValue ?? ''}」`,
    )
    .join('；')
}

function candidateLabel(candidate: SyncCandidate): string {
  return `${candidate.name}（工号 ${candidate.employeeNo || '—'} / 手机 ${candidate.phone || '—'}）`
}
</script>

<style scoped>
.org-sync-preview {
  margin-top: 12px;
}
.summary-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.spacer {
  flex: 1;
}
.hint {
  margin: 8px 0 0;
  color: var(--paiban-muted);
  font-size: 12px;
}
.groups {
  margin-top: 8px;
}
.deactivation-warning {
  margin-top: 8px;
}
.deactivation-section {
  margin-top: 10px;
}
.deactivation-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}
.deactivation-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--el-color-danger);
}
.group-head {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.group-name {
  font-weight: 600;
}
.group-counts {
  color: var(--paiban-muted);
  font-size: 12px;
}
.muted {
  color: var(--paiban-muted);
  font-size: 12px;
}
.fills {
  color: var(--el-color-success);
  font-size: 12px;
}
.conflicts {
  color: var(--el-color-warning);
  font-size: 12px;
}
.decision {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
</style>
