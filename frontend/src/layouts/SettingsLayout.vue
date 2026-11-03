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
import PageTabs, { type PageTabItem } from '@/components/layout/PageTabs.vue'

const tabs: PageTabItem[] = [
  { label: '设置', to: '/settings/general' },
  { label: '账号', to: '/settings/users' },
  { label: '最近删除', to: '/settings/recycle-bin' },
]
</script>
