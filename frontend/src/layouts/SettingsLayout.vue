<template>
  <div class="page-shell">
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
