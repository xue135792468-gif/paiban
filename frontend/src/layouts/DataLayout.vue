<template>
  <div class="page-shell">
    <header class="page-head">
      <div>
        <h2>数据管理</h2>
        <p class="page-subtitle">学期作息、基础数据、授课任务与导入。</p>
      </div>
    </header>
    <PageTabs :items="tabs" />
    <router-view />
  </div>
</template>

<script setup lang="ts">
import PageTabs, { type PageTabItem } from '@/components/layout/PageTabs.vue'

const tabs: PageTabItem[] = [
  { label: '学期与作息', to: '/data/semesters', activePrefix: '/data/semesters' },
  { label: '教师', to: '/data/teachers' },
  { label: '课程', to: '/data/courses' },
  { label: '年级', to: '/data/grades' },
  { label: '行政班', to: '/data/classes', tour: '/data/classes' },
  { label: '授课任务', to: '/data/assignments' },
  { label: '导入', to: '/data/import' },
  { label: '手动导入', to: '/data/import/manual' },
  { label: '导入配方', to: '/data/recipes' },
]
</script>
