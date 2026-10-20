<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>通知中心</h2>
        <p class="subtitle">未读 {{ unread }} 条 · 共 {{ total }} 条</p>
      </div>
      <div class="actions">
        <el-button :disabled="unread === 0" @click="markAll">全部标记已读</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-card v-loading="loading" shadow="never">
      <el-empty v-if="items.length === 0" description="暂无通知" />
      <div
        v-for="item in items"
        :key="item.id"
        class="notification"
        :class="{ unread: !item.read }"
        @click="open(item)"
      >
        <div class="notification-main">
          <div class="notification-title">
            <span v-if="!item.read" class="dot" />
            {{ item.title }}
          </div>
          <div class="notification-content">{{ item.content }}</div>
        </div>
        <div class="notification-side">
          <el-tag size="small" :type="item.read ? 'info' : 'primary'" effect="plain">
            {{ item.read ? '已读' : '未读' }}
          </el-tag>
          <span class="time">{{ formatTime(item.createdAt) }}</span>
        </div>
      </div>
    </el-card>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'

interface NotificationItem {
  id: number
  templateCode: string
  title: string
  content: string
  link?: string
  read: boolean
  createdAt: string
}

const router = useRouter()
const items = ref<NotificationItem[]>([])
const total = ref(0)
const unread = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)

function formatTime(value: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

async function load() {
  loading.value = true
  try {
    const [pageRes, unreadRes] = await Promise.all([
      http.get('/notifications', { params: { page: page.value - 1, size } }),
      http.get('/notifications/unread-count'),
    ])
    items.value = pageRes.data.content
    total.value = pageRes.data.total
    unread.value = unreadRes.data.count
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '加载通知失败')
  } finally {
    loading.value = false
  }
}

async function open(item: NotificationItem) {
  if (!item.read) {
    await http.post(`/notifications/${item.id}/read`)
    item.read = true
    unread.value = Math.max(0, unread.value - 1)
  }
  if (item.link) {
    router.push(item.link)
  }
}

async function markAll() {
  await http.post('/notifications/read-all')
  ElMessage.success('已全部标记为已读')
  await load()
}

onMounted(load)
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
.actions {
  display: flex;
  gap: 8px;
}
.notification {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 8px;
  border-bottom: 1px dashed #eef0f5;
  cursor: pointer;
  border-radius: 8px;
}
.notification:hover {
  background: #f8faff;
}
.notification.unread {
  background: #fbfcff;
}
.notification-title {
  font-weight: 600;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #4f6ef7;
  display: inline-block;
}
.notification-content {
  color: #5b6472;
  font-size: 13px;
  margin-top: 5px;
  line-height: 1.7;
}
.notification-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}
.time {
  font-size: 12px;
  color: #9ca3af;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
