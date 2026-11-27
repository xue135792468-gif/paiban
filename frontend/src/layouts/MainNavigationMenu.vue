<template>
  <el-menu
    :default-active="activeMenu"
    router
    class="menu"
    :data-testid="menuTestId"
    @select="onSelect"
  >
    <template v-for="group in groups" :key="group.title">
      <div class="group-title">{{ group.title }}</div>
      <el-menu-item
        v-for="item in group.items"
        :key="item.path"
        :index="item.target"
        :data-tour="withTourAnchors ? item.path : undefined"
      >
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </el-menu-item>
    </template>
  </el-menu>
</template>

<script setup lang="ts">
import type { ResolvedMenuGroup } from '@/layouts/navigation'

/**
 * 桌面 aside 与手机 drawer 共用的同一份导航菜单。
 * 两处真实 caller 的差异只在 `menuTestId` 与是否挂新手教程锚点：
 * 桌面保留 `layout-menu` 与 `data-tour`，抽屉用独立 testid 且不带 tour 锚点，
 * 避免抽屉打开时出现重复选择器/教程节点。
 */
withDefaults(
  defineProps<{
    groups: ResolvedMenuGroup[]
    activeMenu: string
    menuTestId: string
    withTourAnchors?: boolean
  }>(),
  { withTourAnchors: false },
)

const emit = defineEmits<{ select: [index: string] }>()

function onSelect(index: string) {
  emit('select', index)
}
</script>

<style scoped>
.menu {
  border-right: none;
  padding: 8px;
  /* 标识区固定，菜单占满剩余高度并独立滚动。 */
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.group-title {
  padding: 16px 12px 6px;
  font-size: 12px;
  color: #9aa1b1;
  letter-spacing: 0.08em;
}
</style>
