<template>
  <div class="dingtalk-connection-probe" data-testid="dingtalk-connection-probe">
    <el-form-item label="AppKey">
      <el-input v-model="appKey" data-testid="dingtalk-probe-app-key" placeholder="钉钉企业内部应用 AppKey" />
    </el-form-item>
    <el-form-item label="AppSecret">
      <el-input
        v-model="appSecret"
        data-testid="dingtalk-probe-app-secret"
        type="password"
        show-password
        :placeholder="hasSecret ? '已配置，不修改请留空' : '请输入 AppSecret'"
      />
      <span v-if="canReuseSavedSecret" class="hint block"> 留空将复用已保存的 AppSecret </span>
      <span v-else-if="requiresNewSecret" class="hint block">
        请为新 AppKey 输入对应的 AppSecret，测试不会复用旧密钥
      </span>
    </el-form-item>
    <el-form-item>
      <el-button :loading="testing" :disabled="!canTest" data-testid="dingtalk-probe" @click="testConnection">
        {{ testing ? '正在测试连接' : '测试连接' }}
      </el-button>
      <span class="hint inline">使用当前填写内容测试，不保存配置。</span>
    </el-form-item>
    <el-alert
      v-if="result"
      data-testid="dingtalk-probe-result"
      :title="result.message"
      :type="result.success ? 'success' : 'error'"
      :closable="false"
      show-icon
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { http } from '@/api/http'

interface ProbeResult {
  success: boolean
  code: string
  message: string
}

const SUCCESS_MESSAGE = '测试成功：当前凭据可获取访问令牌并读取根部门。'

const props = defineProps<{
  hasSecret: boolean
  savedAppKey: string
}>()

const appKey = defineModel<string>('appKey', { required: true })
const appSecret = defineModel<string>('appSecret', { required: true })
const testing = ref(false)
const result = ref<ProbeResult | null>(null)
let requestGeneration = 0
const canReuseSavedSecret = computed(
  () => props.hasSecret && !appSecret.value && Boolean(appKey.value) && appKey.value === props.savedAppKey,
)
const requiresNewSecret = computed(
  () => props.hasSecret && !appSecret.value && appKey.value !== props.savedAppKey,
)
const canTest = computed(
  () => Boolean(appKey.value) && (Boolean(appSecret.value) || canReuseSavedSecret.value),
)

watch([appKey, appSecret], () => {
  requestGeneration += 1
  result.value = null
})

async function testConnection() {
  const generation = ++requestGeneration
  testing.value = true
  result.value = null
  try {
    const { data } = await http.post<ProbeResult>('/integrations/dingtalk/test-connection', {
      appKey: appKey.value,
      appSecret: appSecret.value,
      useSavedSecret: canReuseSavedSecret.value,
    })
    if (generation === requestGeneration) {
      result.value = data.success ? { ...data, message: SUCCESS_MESSAGE } : data
    }
  } catch (error: unknown) {
    const status = (error as { response?: { status?: number } })?.response?.status
    let message = '测试连接未能完成，请稍后重试'
    if (status === 400) {
      message = '当前填写的连接参数不完整或无效，请检查后重试'
    } else if (status === 403) {
      message = '当前账号没有集成配置权限'
    } else if (!status) {
      message = '无法连接服务器，请检查网络后重试'
    }
    if (generation === requestGeneration) {
      result.value = { success: false, code: 'REQUEST_FAILED', message }
    }
  } finally {
    testing.value = false
  }
}
</script>

<style scoped>
.hint {
  color: var(--paiban-muted);
  font-size: 12px;
}
.hint.inline {
  margin: 0 0 0 12px;
}
.hint.block {
  display: block;
  margin-top: 6px;
}
</style>
