<template>
  <div class="page-shell">
    <header class="page-head">
      <div>
        <h2>课表工作台</h2>
        <p class="page-subtitle">草稿、自动排课与已发布课表。</p>
      </div>
    </header>
    <PageTabs :items="tabs" />
    <router-view />
  </div>
</template>

<script setup lang="ts">
import PageTabs, { type PageTabItem } from '@/components/layout/PageTabs.vue'

const tabs: PageTabItem[] = [
  { label: '草稿与排课', to: '/workbench', activePrefix: '/workbench/drafts' },
  { label: '课表', to: '/workbench/timetable', tour: '/workbench/timetable' },
]
</script>
