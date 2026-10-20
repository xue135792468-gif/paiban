<template>
  <div class="settings-page">
    <div class="toolbar">
      <div>
        <h2>设置中心</h2>
        <p class="subtitle">所有配置保存后立即生效，无需重启服务。</p>
      </div>
      <el-button type="primary" :loading="saving" @click="saveSettings">保存设置</el-button>
    </div>

    <el-card shadow="never">
      <template #header><span class="card-title">学校信息</span></template>
      <el-form label-width="110px" class="form">
        <el-form-item label="学校名称">
          <el-input v-model="form['school.name']" placeholder="用于课表导出与 PDF 页眉" />
        </el-form-item>
        <el-form-item label="学校 Logo">
          <div class="logo-row">
            <el-upload :auto-upload="false" :show-file-list="false" accept="image/png,image/jpeg" :on-change="uploadLogo">
              <el-button :loading="uploading">上传图片</el-button>
            </el-upload>
            <span class="logo-path">{{ logoName || '未上传' }}</span>
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header><span class="card-title">调代与通知</span></template>
      <el-form label-width="110px" class="form">
        <el-form-item label="审批模式">
          <el-radio-group v-model="form['substitution.approval-mode']">
            <el-radio-button value="approval">审批模式</el-radio-button>
            <el-radio-button value="direct">直接生效</el-radio-button>
          </el-radio-group>
          <div class="hint">切换只影响新提交的申请，已在流程中的申请按提交时的模式走完。</div>
        </el-form-item>
        <el-form-item label="通知范围">
          <el-select v-model="form['notification.scope']" style="width: 260px">
            <el-option label="仅当事人（原教师 + 代课教师）" value="minimal" />
            <el-option label="含班主任（一期暂无年级组长模型）" value="with-grade-leader" />
            <el-option label="含教务（增加教务管理员）" value="with-admin" />
          </el-select>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">钉钉集成</span>
          <el-tag :type="dingtalk.enabled ? 'success' : 'info'" size="small">
            {{ dingtalk.enabled ? '已启用' : '未启用' }}
          </el-tag>
        </div>
      </template>
      <el-form label-width="110px" class="form">
        <el-form-item label="启用集成">
          <el-switch v-model="dingtalk.enabled" />
          <span class="hint inline">关闭后系统完整可用，仅使用本地账号与系统内通知。</span>
        </el-form-item>
        <el-form-item label="AppKey">
          <el-input v-model="dingtalk.appKey" placeholder="钉钉企业内部应用 AppKey" />
        </el-form-item>
        <el-form-item label="AppSecret">
          <el-input
            v-model="dingtalk.appSecret"
            type="password"
            show-password
            :placeholder="dingtalk.hasSecret ? '已配置，不修改请留空' : '请输入 AppSecret'"
          />
        </el-form-item>
        <el-form-item label="AgentId">
          <el-input v-model="dingtalk.agentId" placeholder="微应用 AgentId" />
        </el-form-item>
        <el-form-item label="CorpId">
          <el-input v-model="dingtalk.corpId" placeholder="企业 CorpId，H5 免登需要" />
        </el-form-item>
        <el-form-item label="同步策略">
          <el-select v-model="dingtalk.syncStrategy" style="width: 220px">
            <el-option label="钉钉为准（覆盖本地）" value="dingtalk" />
            <el-option label="本地为准（只补空字段）" value="local" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingDingtalk" @click="saveDingtalk">保存钉钉配置</el-button>
          <el-button :loading="syncing" :disabled="!dingtalk.enabled" @click="sync">立即同步教职工</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="syncResult" :title="syncResult.message" type="success" :closable="false" show-icon class="sync-result" />
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
    </el-card>

    <el-card shadow="never">
      <template #header><span class="card-title">AI 特性</span></template>
      <div class="ai-row">
        <div>
          <div class="ai-name">代课人选推荐</div>
          <div class="hint">根据历史代课与空闲时段推荐代课教师</div>
        </div>
        <el-switch :model-value="form['ai.features.substitute-recommendation'] === 'true'" disabled />
        <el-tag size="small" type="info">即将推出</el-tag>
      </div>
      <div class="ai-row">
        <div>
          <div class="ai-name">自然语言生成约束</div>
          <div class="hint">用一句话描述排课规则，自动转换为约束配置</div>
        </div>
        <el-switch :model-value="form['ai.features.natural-language-constraints'] === 'true'" disabled />
        <el-tag size="small" type="info">即将推出</el-tag>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import { http } from '@/api/http'

interface DingTalkConfig {
  enabled: boolean
  appKey: string
  appSecret?: string
  hasSecret: boolean
  agentId: string
  corpId?: string
  syncStrategy: string
}

interface SyncLog {
  id: number
  createdAt: string
  detail: string
  successRows: number
}

const form = reactive<Record<string, string>>({})
const dingtalk = reactive<DingTalkConfig>({
  enabled: false,
  appKey: '',
  appSecret: '',
  hasSecret: false,
  agentId: '',
  corpId: '',
  syncStrategy: 'dingtalk',
})
const logs = ref<SyncLog[]>([])
const syncResult = ref<{ created: number; updated: number; deactivated: number; message: string } | null>(null)
const saving = ref(false)
const savingDingtalk = ref(false)
const syncing = ref(false)
const uploading = ref(false)
const loadingLogs = ref(false)

const logoName = computed(() => {
  const path = form['school.logo-path']
  return path ? path.split(/[\\/]/).pop() : ''
})

function formatTime(value: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

async function loadSettings() {
  const { data } = await http.get('/settings')
  Object.keys(data).forEach((key) => {
    form[key] = data[key]
  })
}

async function loadDingtalk() {
  const { data } = await http.get('/integrations/dingtalk/config')
  Object.assign(dingtalk, data)
  dingtalk.appSecret = ''
}

async function loadLogs() {
  loadingLogs.value = true
  try {
    const { data } = await http.get('/integrations/dingtalk/logs')
    logs.value = data
  } finally {
    loadingLogs.value = false
  }
}

async function saveSettings() {
  saving.value = true
  try {
    const { data } = await http.put('/settings', { ...form })
    Object.keys(data).forEach((key) => {
      form[key] = data[key]
    })
    ElMessage.success('设置已保存并立即生效')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function saveDingtalk() {
  savingDingtalk.value = true
  try {
    const payload: Record<string, unknown> = {
      enabled: dingtalk.enabled,
      appKey: dingtalk.appKey,
      agentId: dingtalk.agentId,
      corpId: dingtalk.corpId,
      syncStrategy: dingtalk.syncStrategy,
    }
    if (dingtalk.appSecret) payload.appSecret = dingtalk.appSecret
    const { data } = await http.put('/integrations/dingtalk/config', payload)
    Object.assign(dingtalk, data)
    dingtalk.appSecret = ''
    ElMessage.success('钉钉配置已保存')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '保存失败')
  } finally {
    savingDingtalk.value = false
  }
}

async function sync() {
  syncing.value = true
  try {
    const { data } = await http.post('/integrations/dingtalk/sync')
    syncResult.value = data
    ElMessage.success(data.message)
    await loadLogs()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '同步失败')
  } finally {
    syncing.value = false
  }
}

async function uploadLogo(upload: UploadFile) {
  if (!upload.raw) return
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', upload.raw)
    const { data } = await http.post('/settings/logo', body)
    Object.keys(data).forEach((key) => {
      form[key] = data[key]
    })
    ElMessage.success('Logo 已上传')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? '上传失败')
  } finally {
    uploading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadSettings(), loadDingtalk(), loadLogs()])
})
</script>

<style scoped>
.settings-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 960px;
}
.toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
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
.card-title {
  font-weight: 600;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.form {
  max-width: 720px;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 6px;
}
.hint.inline {
  margin: 0 0 0 12px;
}
.logo-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.logo-path {
  color: var(--paiban-muted);
  font-size: 12px;
}
.sync-result {
  margin-bottom: 12px;
}
.logs {
  margin-top: 8px;
}
.ai-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px dashed #eef0f5;
}
.ai-row:last-child {
  border-bottom: none;
}
.ai-row > div:first-child {
  flex: 1;
}
.ai-name {
  font-weight: 600;
  font-size: 14px;
}
</style>
