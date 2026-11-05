<template>
  <nav class="page-tabs" aria-label="页面子导航">
    <router-link
      v-for="item in items"
      :key="item.to"
      :to="item.to"
      class="page-tab"
      :class="{ active: isActive(item) }"
      :data-tour="item.tour"
    >
      {{ item.label }}
    </router-link>
  </nav>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'

export interface PageTabItem {
  label: string
  to: string
  /** Optional marker consumed by the onboarding tour. */
  tour?: string
  /** Also mark the tab active when the route is below this prefix (e.g. a detail page). */
  activePrefix?: string
}

defineProps<{ items: PageTabItem[] }>()

const route = useRoute()

function isActive(item: PageTabItem): boolean {
  return route.path === item.to || (!!item.activePrefix && route.path.startsWith(item.activePrefix))
}
</script>

<style scoped>
.page-tabs {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  border-bottom: 1px solid #eef0f5;
  margin-bottom: 16px;
}
.page-tab {
  padding: 9px 14px;
  font-size: 14px;
  color: var(--paiban-muted);
  text-decoration: none;
  white-space: nowrap;
  border-bottom: 2px solid transparent;
}
.page-tab:hover {
  color: var(--paiban-primary);
}
.page-tab.active {
  color: var(--paiban-primary);
  border-bottom-color: var(--paiban-primary);
  font-weight: 600;
}
</style>
