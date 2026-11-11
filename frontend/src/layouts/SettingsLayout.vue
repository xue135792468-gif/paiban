<template>
  <div class="page-shell">
    <header class="page-head">
      <div>
        <h2>设置</h2>
        <p class="page-subtitle">学校信息、集成配置与账号管理。</p>
      </div>
    </header>
    <PageTabs :items="tabs" />
    <router-view />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import PageTabs from '@/components/layout/PageTabs.vue'
import { settingsTabs } from '@/layouts/sectionTabs'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// 票 01：账号页只对持「用户与角色管理」的超级管理员可见，教务管理员只有业务设置。
const tabs = computed(() => settingsTabs((permission) => auth.hasPermission(permission)))
</script>
