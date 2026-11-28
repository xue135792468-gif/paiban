<template>
  <div class="not-found">
    <el-result icon="warning" title="404" sub-title="页面不存在，请检查访问地址是否正确。">
      <template #extra>
        <el-button type="primary" data-testid="not-found-back" @click="router.replace('/')">
          返回工作台
        </el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<style scoped>
.not-found {
  display: grid;
  place-items: center;
  min-height: 60vh;
}
</style>
