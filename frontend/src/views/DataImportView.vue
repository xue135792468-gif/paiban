<template>
  <div class="unified-import">
    <ImportModeHeader :mode="mode" @change="setMode" />
    <el-tag v-if="mode === 'ai' && aiConfigured" type="success" effect="plain">AI 已就绪</el-tag>
    <el-alert
      v-if="mode === 'manual' && pendingAiSession"
      type="info"
      show-icon
      :closable="false"
      data-testid="pending-ai-session"
    >
      <template #title>
        检测到上次未完成的 AI 导入会话（{{ pendingAiSession }}）
        <el-button link type="primary" @click="setMode('ai')">继续 AI 导入</el-button>
      </template>
    </el-alert>
    <ManualImportView v-if="mode === 'manual'" embedded />
    <AiDataImportView v-else-if="aiConfigured" embedded />
    <el-card v-else shadow="never" class="ai-guide" data-testid="ai-not-configured">
      <h3>尚未配置 AI 供应商</h3>
      <p>配置大模型后可自动识别多工作表工作簿、乱序名单或拍照图片；当前可直接使用手动选列导入。</p>
      <div class="actions">
        <el-button type="primary" @click="router.push('/ai')">去 AI 助手配置</el-button>
        <el-button data-testid="back-to-manual" @click="setMode('manual')">返回手动导入</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '@/api/http'
import ImportModeHeader from '@/components/imports/ImportModeHeader.vue'
import ManualImportView from './ManualImportView.vue'
import AiDataImportView from './AiDataImportView.vue'

const route = useRoute()
const router = useRouter()
const aiConfigured = ref(false)
const pendingAiSession = ref('')
try {
  const stored = JSON.parse(localStorage.getItem('paiban.aiImport.lastSession') ?? '{}')
  if (stored.fileName && stored.status !== 'ABANDONED') pendingAiSession.value = String(stored.fileName)
} catch {
  pendingAiSession.value = ''
}
const mode = ref<'manual' | 'ai'>(
  route.path === '/data/import' &&
    route.query.mode !== 'manual' &&
    (route.query.mode === 'ai' || route.query.session != null || route.query.target != null)
    ? 'ai'
    : 'manual',
)
function setMode(next: 'manual' | 'ai') {
  mode.value = next
  void router.replace({ path: '/data/import', query: { ...route.query, mode: next } })
}

onMounted(async () => {
  try {
    const { data } = await http.get('/ai/status')
    aiConfigured.value = Boolean(data?.tasks?.data_import?.configured)
  } catch {
    aiConfigured.value = false
  }
})
</script>

<style scoped>
.unified-import {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.ai-guide {
  padding: 24px;
  text-align: center;
}
.ai-guide h3 {
  margin: 0 0 8px;
}
.ai-guide p {
  color: var(--paiban-muted);
  line-height: 1.7;
}
.actions {
  display: flex;
  justify-content: center;
  gap: 10px;
  margin-top: 18px;
}
</style>
