<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>账号审计</h2>
        <p class="subtitle">
          每一次账号操作都留一条记录：谁做的、对哪个账号、做了什么、改之前和改之后。
        </p>
        <p class="subtitle">
          升级前未记录目标账号 ID 或逐账号明细的记录，无法按账号精确筛选。
        </p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <div class="filter-row">
        <el-form inline>
          <el-form-item label="动作类型">
            <el-select
              v-model="actionFilter"
              aria-label="动作类型"
              data-testid="account-audit-action-filter"
              clearable
              placeholder="全部动作"
              class="filter-select"
              @change="reload"
            >
              <el-option
                v-for="option in actionOptions"
                :key="option.code"
                :label="option.label"
                :value="option.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="目标账号">
            <el-select
              v-model="targetFilter"
              aria-label="目标账号"
              data-testid="account-audit-target-filter"
              clearable
              filterable
              placeholder="全部账号"
              class="account-select"
              @change="reload"
            >
              <el-option
                v-for="account in accounts"
                :key="account.id"
                :label="accountOptionLabel(account)"
                :value="account.id"
              />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <el-empty v-if="!loading && entries.length === 0" description="还没有账号操作记录" />

      <template v-else>
        <el-table v-loading="loading" :data="entries" stripe>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="actor" label="操作者" min-width="120" />
          <el-table-column label="目标账号" min-width="170">
            <template #default="{ row }">
              <span :class="{ muted: !row.targetUserId }">{{ describeAuditTarget(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="动作" width="140">
            <template #default="{ row }">{{ actionLabel(row.action) }}</template>
          </el-table-column>
          <el-table-column label="变更" min-width="320">
            <template #default="{ row }">{{ describeAuditRowChange(row) }}</template>
          </el-table-column>
        </el-table>

        <div class="pagination-row">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :total="total"
            :page-size="pageSize"
            :current-page="page"
            @current-change="changePage"
          />
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import {
  listAccountAuditActions,
  listAccountAudits,
  listAccountOptions,
  type AccountActionOption,
  type AccountAuditEntry,
  type AccountOption,
} from '@/api/accountAudit'
import { describeAuditRowChange, describeAuditTarget } from '@/utils/accountAuditChange'
import { formatDateTime } from '@/utils/datetime'

const pageSize = 20

const loading = ref(false)
const entries = ref<AccountAuditEntry[]>([])
const total = ref(0)
const page = ref(1)
const actionFilter = ref<string>('')
const targetFilter = ref<number | undefined>(undefined)
const actionOptions = ref<AccountActionOption[]>([])
const accounts = ref<AccountOption[]>([])
/** 只让最新一次请求改写列表：快速改筛选时，迟到的旧响应全部丢弃。 */
let requestSeq = 0

function actionLabel(code: string): string {
  // 已知动作按目录翻译；未知历史码给中文兜底，页面不裸露英文码。
  return actionOptions.value.find((option) => option.code === code)?.label ?? '其他账号操作'
}

function accountOptionLabel(account: AccountOption): string {
  return `${account.username}（${account.displayName}）${account.enabled ? '' : ' · 已停用'}`
}

async function load() {
  const seq = ++requestSeq
  loading.value = true
  try {
    const data = await listAccountAudits({
      action: actionFilter.value || undefined,
      targetUserId: targetFilter.value,
      page: page.value,
      size: pageSize,
    })
    if (seq !== requestSeq) {
      return
    }
    entries.value = data.items
    total.value = data.total
  } catch (e: unknown) {
    if (seq !== requestSeq) {
      return
    }
    ElMessage.error(serverMessage(e, '加载账号审计失败'))
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

/** 筛选变化回到第一页，避免停在已不存在的页码上。 */
function reload() {
  page.value = 1
  void load()
}

function changePage(next: number) {
  page.value = next
  void load()
}

async function loadFilters() {
  try {
    actionOptions.value = await listAccountAuditActions()
    accounts.value = await listAccountOptions()
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '加载筛选项失败'))
  }
}

onMounted(async () => {
  await loadFilters()
  await load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}
h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.subtitle {
  margin: 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.filter-row {
  margin-bottom: 8px;
}
.filter-select {
  width: 180px;
}
.account-select {
  width: 240px;
}
.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}
.muted {
  color: #9ca3af;
}
</style>
