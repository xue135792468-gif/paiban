<template>
  <div class="forbidden">
    <el-result icon="warning" title="403" sub-title="你没有访问该页面的权限，请联系教务管理员。">
      <template #extra>
        <el-button type="primary" @click="router.replace('/')">返回工作台</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<style scoped>
.forbidden {
  display: grid;
  place-items: center;
  min-height: 60vh;
}
</style>
