<template>
  <div class="page-shell">
    <nav class="grouped-tabs" aria-label="数据管理子导航">
      <div v-for="group in groups" :key="group.label" class="tab-group" data-testid="page-tab-group">
        <span class="group-label">{{ group.label }}</span>
        <PageTabs :items="group.items" :aria-label="group.label" />
      </div>
    </nav>
    <router-view />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import PageTabs, { type PageTabItem } from '@/components/layout/PageTabs.vue'

const auth = useAuthStore()

const groups = computed<Array<{ label: string; items: PageTabItem[] }>>(() => {
  const inputItems: PageTabItem[] = [{ label: '授课任务', to: '/data/assignments' }]
  if (auth.hasPermission('assignment:manage')) {
    inputItems.push({ label: '任课表', to: '/data/teaching-matrix' })
  }
  return [
    {
      label: '基础档案',
      items: [
        { label: '学期与作息', to: '/data/semesters', activePrefix: '/data/semesters' },
        { label: '年级', to: '/data/grades' },
        { label: '行政班', to: '/data/classes', tour: '/data/classes' },
        { label: '课程', to: '/data/courses' },
        { label: '教师', to: '/data/teachers' },
      ],
    },
    { label: '排课输入', items: inputItems },
    {
      label: '批量导入',
      items: [
        { label: '数据导入', to: '/data/import', activePrefix: '/data/import' },
        { label: '导入配方', to: '/data/recipes' },
      ],
    },
  ]
})
</script>

<style scoped>
.grouped-tabs {
  display: flex;
  align-items: flex-end;
  gap: 22px;
  overflow-x: auto;
  border-bottom: 1px solid #eef0f5;
  margin-bottom: 16px;
}
.tab-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.tab-group + .tab-group {
  border-left: 1px solid #e5e7ef;
  padding-left: 22px;
}
.group-label {
  padding-bottom: 10px;
  color: var(--paiban-muted);
  font-size: 12px;
  white-space: nowrap;
}
.tab-group :deep(.page-tabs) {
  border-bottom: 0;
  margin-bottom: 0;
  overflow: visible;
}
</style>
