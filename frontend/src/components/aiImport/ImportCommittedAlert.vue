<template>
  <el-alert type="success" show-icon :closable="false" class="result-alert">
    <template #title>
      <template v-if="importedCount !== null">
        已成功入库 {{ importedCount }} 条{{ targetTitle(targetType) }}数据
      </template>
      <template v-else>该导入已入库</template>
    </template>
    <div class="result-actions">
      <el-button v-if="targetType === 'teacher'" link type="primary" @click="goTeachers">
        查看教师
      </el-button>
      <el-button link type="primary" @click="emit('reset')">继续导入</el-button>
    </div>
  </el-alert>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { targetTitle } from './importTargets'

defineProps<{
  targetType: string
  importedCount: number | null
}>()

const emit = defineEmits<{ (e: 'reset'): void }>()
const router = useRouter()

function goTeachers() {
  router.push('/admin/teachers')
}
</script>

<style scoped>
.result-actions {
  margin-top: 6px;
}
</style>
