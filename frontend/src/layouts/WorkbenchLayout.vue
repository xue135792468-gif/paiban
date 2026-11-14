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
import { computed } from 'vue'
import PageTabs from '@/components/layout/PageTabs.vue'
import { workbenchTabs } from '@/layouts/sectionTabs'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

// 票 01：老师拿到「查看全部课表」后依然没有草稿与排课权限，页签不能指向 403。
const tabs = computed(() => workbenchTabs((permission) => auth.hasPermission(permission)))
</script>
