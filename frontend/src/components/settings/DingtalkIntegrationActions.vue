<template>
  <div class="dingtalk-actions">
    <el-form-item>
      <el-button type="primary" :loading="saving" data-testid="dingtalk-save" @click="emit('save')">
        保存钉钉配置
      </el-button>
      <el-button :loading="previewing" :disabled="!enabled" @click="emit('preview')">
        预演同步
      </el-button>
      <el-button
        type="danger"
        plain
        data-testid="dingtalk-clear"
        :loading="clearing"
        @click="clear"
      >
        清除配置
      </el-button>
      <span class="hint inline">只补齐空字段，冲突与疑似同名在预演中确认</span>
    </el-form-item>

    <el-collapse class="logs">
      <el-collapse-item title="同步日志" name="logs">
        <el-table v-loading="loadingLogs" :data="logs" size="small" border>
          <el-table-column prop="createdAt" label="时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="detail" label="详情" />
          <el-table-column prop="successRows" label="成功" width="80" />
        </el-table>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

interface SyncLog {
  id: number
  createdAt: string
  detail: string
  successRows: number
}

defineProps<{
  saving: boolean
  previewing: boolean
  enabled: boolean
  loadingLogs: boolean
  logs: SyncLog[]
}>()

const emit = defineEmits<{
  save: []
  preview: []
  cleared: []
}>()

const clearing = ref(false)

/**
 * Ticket 04: clearing the keys enters the 7-day recycle bin. The secret is kept in
 * the recycle snapshot only; it never shows up in the operation log.
 */
async function clear() {
  try {
    await ElMessageBox.confirm(
      '清除后钉钉集成立即停用，AppKey / AppSecret 等配置会进入「最近删除」，7 天内可以恢复。确定清除？',
      '清除钉钉配置',
      { confirmButtonText: '清除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  clearing.value = true
  try {
    await http.delete('/integrations/dingtalk/config')
    ElMessage.success('钉钉配置已清除，可在「最近删除」中恢复')
    emit('cleared')
  } catch (e: unknown) {
    ElMessage.error(serverMessage(e, '清除失败'))
  } finally {
    clearing.value = false
  }
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.logs {
  margin-top: 8px;
}
</style>
