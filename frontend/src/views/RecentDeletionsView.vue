<template>
  <div>
    <div class="toolbar">
      <div>
        <h2>最近删除</h2>
        <p class="subtitle">删除的数据保留 7 天，恢复后回到原来的位置；过期后不再出现在这里。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <div class="filter-row">
        <el-checkbox v-model="includeClosed" @change="load">显示已恢复 / 已过期</el-checkbox>
      </div>

      <el-empty v-if="!loading && batches.length === 0" description="最近删除是空的" />

      <el-table v-else v-loading="loading" :data="batches" stripe>
        <el-table-column label="名称" min-width="220">
          <template #default="{ row }">
            <span>{{ row.label }}</span>
            <el-tag v-if="row.itemCount > 1" size="small" type="info" class="count-tag">
              共 {{ row.itemCount }} 项
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="150">
          <template #default="{ row }">
            <el-tag v-for="type in row.entityTypes" :key="type" size="small" class="type-tag">
              {{ typeLabel(type) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="删除时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.deletedAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'PENDING'" type="warning">
              剩余 {{ row.daysLeft }} 天
            </el-tag>
            <el-tag v-else-if="row.status === 'RESTORED'" type="success">已恢复</el-tag>
            <el-tag v-else type="info">已过期</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              link
              type="primary"
              data-testid="restore-batch"
              :loading="restoring === row.batchId"
              @click="restore(row)"
            >
              恢复
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import {
  LIFECYCLE_ENTITY_LABEL,
  groupRecentDeletions,
  listRecentDeletions,
  restoreDeletion,
  type LifecycleLabelType,
  type RecentDeletionBatch,
} from '@/api/lifecycle'

const loading = ref(false)
const includeClosed = ref(false)
const restoring = ref('')
const batches = ref<RecentDeletionBatch[]>([])

function typeLabel(entityType: string): string {
  const key = entityType.toLowerCase() as LifecycleLabelType
  return LIFECYCLE_ENTITY_LABEL[key] ?? entityType
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const rows = await listRecentDeletions(includeClosed.value)
    batches.value = groupRecentDeletions(rows)
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '加载最近删除失败'))
  } finally {
    loading.value = false
  }
}

async function restore(batch: RecentDeletionBatch) {
  restoring.value = batch.batchId
  try {
    await restoreDeletion(batch.batchId)
    ElMessage.success(`已恢复「${batch.label}」`)
    await load()
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '恢复失败'))
  } finally {
    restoring.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 16px;
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
  margin-bottom: 12px;
}
.count-tag {
  margin-left: 8px;
}
.type-tag {
  margin-right: 4px;
}
.muted {
  color: var(--paiban-muted);
}
</style>
