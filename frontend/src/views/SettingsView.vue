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
        <el-form-item>
          <template #label>
            学校类型
            <InfoTooltip field="settings.schoolType" />
          </template>
          <el-radio-group v-model="form['school.type']">
            <el-radio-button value="junior">初中</el-radio-button>
            <el-radio-button value="senior">高中</el-radio-button>
          </el-radio-group>
          <div class="hint">初中模式会隐藏权重、模板、多草稿对比等高级功能。</div>
        </el-form-item>
        <el-form-item label="学校 Logo">
          <div class="logo-row">
            <el-upload :auto-upload="false" :show-file-list="false" accept="image/png,image/jpeg" :on-change="uploadLogo">
              <el-button :loading="uploading">上传图片</el-button>
            </el-upload>
            <span class="logo-path">{{ logoName || '未上传' }}</span>
          </div>
        </el-form-item>
        <el-form-item label="外部访问地址">
          <el-input v-model="form['public.base-url']" placeholder="如 https://paiban.school.cn，用于通知里的跳转链接" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header><span class="card-title">调代与通知</span></template>
      <el-form label-width="110px" class="form">
        <el-form-item>
          <template #label>
            审批模式
            <InfoTooltip field="substitution.approvalMode" />
          </template>
          <el-radio-group v-model="form['substitution.approval-mode']">
            <el-radio-button value="approval">审批模式</el-radio-button>
            <el-radio-button value="direct">直接生效</el-radio-button>
          </el-radio-group>
          <div class="hint">切换只影响新提交的申请，已在流程中的申请按提交时的模式走完。</div>
        </el-form-item>
        <el-form-item label="通知范围">
          <el-select v-model="form['notification.scope']" style="width: 260px">
            <el-option label="当事人（原教师 + 代课教师 + 班主任）" value="minimal" />
            <el-option label="含年级组长（一期未建模，等同当事人）" value="with-grade-leader" />
            <el-option label="含教务（增加教务管理员）" value="with-admin" />
          </el-select>
        </el-form-item>
      </el-form>
    </el-card>

    <ImPlatformCard @switched="reloadIntegrations" />

    <el-card id="dingtalk-integration" shadow="never">
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
        <el-form-item label="事件令牌">
          <el-input
            v-model="form['integration.dingtalk.event-token']"
            placeholder="用于校验 /integrations/dingtalk/event 回调（X-DingTalk-Token）"
          />
        </el-form-item>
        <el-form-item v-if="!isJunior" label="同步策略">
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
      <el-alert v-if="syncResult" :title="syncResult.message" :type="syncResult.success ? 'success' : 'error'" :closable="false" show-icon class="sync-result" />
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

    <el-card id="feishu-integration" shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">飞书集成</span>
          <el-tag :type="feishu.enabled ? 'success' : 'info'" size="small">
            {{ feishu.enabled ? '已启用' : '未启用' }}
          </el-tag>
        </div>
      </template>
      <el-form label-width="110px" class="form">
        <el-form-item label="启用集成">
          <el-switch v-model="feishu.enabled" />
          <span class="hint inline">关闭后飞书免登、组织同步与通知停用，系统完整可用。</span>
        </el-form-item>
        <el-form-item label="App ID">
          <el-input v-model="feishu.appId" placeholder="飞书企业自建应用 App ID" />
        </el-form-item>
        <el-form-item label="App Secret">
          <el-input
            v-model="feishu.appSecret"
            type="password"
            show-password
            :placeholder="feishu.hasSecret ? '已配置，不修改请留空' : '请输入 App Secret'"
          />
        </el-form-item>
        <el-form-item label="验证 Token">
          <el-input
            v-model="feishu.verificationToken"
            :placeholder="
              feishu.verificationTokenConfigured
                ? '已配置，不修改请留空'
                : '用于校验 /integrations/feishu/event 回调（X-Feishu-Token）'
            "
          />
        </el-form-item>
        <el-form-item v-if="!isJunior" label="同步策略">
          <el-select v-model="feishu.syncStrategy" style="width: 220px">
            <el-option label="飞书为准（覆盖本地）" value="feishu" />
            <el-option label="本地为准（只补空字段）" value="local" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingFeishu" @click="saveFeishu">保存飞书配置</el-button>
          <el-button :loading="syncingFeishu" :disabled="!feishu.enabled" @click="syncFeishu">
            立即同步教职工
          </el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="feishuSyncResult" :title="feishuSyncResult.message" :type="feishuSyncResult.success ? 'success' : 'error'" :closable="false" show-icon class="sync-result" />
      <el-collapse class="logs">
        <el-collapse-item title="同步日志" name="feishu-logs">
          <el-table v-loading="loadingFeishuLogs" :data="feishuLogs" size="small" border>
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
      <template #header>
        <span class="card-title">
          AI 供应商
          <InfoTooltip field="settings.aiProvider" />
        </span>
      </template>
      <div class="ai-row">
        <div>
          <div class="ai-name">AI 供应商与模型管理</div>
          <div class="hint">
            配置 DeepSeek、Gemini、通义千问、智谱或自定义 OpenAI 兼容端点，并分配 AI 任务使用的模型。
          </div>
        </div>
        <el-button type="primary" plain @click="router.push('/admin/ai-providers')">去配置</el-button>
      </div>
      <div class="ai-row">
        <div>
          <div class="ai-name">数据导入低置信度阈值</div>
          <div class="hint">低于该值的单元格在预览中标黄，默认 0.8</div>
        </div>
        <el-input-number
          :model-value="confidenceThreshold"
          :min="0"
          :max="1"
          :step="0.05"
          :precision="2"
          @update:model-value="setConfidenceThreshold"
        />
      </div>
    </el-card>

    <el-card id="tour-reopen" shadow="never">
      <template #header><span class="card-title">新手教程</span></template>
      <div class="ai-row">
        <div>
          <div class="ai-name">重新查看新手教程</div>
          <div class="hint">重新播放首次登录时的界面引导，介绍基础数据、排课、调代与课表入口。</div>
        </div>
        <el-button plain @click="reopenTour">重新查看新手教程</el-button>
      </div>
    </el-card>

    <el-card v-if="aiEnabled" shadow="never">
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

    <el-card id="wizard-reenter" shadow="never">
      <template #header><span class="card-title">初始化向导</span></template>
      <div class="ai-row">
        <div>
          <div class="ai-name">重新走一遍初始化步骤</div>
          <div class="hint">按学期开学的顺序检查学校信息、作息、基础数据、授课任务与约束是否就绪。</div>
        </div>
        <el-button plain @click="router.push('/onboarding')">重新进入初始化向导</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { useRouter } from 'vue-router'
import { http } from '@/api/http'
import { useSettingsStore } from '@/stores/settings'
import ImPlatformCard from '@/components/ImPlatformCard.vue'
import InfoTooltip from '@/components/InfoTooltip.vue'
import { resetTour, startTour } from '@/onboarding/tour'
import { serverMessage } from '@/api/errors'
import type { SyncResult } from '@/api/integration'

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

interface FeishuConfig {
  enabled: boolean
  appId: string
  appSecret?: string
  hasSecret: boolean
  syncStrategy: string
  verificationToken?: string
  verificationTokenConfigured: boolean
}

const form = reactive<Record<string, string>>({})
const settings = useSettingsStore()
const isJunior = computed(() => settings.schoolType === 'junior')
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
const syncResult = ref<SyncResult | null>(null)
const feishu = reactive<FeishuConfig>({
  enabled: false,
  appId: '',
  appSecret: '',
  hasSecret: false,
  syncStrategy: 'feishu',
  verificationToken: '',
  verificationTokenConfigured: false,
})
const feishuLogs = ref<SyncLog[]>([])
const feishuSyncResult = ref<SyncResult | null>(null)
const saving = ref(false)
const savingDingtalk = ref(false)
const syncing = ref(false)
const savingFeishu = ref(false)
const syncingFeishu = ref(false)
const uploading = ref(false)
const loadingLogs = ref(false)
const loadingFeishuLogs = ref(false)
const router = useRouter()

const logoName = computed(() => {
  const path = form['school.logo-path']
  return path ? path.split(/[\\/]/).pop() : ''
})

const confidenceThreshold = computed(() => Number(form['ai.import.confidence-threshold'] ?? 0.8))

function setConfidenceThreshold(value: number | undefined) {
  form['ai.import.confidence-threshold'] = String(value ?? 0.8)
}

function reopenTour() {
  resetTour()
  startTour('basic')
}

// ADR-0003: AI entry points are hidden entirely while every feature is off.
const aiEnabled = computed(
  () =>
    form['ai.features.substitute-recommendation'] === 'true' ||
    form['ai.features.natural-language-constraints'] === 'true',
)

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

async function loadFeishu() {
  const { data } = await http.get('/integrations/feishu/config')
  Object.assign(feishu, data)
  feishu.appSecret = ''
  feishu.verificationToken = ''
}

async function loadFeishuLogs() {
  loadingFeishuLogs.value = true
  try {
    const { data } = await http.get('/integrations/feishu/logs')
    feishuLogs.value = data
  } finally {
    loadingFeishuLogs.value = false
  }
}

/** A platform switch flips both integrations, so every integration card reloads. */
async function reloadIntegrations() {
  await Promise.all([
    loadSettings(),
    loadDingtalk(),
    loadLogs(),
    loadFeishu(),
    loadFeishuLogs(),
  ])
}

async function saveSettings() {
  const typeChanged = (form['school.type'] ?? 'junior') !== settings.schoolType
  if (typeChanged) {
    try {
      await ElMessageBox.confirm(
        '切换后将刷新页面，初中模式将隐藏高级功能',
        '切换学校类型',
        { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }

  saving.value = true
  try {
    // Read-only settings: the logo is written by the upload endpoint, and the
    // active IM platform only by PUT /integrations/platform.
    const payload = { ...form }
    delete payload['school.logo-path']
    delete payload['integration.active-platform']
    const { data } = await http.put('/settings', payload)
    Object.keys(data).forEach((key) => {
      form[key] = data[key]
    })
    if (typeChanged) {
      location.reload()
      return
    }
    ElMessage.success('设置已保存并立即生效')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '保存失败'))
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
    ElMessage.error(serverMessage(e, '保存失败'))
  } finally {
    savingDingtalk.value = false
  }
}

async function sync() {
  syncing.value = true
  try {
    const { data } = await http.post<SyncResult>('/integrations/dingtalk/sync')
    syncResult.value = data
    if (data.success) {
      ElMessage.success(data.message)
    } else {
      ElMessage.error(data.message)
    }
    await loadLogs()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '同步失败'))
  } finally {
    syncing.value = false
  }
}

async function saveFeishu() {
  savingFeishu.value = true
  try {
    const payload: Record<string, unknown> = {
      enabled: feishu.enabled,
      appId: feishu.appId,
      syncStrategy: feishu.syncStrategy,
    }
    if (feishu.appSecret) payload.appSecret = feishu.appSecret
    if (feishu.verificationToken) payload.verificationToken = feishu.verificationToken
    const { data } = await http.put('/integrations/feishu/config', payload)
    Object.assign(feishu, data)
    feishu.appSecret = ''
    feishu.verificationToken = ''
    ElMessage.success('飞书配置已保存')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '保存失败'))
  } finally {
    savingFeishu.value = false
  }
}

async function syncFeishu() {
  syncingFeishu.value = true
  try {
    const { data } = await http.post<SyncResult>('/integrations/feishu/sync')
    feishuSyncResult.value = data
    if (data.success) {
      ElMessage.success(data.message)
    } else {
      ElMessage.error(data.message)
    }
    await loadFeishuLogs()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '同步失败'))
  } finally {
    syncingFeishu.value = false
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
    ElMessage.error(serverMessage(e, '上传失败'))
  } finally {
    uploading.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadSettings(), loadDingtalk(), loadLogs(), loadFeishu(), loadFeishuLogs()])
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
