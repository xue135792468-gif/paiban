<template>
  <div class="settings-page">
    <div class="toolbar">
      <div>
        <h2>设置中心</h2>
        <p class="subtitle">各模块独立保存，修改后立即生效。</p>
      </div>
    </div>

    <SchoolInfoCard v-model="form" />
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
        <template v-if="dingtalk.enabled">
          <DingtalkConnectionProbe
            v-model:app-key="dingtalk.appKey"
            v-model:app-secret="dingtalk.appSecret"
            :has-secret="dingtalk.hasSecret"
            :saved-app-key="savedDingtalkAppKey"
          />
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
        </template>
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
      <el-alert
        v-if="syncResult"
        :title="syncResult.message"
        :type="syncResult.success ? 'success' : 'error'"
        :closable="false"
        show-icon
        class="sync-result"
      />
      <DingTalkSyncPreview
        v-if="syncPreview"
        :preview="syncPreview"
        :confirming="confirming"
        @confirm="confirmSync"
      />
    </el-card>

    <AiSettingsCard v-model="form" />

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
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { DINGTALK_SYNC_TIMEOUT_MS, http } from '@/api/http'
import SchoolInfoCard from '@/components/settings/SchoolInfoCard.vue'
import AiSettingsCard from '@/components/settings/AiSettingsCard.vue'
import NotificationDeliveryLogsCard from '@/components/settings/NotificationDeliveryLogsCard.vue'
import SubstitutionPolicyCard from '@/components/settings/SubstitutionPolicyCard.vue'
import DingTalkSyncPreview from '@/components/DingTalkSyncPreview.vue'
import DingtalkConnectionProbe from '@/components/settings/DingtalkConnectionProbe.vue'
import DingtalkIntegrationActions from '@/components/settings/DingtalkIntegrationActions.vue'
import { resetTour, startTour } from '@/onboarding/tour'
import { serverMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { INTEGRATION_MANAGE_PERMISSION } from '@/constants/permissions'
import type { DingTalkConfig, SyncConfirmRequest, SyncPreview, SyncResult } from '@/api/integration'

interface SyncLog {
  id: number
  createdAt: string
  detail: string
  successRows: number
}

const form = ref<Record<string, string>>({})
const dingtalk = reactive<DingTalkConfig & { appSecret: string }>({
  enabled: false,
  appKey: '',
  appSecret: '',
  hasSecret: false,
  agentId: '',
  corpId: '',
  syncStrategy: 'dingtalk',
})
const savedDingtalkAppKey = ref('')
const logs = ref<SyncLog[]>([])
const syncResult = ref<SyncResult | null>(null)
const syncPreview = ref<SyncPreview | null>(null)
const savingDingtalk = ref(false)
const previewing = ref(false)
const confirming = ref(false)
const loadingLogs = ref(false)
const router = useRouter()
const auth = useAuthStore()

const canManageIntegration = computed(() => auth.hasPermission(INTEGRATION_MANAGE_PERMISSION))
const canViewDeliveryLogs = computed(() => auth.hasPermission('substitution:view'))

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
  savedDingtalkAppKey.value = data.appKey ?? ''
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
    // The token belongs to this same card, not to an unrelated page-level save.
    const settings = await http.put<Record<string, string>>('/settings', {
      'integration.dingtalk.event-token': form.value['integration.dingtalk.event-token'] ?? '',
    })
    Object.assign(dingtalk, data)
    Object.assign(form.value, settings.data)
    savedDingtalkAppKey.value = data.appKey ?? ''
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
    const { data } = await http.post<SyncPreview>('/integrations/dingtalk/sync/preview', undefined, {
      timeout: DINGTALK_SYNC_TIMEOUT_MS,
    })
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
    const { data } = await http.post<SyncResult>('/integrations/dingtalk/sync/confirm', request, {
      timeout: DINGTALK_SYNC_TIMEOUT_MS,
    })
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
