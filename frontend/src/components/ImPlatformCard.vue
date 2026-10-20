<template>
  <el-card shadow="never" data-testid="im-platform-card">
    <template #header>
      <div class="card-head">
        <span class="card-title">IM 平台</span>
        <el-tag size="small" :type="status.activePlatform === 'none' ? 'info' : 'success'">
          当前：{{ label(status.activePlatform) }}
        </el-tag>
      </div>
    </template>

    <div class="platform-row">
      <div class="row-label">启用平台</div>
      <el-radio-group v-model="selected">
        <el-radio-button value="none">不启用</el-radio-button>
        <el-radio-button value="dingtalk">钉钉</el-radio-button>
        <el-radio-button value="feishu">飞书</el-radio-button>
      </el-radio-group>
    </div>

    <div class="status-row">
      <div class="status-item">
        <span class="status-name">钉钉</span>
        <el-tag size="small" :type="status.dingtalkConfigured ? 'success' : 'info'">
          {{ status.dingtalkConfigured ? '已配置' : '未配置' }}
        </el-tag>
        <el-button link type="primary" @click="scrollTo('dingtalk-integration')">去配置</el-button>
      </div>
      <div class="status-item">
        <span class="status-name">飞书</span>
        <el-tag size="small" :type="status.feishuConfigured ? 'success' : 'info'">
          {{ status.feishuConfigured ? '已配置' : '未配置' }}
        </el-tag>
        <el-button link type="primary" @click="scrollTo('feishu-integration')">去配置</el-button>
      </div>
    </div>

    <div class="actions">
      <el-button
        type="primary"
        data-testid="platform-switch-button"
        :loading="switching"
        :disabled="selected === status.activePlatform"
        @click="confirmSwitch"
      >
        切换平台
      </el-button>
      <span class="hint">
        切换会停用原平台并触发新平台组织同步；班级、课程、课表与调代数据完整保留。
      </span>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'

interface PlatformStatus {
  activePlatform: string
  dingtalkEnabled: boolean
  feishuEnabled: boolean
  dingtalkConfigured: boolean
  feishuConfigured: boolean
}

const LABELS: Record<string, string> = { none: '不启用', dingtalk: '钉钉', feishu: '飞书' }

const emit = defineEmits<{ switched: [status: PlatformStatus] }>()

const status = ref<PlatformStatus>({
  activePlatform: 'none',
  dingtalkEnabled: false,
  feishuEnabled: false,
  dingtalkConfigured: false,
  feishuConfigured: false,
})
const selected = ref('none')
const switching = ref(false)

function label(value: string) {
  return LABELS[value] ?? value
}

async function load() {
  const { data } = await http.get('/integrations/platform')
  status.value = data
  selected.value = data.activePlatform
}

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** The migration notice is spelled out for the common DingTalk -> Feishu move. */
function confirmText(target: string) {
  const active = status.value.activePlatform
  if (target === 'feishu') {
    return active === 'dingtalk'
      ? '将停用钉钉，启用飞书。教职工数据保留，需重新绑定飞书账号。'
      : '将启用飞书。教职工数据保留，需重新绑定飞书账号。'
  }
  if (target === 'dingtalk') {
    return active === 'feishu'
      ? '将停用飞书，启用钉钉。教职工数据保留，需重新绑定钉钉账号。'
      : '将启用钉钉。教职工数据保留，需重新绑定钉钉账号。'
  }
  return '将停用当前平台，系统仅保留本地账号与系统内通知。教职工数据保留。'
}

async function confirmSwitch() {
  const target = selected.value
  try {
    await ElMessageBox.confirm(confirmText(target), '切换 IM 平台', {
      confirmButtonText: '确认切换',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  switching.value = true
  try {
    const { data } = await http.put('/integrations/platform', { platform: target })
    status.value = data
    selected.value = data.activePlatform
    ElMessage.success(`已切换到${label(data.activePlatform)}`)
    emit('switched', data)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '切换失败')
  } finally {
    switching.value = false
  }
}

onMounted(load)

defineExpose({ reload: load })
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.card-title {
  font-weight: 600;
}
.platform-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 6px 0 14px;
}
.row-label {
  font-size: 14px;
  color: var(--paiban-muted);
}
.status-row {
  display: flex;
  gap: 32px;
  padding: 10px 0 14px;
  border-top: 1px dashed #eef0f5;
  border-bottom: 1px dashed #eef0f5;
}
.status-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.status-name {
  font-weight: 600;
  font-size: 14px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-top: 14px;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
}
</style>
