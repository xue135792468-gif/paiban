<template>
  <el-card shadow="never">
    <template #header><span class="card-title">通知发送记录</span></template>
    <el-collapse
      v-model="expanded"
      data-testid="notification-delivery-logs-collapse"
      @change="onExpanded"
    >
      <el-collapse-item title="查看发送结果" name="logs">
        <el-alert
          title="失败记录只显示固定安全说明，不展示第三方原始错误或响应内容。"
          type="info"
          :closable="false"
          show-icon
          class="safety-note"
        />
        <el-alert
          v-if="loadError"
          title="加载通知发送记录失败，请稍后重试。"
          type="error"
          :closable="false"
          show-icon
          class="load-error"
        />
        <el-empty
          v-if="loaded && !loading && logs.length === 0"
          description="还没有通知发送记录"
        />
        <template v-else>
          <el-table v-loading="loading" :data="logs" size="small" border>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="recipientLabel" label="收件人" width="120" show-overflow-tooltip />
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <el-tag :type="deliveryStatus(row).type" size="small">
                  {{ deliveryStatus(row).label }}
                </el-tag>
                <div v-if="isUnknownChannel(row)" class="channel-name">
                  {{ row.channel || '—' }}
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="templateCode" label="模板" width="135" show-overflow-tooltip />
            <el-table-column prop="content" label="内容" min-width="200" show-overflow-tooltip />
          </el-table>
          <div v-if="total > pageSize" class="pagination-row">
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
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { formatDateTime } from '@/utils/datetime'
import {
  listNotificationDeliveryLogs,
  type NotificationDeliveryLog,
} from '@/api/notificationDeliveryLogs'

type TagType = 'success' | 'danger' | 'info'
type CollapseValue = string | number | Array<string | number>

const pageSize = 20
const expanded = ref<string[]>([])
const loading = ref(false)
const loaded = ref(false)
const loadError = ref(false)
const logs = ref<NotificationDeliveryLog[]>([])
const total = ref(0)
const page = ref(1)
let requestSeq = 0

function deliveryStatus(row: NotificationDeliveryLog): { label: string; type: TagType } {
  if (!row.success) {
    return { label: '失败', type: 'danger' }
  }
  if (row.channel === 'dingtalk') {
    return { label: '钉钉已受理', type: 'success' }
  }
  if (row.channel === 'local') {
    return { label: '系统已保存', type: 'success' }
  }
  return { label: '已受理', type: 'info' }
}

function isUnknownChannel(row: NotificationDeliveryLog): boolean {
  return row.channel !== 'dingtalk' && row.channel !== 'local'
}

async function loadPage() {
  const seq = ++requestSeq
  loading.value = true
  loadError.value = false
  try {
    const result = await listNotificationDeliveryLogs({
      page: page.value - 1,
      size: pageSize,
    })
    if (seq !== requestSeq) {
      return
    }
    logs.value = result.items
    total.value = result.total
    loaded.value = true
  } catch {
    if (seq !== requestSeq) {
      return
    }
    loadError.value = true
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

function onExpanded(names: CollapseValue) {
  const opened = Array.isArray(names) ? names.includes('logs') : names === 'logs'
  if (opened && !loaded.value && !loading.value) {
    void loadPage()
  }
}

function changePage(nextPage: number) {
  page.value = nextPage
  void loadPage()
}
</script>

<style scoped>
.card-title {
  font-weight: 600;
}
.safety-note {
  margin-bottom: 12px;
}
.load-error {
  margin-bottom: 12px;
}
.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}
.channel-name {
  margin-top: 4px;
  color: var(--paiban-muted);
  font-size: 11px;
  word-break: break-all;
}
</style>
