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
        <el-form-item label="外部访问地址">
          <el-input v-model="form['public.base-url']" placeholder="如 https://paiban.school.cn，用于通知里的跳转链接" />
        </el-form-item>
      </el-form>
    </el-card>
    <SubstitutionPolicyCard v-model="form" />
    <NotificationDeliveryLogsCard v-if="canViewDeliveryLogs" />

    <!--
      票 01：钉钉参数与通讯录同步只归超级管理员（integration:manage）。教务管理员
      连区块都看不到，读取接口也不必打——后端同样会对它返回 403。
    -->
    <el-card v-if="canManageIntegration" id="dingtalk-integration" shadow="never">
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
        <DingtalkIntegrationActions
          :saving="savingDingtalk"
          :previewing="previewing"
          :enabled="dingtalk.enabled"
          :loading-logs="loadingLogs"
          :logs="logs"
          @save="saveDingtalk"
          @preview="previewSync"
          @cleared="reloadDingtalk"
        />
      </el-form>
      <el-alert v-if="syncResult" :title="syncResult.message" :type="syncResult.success ? 'success' : 'error'" :closable="false" show-icon class="sync-result" />
      <DingTalkSyncPreview v-if="syncPreview" :preview="syncPreview" :confirming="confirming" @confirm="confirmSync" />
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

    <!--
      ADR-0003 的票 14 修订：自然语言生成约束的入口在关闭时也要保留，管理员必须先能
      找到开关才能开启；代课推荐仍按原规则标注即将推出。
    -->
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
        <el-switch
          :model-value="form['ai.features.natural-language-constraints'] === 'true'"
          data-testid="settings-natural-language-switch"
          @update:model-value="(value: string | number | boolean) => (form['ai.features.natural-language-constraints'] = value ? 'true' : 'false')"
        />
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
import { ElMessage, type UploadFile } from 'element-plus'
import { useRouter } from 'vue-router'
import { DINGTALK_SYNC_TIMEOUT_MS, http } from '@/api/http'
import InfoTooltip from '@/components/InfoTooltip.vue'
import NotificationDeliveryLogsCard from '@/components/settings/NotificationDeliveryLogsCard.vue'
import SubstitutionPolicyCard from '@/components/settings/SubstitutionPolicyCard.vue'
import DingTalkSyncPreview from '@/components/DingTalkSyncPreview.vue'
import DingtalkIntegrationActions from '@/components/settings/DingtalkIntegrationActions.vue'
import { resetTour, startTour } from '@/onboarding/tour'
import { serverMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import type {
  DingTalkConfig,
  SyncConfirmRequest,
  SyncPreview,
  SyncResult,
} from '@/api/integration'

interface SyncLog {
  id: number
  createdAt: string
  detail: string
  successRows: number
}

const form = ref<Record<string, string>>({})
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
const syncPreview = ref<SyncPreview | null>(null)
const saving = ref(false)
const savingDingtalk = ref(false)
const previewing = ref(false)
const confirming = ref(false)
const uploading = ref(false)
const loadingLogs = ref(false)
const router = useRouter()
const auth = useAuthStore()

const canManageIntegration = computed(() => auth.hasPermission('integration:manage'))
const canViewDeliveryLogs = computed(() => auth.hasPermission('substitution:view'))

const logoName = computed(() => {
  const path = form.value['school.logo-path']
  return path ? path.split(/[\\/]/).pop() : ''
})

const confidenceThreshold = computed(() => Number(form.value['ai.import.confidence-threshold'] ?? 0.8))

function setConfidenceThreshold(value: number | undefined) {
  form.value['ai.import.confidence-threshold'] = String(value ?? 0.8)
}

function reopenTour() {
  resetTour()
  startTour('basic')
}

async function loadSettings() {
  const { data } = await http.get('/settings')
  Object.keys(data).forEach((key) => {
    form.value[key] = data[key]
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

/** A cleared DingTalk config must refresh both its form and its operation log. */
async function reloadDingtalk() {
  await Promise.all([loadDingtalk(), loadLogs()])
}

async function saveSettings() {
  saving.value = true
  try {
    // Read-only setting: the logo is written by the upload endpoint.
    const payload = { ...form.value }
    delete payload['school.logo-path']
    // 集成配置（钉钉参数）只有超级管理员能改：没有该权限时，即使读取响应里带了
    // 空值也不能把它跟普通设置一起提交，否则后端会整单拒绝这次保存。
    if (!canManageIntegration.value) {
      Object.keys(payload)
        .filter((key) => key.startsWith('integration.'))
        .forEach((key) => delete payload[key])
    }
    const { data } = await http.put('/settings', payload)
    Object.keys(data).forEach((key) => {
      form.value[key] = data[key]
    })
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

async function previewSync() {
  previewing.value = true
  syncResult.value = null
  try {
    const { data } = await http.post<SyncPreview>(
      '/integrations/dingtalk/sync/preview',
      undefined,
      { timeout: DINGTALK_SYNC_TIMEOUT_MS },
    )
    syncPreview.value = data
    if (!data.success) {
      ElMessage.error(data.message)
    }
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '预演失败'))
  } finally {
    previewing.value = false
  }
}

async function confirmSync(request: SyncConfirmRequest) {
  confirming.value = true
  try {
    const { data } = await http.post<SyncResult>(
      '/integrations/dingtalk/sync/confirm',
      request,
      { timeout: DINGTALK_SYNC_TIMEOUT_MS },
    )
    syncResult.value = data
    if (data.success) {
      ElMessage.success(data.message)
      syncPreview.value = null
    } else {
      ElMessage.error(data.message)
    }
    await loadLogs()
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '同步失败'))
  } finally {
    confirming.value = false
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
      form.value[key] = data[key]
    })
    ElMessage.success('Logo 已上传')
  } catch (e: any) {
    ElMessage.error(serverMessage(e, '上传失败'))
  } finally {
    uploading.value = false
  }
}

onMounted(async () => {
  const jobs = [loadSettings()]
  if (canManageIntegration.value) {
    jobs.push(loadDingtalk(), loadLogs())
  }
  await Promise.all(jobs)
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
