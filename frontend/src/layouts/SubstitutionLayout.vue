<template>
  <div class="page-shell">
    <header class="page-head">
      <div>
        <h2>调代课</h2>
        <p class="page-subtitle">教师提交的代课申请由教务审批后生效；管理员也可在调课工作台直接调整并确认，全部保留记录。</p>
      </div>
    </header>
    <PageTabs :items="tabs" />
    <router-view />
  </div>
</template>

<script setup lang="ts">
import PageTabs, { type PageTabItem } from '@/components/layout/PageTabs.vue'

const tabs: PageTabItem[] = [
  { label: '调代工作台', to: '/substitution' },
  { label: '我的申请', to: '/substitution/requests' },
]
</script>

<style scoped>
/*
 * The workbench view owns an internal scroll container (.wb-scroll) and expects
 * a definite parent height. Fill the main content area and let the routed view
 * take the space left below the page head and tabs.
 */
.page-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.page-shell > :deep(.workbench) {
  flex: 1;
  height: auto;
  min-height: 0;
}
</style>
