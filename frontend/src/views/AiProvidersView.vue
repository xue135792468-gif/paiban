<template>
  <div class="ai-page">
    <div class="toolbar">
      <div>
        <h2>AI 供应商</h2>
        <p class="subtitle">配置 AI 供应商与模型，并决定数据导入、调代推荐分别使用哪个模型。</p>
      </div>
      <el-button type="primary" @click="openProviderDialog()">
        <el-icon><Plus /></el-icon>
        <span>添加供应商</span>
      </el-button>
    </div>

    <el-empty v-if="!loading && providers.length === 0" description="尚未添加 AI 供应商">
      <el-button type="primary" @click="openProviderDialog()">添加第一个供应商</el-button>
    </el-empty>

    <el-card v-for="provider in providers" :key="provider.id" shadow="never" class="provider-card">
      <div class="provider-head">
        <div class="provider-main">
          <div class="provider-title">
            <span class="provider-name">{{ provider.name }}</span>
            <el-tag size="small" :type="provider.type === 'preset' ? 'success' : 'info'">
              {{ provider.type === 'preset' ? '预设' : '自定义' }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ protocolLabel(provider.protocol) }}</el-tag>
            <el-tag v-if="!provider.enabled" size="small" type="danger">已停用</el-tag>
          </div>
          <div class="provider-url">{{ provider.baseUrl }}</div>
          <div class="provider-key">
            <span v-if="provider.hasApiKey">API Key：{{ provider.apiKeyMasked }}</span>
            <span v-else class="muted">未配置 API Key</span>
          </div>
        </div>
        <div class="provider-actions">
          <el-switch
            v-model="provider.enabled"
            inline-prompt
            active-text="启用"
            inactive-text="停用"
            @change="toggleProvider(provider)"
          />
          <el-button :loading="testingId === provider.id" @click="testProvider(provider)">
            连接测试
          </el-button>
          <el-button :loading="fetchingId === provider.id" @click="fetchProviderModels(provider)">
            拉取模型
          </el-button>
          <el-button @click="openProviderDialog(provider)">编辑</el-button>
          <el-button type="danger" plain @click="removeProvider(provider)">删除</el-button>
        </div>
      </div>

      <el-alert
        v-if="testResults[provider.id]"
        class="test-result"
        :title="testTitle(testResults[provider.id])"
        :type="testResults[provider.id].success ? 'success' : 'warning'"
        :closable="false"
        show-icon
      />

      <el-collapse v-model="expandedProviders" class="model-collapse">
        <el-collapse-item :name="String(provider.id)">
          <template #title>
            <span class="model-toggle">模型列表（{{ provider.models.length }}）</span>
          </template>

          <div class="model-toolbar">
            <span class="muted">模型与任务分配相互独立，可随时启用或停用。</span>
            <el-button size="small" type="primary" plain @click="openModelDialog(provider)">
              <el-icon><Plus /></el-icon>
              <span>添加模型</span>
            </el-button>
          </div>

          <div v-if="provider.models.length === 0" class="empty-models">
            暂无模型，请添加或点击「拉取模型」。
          </div>

          <div v-for="model in provider.models" :key="model.id" class="model-block">
            <div class="model-row">
              <span class="model-name">{{ model.modelName }}</span>
              <span class="model-display">{{ model.displayName }}</span>
              <el-tag v-if="model.visionCapable" size="small" type="success">视觉</el-tag>
              <el-tag v-if="!model.enabled" size="small" type="info">停用</el-tag>
              <div class="model-row-actions">
                <el-switch
                  v-model="model.enabled"
                  size="small"
                  @change="toggleModel(provider, model)"
                />
                <el-button link type="primary" @click="toggleParams(model)">高级参数</el-button>
                <el-button link type="primary" @click="openModelDialog(provider, model)">编辑</el-button>
                <el-button link type="danger" @click="removeModel(provider, model)">删除</el-button>
              </div>
            </div>

            <div v-if="expandedParams.includes(model.id)" class="params-panel">
              <div class="params-row">
                <span class="params-label">temperature</span>
                <el-input-number
                  v-model="paramDraft(model.id).temperature"
                  :min="0"
                  :max="2"
                  :step="0.1"
                  :precision="1"
                  controls-position="right"
                />
                <span class="params-hint">0 更稳定，2 更发散；留空使用模型默认值</span>
              </div>
              <div class="params-row">
                <span class="params-label">max_tokens</span>
                <el-input-number
                  v-model="paramDraft(model.id).maxTokens"
                  :min="1"
                  :max="200000"
                  :step="256"
                  controls-position="right"
                />
                <span class="params-hint">单次回复最大 token 数；留空使用模型默认值</span>
              </div>
              <div class="params-actions">
                <el-button size="small" type="primary" :loading="savingParamsId === model.id" @click="saveParams(provider, model)">
                  保存参数
                </el-button>
              </div>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-card shadow="never">
      <template #header><span class="card-title">任务分配</span></template>
      <p class="section-hint">
        统一模式：所有 AI 任务使用同一个模型。分开模式：数据导入与调代推荐各自选择模型。
      </p>
      <el-radio-group v-model="taskMode">
        <el-radio-button value="unified">统一模式</el-radio-button>
        <el-radio-button value="custom">分开模式</el-radio-button>
      </el-radio-group>

      <el-alert
        v-if="modelOptions.length === 0"
        class="task-alert"
        type="info"
        :closable="false"
        show-icon
        title="尚未添加可用的供应商与模型，请先添加供应商并启用至少一个模型。"
      />

      <template v-else>
        <div v-if="taskMode === 'unified'" class="task-form">
          <span class="task-label">统一使用</span>
          <el-select v-model="unifiedModelId" placeholder="选择模型" style="width: 360px">
            <el-option
              v-for="option in modelOptions"
              :key="option.modelId"
              :label="option.providerName + ' / ' + option.modelName"
              :value="option.modelId"
            />
          </el-select>
        </div>

        <div v-else class="task-form custom">
          <div class="task-line">
            <span class="task-label">数据导入</span>
            <el-select v-model="customSelections.data_import" placeholder="选择模型" style="width: 360px">
              <el-option
                v-for="option in modelOptions"
                :key="option.modelId"
                :label="option.providerName + ' / ' + option.modelName"
                :value="option.modelId"
              />
            </el-select>
          </div>
          <div class="task-line">
            <span class="task-label">调代推荐</span>
            <el-select
              v-model="customSelections.substitution_recommend"
              placeholder="选择模型"
              style="width: 360px"
            >
              <el-option
                v-for="option in modelOptions"
                :key="option.modelId"
                :label="option.providerName + ' / ' + option.modelName"
                :value="option.modelId"
              />
            </el-select>
          </div>
        </div>

        <el-button class="task-save" type="primary" :loading="savingTasks" @click="saveTaskConfig">
          保存任务分配
        </el-button>
      </template>
    </el-card>

    <el-dialog
      v-model="providerDialogVisible"
      :title="editingProviderId ? '编辑供应商' : '添加供应商'"
      width="560px"
      destroy-on-close
    >
      <el-form ref="providerFormRef" :model="providerForm" :rules="providerRules" label-width="110px">
        <el-form-item label="供应商类型" prop="type">
          <el-radio-group v-model="providerForm.type" :disabled="editingProviderId !== null" @change="onTypeChange">
            <el-radio-button value="preset">预设供应商</el-radio-button>
            <el-radio-button value="custom">自定义（OpenAI / Anthropic / Gemini）</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="providerForm.type === 'preset' && !editingProviderId" label="预设模板">
          <el-select v-model="providerForm.presetCode" placeholder="选择预设供应商" style="width: 100%" @change="onPresetChange">
            <el-option v-for="preset in presets" :key="preset.code" :label="preset.name" :value="preset.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="providerForm.type === 'custom'" label="协议" prop="protocol">
          <el-radio-group v-model="providerForm.protocol">
            <el-radio-button value="openai">OpenAI 兼容</el-radio-button>
            <el-radio-button value="anthropic">Anthropic</el-radio-button>
            <el-radio-button value="gemini">Gemini</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="providerForm.name" placeholder="如：DeepSeek 主账号" />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="providerForm.baseUrl" :placeholder="baseUrlPlaceholder" />
          <div class="hint">{{ baseUrlHint }}</div>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="providerForm.apiKey"
            type="password"
            show-password
            :placeholder="editingProviderId ? '留空表示不修改已保存的密钥' : '粘贴供应商 API Key'"
          />
          <div v-if="editingProviderId" class="hint">
            已保存的密钥不会显示明文；填写新值会覆盖原密钥。
          </div>
        </el-form-item>
        <el-form-item v-if="!editingProviderId" label="模型名列表">
          <el-input
            v-model="providerForm.modelNamesText"
            type="textarea"
            :rows="2"
            placeholder="多个模型用逗号分隔，如：deepseek-flash, deepseek-v4-pro"
          />
          <div class="hint">创建后会自动加入这些模型；也可以稍后手动添加或从供应商拉取。</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="providerForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingProvider" @click="submitProvider">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="modelDialogVisible"
      :title="editingModelId ? '编辑模型' : '添加模型'"
      width="480px"
      destroy-on-close
    >
      <el-form ref="modelFormRef" :model="modelForm" :rules="modelRules" label-width="100px">
        <el-form-item label="模型名" prop="modelName">
          <el-input v-model="modelForm.modelName" placeholder="如：deepseek-chat" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="modelForm.displayName" placeholder="留空则与模型名一致" />
        </el-form-item>
        <el-form-item label="支持视觉">
          <el-switch v-model="modelForm.visionCapable" />
          <span class="hint inline">上传图片解析时需要视觉模型。</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="modelForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingModel" @click="submitModel">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="fetchDialogVisible" title="拉取模型" width="480px" destroy-on-close>
      <p class="section-hint">以下模型来自供应商接口，勾选后加入当前供应商。</p>
      <el-checkbox-group v-model="selectedFetchedModels" class="fetched-list">
        <el-checkbox v-for="name in fetchedModels" :key="name" :value="name">{{ name }}</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="fetchDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="addingFetched"
          :disabled="selectedFetchedModels.length === 0"
          @click="addFetchedModels"
        >
          添加选中模型
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

interface AiModel {
  id: number
  providerId: number
  modelName: string
  displayName: string
  visionCapable: boolean
  enabled: boolean
  paramsJson?: string | null
  createdAt: string
}

interface AiProvider {
  id: number
  name: string
  type: string
  protocol: string
  baseUrl: string
  hasApiKey: boolean
  apiKeyMasked?: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string
  models: AiModel[]
}

interface Preset {
  code: string
  name: string
  protocol: string
  baseUrl: string
  models: string[]
}

interface TaskConfig {
  taskType: string
  configured: boolean
  providerId?: number
  providerName?: string
  modelId?: number
  modelName?: string
  visionCapable?: boolean
}

interface TaskBundle {
  mode: string
  tasks: TaskConfig[]
}

interface ConnectionTestResult {
  success: boolean
  message: string
  latencyMs: number
  modelNames: string[]
}

interface ModelOption {
  providerId: number
  providerName: string
  modelId: number
  modelName: string
}

interface ParamDraft {
  temperature?: number
  maxTokens?: number
}

const TASK_TYPES = ['data_import', 'substitution_recommend'] as const

const providers = ref<AiProvider[]>([])
const presets = ref<Preset[]>([])
const loading = ref(false)
const savingProvider = ref(false)
const savingModel = ref(false)
const savingParamsId = ref<number | null>(null)
const savingTasks = ref(false)
const testingId = ref<number | null>(null)
const fetchingId = ref<number | null>(null)
const addingFetched = ref(false)

const expandedProviders = ref<string[]>([])
const expandedParams = ref<number[]>([])
const paramDrafts = reactive<Record<number, ParamDraft>>({})
const testResults = reactive<Record<number, ConnectionTestResult>>({})

const taskMode = ref<'unified' | 'custom'>('unified')
const unifiedModelId = ref<number | undefined>()
const customSelections = reactive<Record<string, number | undefined>>({
  data_import: undefined,
  substitution_recommend: undefined,
})

const providerDialogVisible = ref(false)
const editingProviderId = ref<number | null>(null)
const providerFormRef = ref<FormInstance>()
const providerForm = reactive({
  type: 'preset',
  protocol: 'openai',
  presetCode: '',
  name: '',
  baseUrl: '',
  apiKey: '',
  modelNamesText: '',
  enabled: true,
})

const modelDialogVisible = ref(false)
const editingModelId = ref<number | null>(null)
const modelProviderId = ref<number | null>(null)
const modelFormRef = ref<FormInstance>()
const modelForm = reactive({
  modelName: '',
  displayName: '',
  visionCapable: false,
  enabled: true,
})

const fetchDialogVisible = ref(false)
const fetchedProvider = ref<AiProvider | null>(null)
const fetchedModels = ref<string[]>([])
const selectedFetchedModels = ref<string[]>([])

const providerRules: FormRules = {
  name: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }],
}

const modelRules: FormRules = {
  modelName: [{ required: true, message: '请输入模型名', trigger: 'blur' }],
}

const baseUrlPlaceholder = computed(() => {
  if (providerForm.protocol === 'anthropic') {
    return 'https://api.anthropic.com'
  }
  if (providerForm.protocol === 'gemini') {
    return 'https://generativelanguage.googleapis.com/v1beta'
  }
  return 'https://api.openai.com/v1'
})

const baseUrlHint = computed(() => {
  if (providerForm.protocol === 'anthropic') {
    return '填 API 根地址，系统会追加 /v1/messages；填到 /v1 也可以。'
  }
  if (providerForm.protocol === 'gemini') {
    return '填版本前缀，系统会追加 /models/{模型}:generateContent。'
  }
  return '填 API 根地址，系统会追加 /chat/completions；OpenAI 官方通常填到 /v1。'
})

function protocolLabel(protocol?: string) {
  const labels: Record<string, string> = {
    openai: 'OpenAI 兼容',
    anthropic: 'Anthropic',
    gemini: 'Gemini',
  }
  return labels[protocol ?? ''] ?? protocol ?? ''
}

const modelOptions = computed<ModelOption[]>(() =>
  providers.value
    .filter((provider) => provider.enabled)
    .flatMap((provider) =>
      provider.models
        .filter((model) => model.enabled)
        .map((model) => ({
          providerId: provider.id,
          providerName: provider.name,
          modelId: model.id,
          modelName: model.modelName,
        })),
    ),
)

function errorMessage(error: any, fallback: string) {
  return serverMessage(error, fallback)
}

async function load() {
  loading.value = true
  try {
    const [providerResponse, presetResponse, taskResponse] = await Promise.all([
      http.get('/ai-providers'),
      http.get('/ai-providers/presets'),
      http.get('/ai-task-config'),
    ])
    providers.value = providerResponse.data
    presets.value = presetResponse.data
    applyTaskConfig(taskResponse.data)
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '加载 AI 供应商失败'))
  } finally {
    loading.value = false
  }
}

function applyTaskConfig(bundle: TaskBundle) {
  taskMode.value = bundle.mode === 'custom' ? 'custom' : 'unified'
  const firstConfigured = bundle.tasks.find((task) => task.configured) ?? bundle.tasks[0]
  unifiedModelId.value = firstConfigured?.modelId
  TASK_TYPES.forEach((taskType) => {
    const task = bundle.tasks.find((item) => item.taskType === taskType)
    customSelections[taskType] = task?.modelId
  })
}

function modelOption(modelId: number | undefined) {
  return modelOptions.value.find((option) => option.modelId === modelId)
}

function openProviderDialog(provider?: AiProvider) {
  editingProviderId.value = provider?.id ?? null
  providerForm.type = provider?.type === 'custom' ? 'custom' : 'preset'
  providerForm.protocol = provider?.protocol ?? 'openai'
  providerForm.presetCode = ''
  providerForm.name = provider?.name ?? ''
  providerForm.baseUrl = provider?.baseUrl ?? ''
  providerForm.apiKey = ''
  providerForm.modelNamesText = ''
  providerForm.enabled = provider?.enabled ?? true
  providerDialogVisible.value = true
}

function onTypeChange() {
  providerForm.presetCode = ''
  if (providerForm.type === 'custom') {
    providerForm.protocol = 'openai'
    providerForm.modelNamesText = ''
  }
}

function onPresetChange() {
  const preset = presets.value.find((item) => item.code === providerForm.presetCode)
  if (!preset) return
  providerForm.protocol = preset.protocol
  providerForm.baseUrl = preset.baseUrl
  providerForm.modelNamesText = preset.models.join(', ')
  if (!providerForm.name) {
    providerForm.name = preset.name
  }
}

function parsedModelNames() {
  return Array.from(
    new Set(
      providerForm.modelNamesText
        .split(/[,，\s]+/)
        .map((name) => name.trim())
        .filter((name) => name.length > 0),
    ),
  )
}

async function submitProvider() {
  if (!providerFormRef.value) return
  try {
    await providerFormRef.value.validate()
  } catch {
    return
  }
  savingProvider.value = true
  try {
    const payload: Record<string, unknown> = {
      name: providerForm.name,
      type: providerForm.type,
      protocol: providerForm.protocol,
      baseUrl: providerForm.baseUrl,
      enabled: providerForm.enabled,
    }
    if (providerForm.apiKey) {
      payload.apiKey = providerForm.apiKey
    }
    if (!editingProviderId.value) {
      const names = parsedModelNames()
      if (names.length > 0) {
        payload.models = names.map((name) => ({ modelName: name, displayName: name }))
      }
    }

    if (editingProviderId.value) {
      await http.put(`/ai-providers/${editingProviderId.value}`, payload)
      ElMessage.success('供应商已更新')
    } else {
      await http.post('/ai-providers', payload)
      ElMessage.success('供应商已添加')
    }
    providerDialogVisible.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '保存供应商失败'))
  } finally {
    savingProvider.value = false
  }
}

async function toggleProvider(provider: AiProvider) {
  try {
    const { data } = await http.put(`/ai-providers/${provider.id}`, {
      name: provider.name,
      type: provider.type,
      protocol: provider.protocol,
      baseUrl: provider.baseUrl,
      enabled: provider.enabled,
    })
    Object.assign(provider, data)
    ElMessage.success(provider.enabled ? '供应商已启用' : '供应商已停用')
  } catch (error: any) {
    provider.enabled = !provider.enabled
    ElMessage.error(errorMessage(error, '更新供应商状态失败'))
  }
}

async function removeProvider(provider: AiProvider) {
  try {
    await ElMessageBox.confirm(`确定删除供应商「${provider.name}」及其全部模型吗？`, '删除供应商', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await http.delete(`/ai-providers/${provider.id}`)
    ElMessage.success('供应商已删除')
    await load()
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '删除供应商失败'))
  }
}

async function testProvider(provider: AiProvider) {
  testingId.value = provider.id
  try {
    const { data } = await http.post(`/ai-providers/${provider.id}/test`)
    testResults[provider.id] = data
    if (data.success) {
      ElMessage.success(data.message)
    } else {
      ElMessage.warning(data.message)
    }
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '连接测试失败'))
  } finally {
    testingId.value = null
  }
}

function testTitle(result: ConnectionTestResult) {
  const models = result.modelNames.length > 0 ? `（${result.modelNames.length} 个模型）` : ''
  return `${result.message}${models} · ${result.latencyMs}ms`
}

async function fetchProviderModels(provider: AiProvider) {
  fetchingId.value = provider.id
  try {
    const { data } = await http.post(`/ai-providers/${provider.id}/fetch-models`)
    const names: string[] = Array.isArray(data) ? data : []
    const existing = new Set(provider.models.map((model) => model.modelName))
    const candidates = names.filter((name) => !existing.has(name))
    if (candidates.length === 0) {
      ElMessage.warning(names.length > 0 ? '模型列表已是最新' : '未获取到模型，请先确认连接测试通过')
      return
    }
    fetchedProvider.value = provider
    fetchedModels.value = candidates
    selectedFetchedModels.value = []
    fetchDialogVisible.value = true
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '拉取模型失败'))
  } finally {
    fetchingId.value = null
  }
}

async function addFetchedModels() {
  if (!fetchedProvider.value) return
  addingFetched.value = true
  try {
    for (const name of selectedFetchedModels.value) {
      await http.post(`/ai-providers/${fetchedProvider.value.id}/models`, {
        modelName: name,
        displayName: name,
      })
    }
    ElMessage.success('模型已添加')
    fetchDialogVisible.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '添加模型失败'))
  } finally {
    addingFetched.value = false
  }
}

function openModelDialog(provider: AiProvider, model?: AiModel) {
  modelProviderId.value = provider.id
  editingModelId.value = model?.id ?? null
  modelForm.modelName = model?.modelName ?? ''
  modelForm.displayName = model?.displayName ?? ''
  modelForm.visionCapable = model?.visionCapable ?? false
  modelForm.enabled = model?.enabled ?? true
  modelDialogVisible.value = true
}

async function submitModel() {
  if (!modelFormRef.value || !modelProviderId.value) return
  try {
    await modelFormRef.value.validate()
  } catch {
    return
  }
  savingModel.value = true
  try {
    const payload: Record<string, unknown> = {
      modelName: modelForm.modelName,
      displayName: modelForm.displayName,
      visionCapable: modelForm.visionCapable,
      enabled: modelForm.enabled,
    }
    if (editingModelId.value) {
      const provider = providers.value.find((item) => item.id === modelProviderId.value)
      const existing = provider?.models.find((item) => item.id === editingModelId.value)
      if (existing?.paramsJson) {
        payload.paramsJson = existing.paramsJson
      }
      await http.put(`/ai-providers/${modelProviderId.value}/models/${editingModelId.value}`, payload)
      ElMessage.success('模型已更新')
    } else {
      await http.post(`/ai-providers/${modelProviderId.value}/models`, payload)
      ElMessage.success('模型已添加')
    }
    modelDialogVisible.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '保存模型失败'))
  } finally {
    savingModel.value = false
  }
}

async function toggleModel(provider: AiProvider, model: AiModel) {
  try {
    await http.put(`/ai-providers/${provider.id}/models/${model.id}`, {
      modelName: model.modelName,
      displayName: model.displayName,
      visionCapable: model.visionCapable,
      enabled: model.enabled,
      paramsJson: model.paramsJson ?? undefined,
    })
    ElMessage.success(model.enabled ? '模型已启用' : '模型已停用')
  } catch (error: any) {
    model.enabled = !model.enabled
    ElMessage.error(errorMessage(error, '更新模型状态失败'))
  }
}

async function removeModel(provider: AiProvider, model: AiModel) {
  try {
    await ElMessageBox.confirm(`确定删除模型「${model.modelName}」吗？`, '删除模型', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/ai-providers/${provider.id}/models/${model.id}`)
    ElMessage.success('模型已删除')
    await load()
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '删除模型失败'))
  }
}

function paramDraft(modelId: number): ParamDraft {
  if (!paramDrafts[modelId]) {
    paramDrafts[modelId] = {}
  }
  return paramDrafts[modelId]
}

function toggleParams(model: AiModel) {
  const draft = paramDraft(model.id)
  if (model.paramsJson) {
    try {
      const parsed = JSON.parse(model.paramsJson)
      draft.temperature = typeof parsed.temperature === 'number' ? parsed.temperature : undefined
      draft.maxTokens = typeof parsed.max_tokens === 'number' ? parsed.max_tokens : undefined
    } catch {
      // 非法历史参数忽略，用户可重新填写
    }
  }
  if (expandedParams.value.includes(model.id)) {
    expandedParams.value = expandedParams.value.filter((id) => id !== model.id)
  } else {
    expandedParams.value = [...expandedParams.value, model.id]
  }
}

async function saveParams(provider: AiProvider, model: AiModel) {
  const draft = paramDraft(model.id)
  const params: Record<string, number> = {}
  if (typeof draft.temperature === 'number') {
    params.temperature = draft.temperature
  }
  if (typeof draft.maxTokens === 'number') {
    params.max_tokens = draft.maxTokens
  }
  savingParamsId.value = model.id
  try {
    await http.put(`/ai-providers/${provider.id}/models/${model.id}`, {
      modelName: model.modelName,
      displayName: model.displayName,
      visionCapable: model.visionCapable,
      enabled: model.enabled,
      paramsJson: Object.keys(params).length > 0 ? JSON.stringify(params) : '',
    })
    ElMessage.success('模型参数已保存')
    await load()
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '保存模型参数失败'))
  } finally {
    savingParamsId.value = null
  }
}

async function saveTaskConfig() {
  if (taskMode.value === 'unified') {
    const option = modelOption(unifiedModelId.value)
    if (!option) {
      ElMessage.warning('请选择统一使用的模型')
      return
    }
    savingTasks.value = true
    try {
      const { data } = await http.put('/ai-task-config', {
        mode: 'unified',
        providerId: option.providerId,
        modelId: option.modelId,
      })
      applyTaskConfig(data)
      ElMessage.success('任务分配已保存（统一模式）')
    } catch (error: any) {
      ElMessage.error(errorMessage(error, '保存任务分配失败'))
    } finally {
      savingTasks.value = false
    }
    return
  }

  const tasks = TASK_TYPES.map((taskType) => {
    const option = modelOption(customSelections[taskType])
    return {
      taskType,
      providerId: option?.providerId,
      modelId: option?.modelId,
    }
  })
  savingTasks.value = true
  try {
    const { data } = await http.put('/ai-task-config', { mode: 'custom', tasks })
    applyTaskConfig(data)
    ElMessage.success('任务分配已保存（分开模式）')
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '保存任务分配失败'))
  } finally {
    savingTasks.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.ai-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 980px;
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
.provider-card {
  border-radius: 10px;
}
.provider-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.provider-main {
  min-width: 0;
}
.provider-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.provider-name {
  font-size: 16px;
  font-weight: 600;
}
.provider-url {
  margin-top: 6px;
  color: var(--paiban-muted);
  font-size: 12px;
  word-break: break-all;
}
.provider-key {
  margin-top: 4px;
  font-size: 12px;
}
.muted {
  color: var(--paiban-muted);
}
.provider-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.test-result {
  margin-top: 12px;
}
.model-collapse {
  margin-top: 14px;
}
.model-toggle {
  font-weight: 600;
  font-size: 13px;
}
.model-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  margin-bottom: 10px;
}
.empty-models {
  padding: 10px 0;
  color: var(--paiban-muted);
  font-size: 13px;
}
.model-block {
  border-top: 1px dashed #eef0f5;
  padding: 10px 0;
}
.model-block:first-of-type {
  border-top: none;
}
.model-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.model-name {
  font-weight: 600;
  font-size: 13px;
}
.model-display {
  color: var(--paiban-muted);
  font-size: 12px;
}
.model-row-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.params-panel {
  margin-top: 10px;
  padding: 12px;
  border-radius: 8px;
  background: #f8f9fc;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.params-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.params-label {
  width: 100px;
  font-size: 13px;
  color: #4b5563;
}
.params-hint {
  color: var(--paiban-muted);
  font-size: 12px;
}
.params-actions {
  padding-left: 112px;
}
.section-hint {
  margin: 0 0 14px;
  color: var(--paiban-muted);
  font-size: 13px;
}
.task-alert {
  margin-top: 14px;
}
.task-form {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.task-form.custom {
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}
.task-line {
  display: flex;
  align-items: center;
  gap: 12px;
}
.task-label {
  width: 84px;
  color: #4b5563;
  font-size: 13px;
}
.task-save {
  margin-top: 16px;
}
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
  margin-top: 6px;
}
.hint.inline {
  margin: 0 0 0 12px;
}
.fetched-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 320px;
  overflow: auto;
}
</style>
