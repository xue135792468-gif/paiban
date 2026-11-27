<template>
  <div class="page-shell">
    <PageTabs v-if="route.name !== 'draft-editor'" :items="tabs" />
    <router-view />
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import PageTabs, { type PageTabItem } from '@/components/layout/PageTabs.vue'

const route = useRoute()
const tabs: PageTabItem[] = [
  { label: '草稿与排课', to: '/workbench', activePrefix: '/workbench/drafts' },
  { label: '课表', to: '/workbench/timetable', tour: '/workbench/timetable' },
]
</script>
