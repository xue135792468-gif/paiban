<template>
  <div class="home">
    <h1>排课系统</h1>
    <p class="subtitle">面向国内中学的排课与调代课平台</p>
    <el-card class="health-card" shadow="never">
      <div class="health-row">
        <span>后端健康检查</span>
        <el-tag v-if="status === 'UP'" type="success">UP</el-tag>
        <el-tag v-else-if="status === 'DOWN'" type="danger">DOWN</el-tag>
        <el-tag v-else type="info">检测中…</el-tag>
      </div>
      <pre v-if="payload" class="payload">{{ payload }}</pre>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { http } from '@/api/http'

const status = ref('')
const payload = ref('')

onMounted(async () => {
  try {
    const { data } = await http.get('/health')
    status.value = data.status
    payload.value = JSON.stringify(data, null, 2)
  } catch {
    status.value = 'DOWN'
  }
})
</script>

<style scoped>
.home {
  max-width: 720px;
  margin: 12vh auto;
  padding: 0 24px;
}
h1 {
  margin-bottom: 4px;
  font-size: 32px;
}
.subtitle {
  color: #6b7280;
  margin-bottom: 28px;
}
.health-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.payload {
  margin: 12px 0 0;
  padding: 12px;
  background: #f8fafc;
  border-radius: 6px;
  font-size: 12px;
  color: #475569;
  overflow: auto;
}
</style>
