<template>
  <div class="page-shell">
    <PageTabs v-if="route.name !== 'draft-editor'" :items="tabs" />
    <router-view />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageTabs from '@/components/layout/PageTabs.vue'
import { workbenchTabs } from '@/layouts/sectionTabs'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const auth = useAuthStore()

// 票 01：老师拿到「查看全部课表」后依然没有草稿与排课权限，页签不能指向 403。
const tabs = computed(() => workbenchTabs((permission) => auth.hasPermission(permission)))
</script>
