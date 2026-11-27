<template>
  <div class="page-shell">
    <PageTabs :items="tabs" />
    <router-view />
  </div>
</template>

<script setup lang="ts">
import PageTabs, { type PageTabItem } from '@/components/layout/PageTabs.vue'

const tabs: PageTabItem[] = [
  { label: '通用设置', to: '/settings/general' },
  { label: '账号', to: '/settings/users' },
  { label: '最近删除', to: '/settings/recycle-bin' },
]
</script>
