<template>
  <el-container class="shell">
    <el-aside width="228px" class="aside" data-testid="layout-aside">
      <div class="logo" data-testid="layout-logo">
        <span class="logo-mark">排</span>
        <span class="logo-text">排课系统</span>
      </div>
      <el-menu :default-active="activeMenu" router class="menu" data-testid="layout-menu">
        <template v-for="group in visibleGroups" :key="group.title">
          <div class="group-title">{{ group.title }}</div>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.target" :data-tour="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header" data-testid="layout-header">
        <div class="page-title">{{ route.meta.title ?? '' }}</div>
        <div class="header-right">
          <el-button circle text aria-label="帮助" data-testid="help-button" @click="helpOpen = true">
            <el-icon><QuestionFilled /></el-icon>
          </el-button>

          <el-dropdown trigger="click" @command="onCommand">
            <span class="user-chip">
              <el-avatar :size="28">{{ initials }}</el-avatar>
              <span class="user-name">{{ auth.displayName }}</span>
              <el-tag size="small" effect="plain">{{ roleLabel }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main ref="mainRef" class="main" data-testid="layout-main">
        <router-view />
      </el-main>
    </el-container>

    <el-drawer v-model="helpOpen" title="帮助" direction="rtl" size="360px" data-testid="help-drawer">
      <div v-for="group in helpGroups" :key="group.title" class="help-group">
        <div class="help-group-title">{{ group.title }}</div>
        <button
          v-for="doc in group.docs"
          :key="doc.slug"
          type="button"
          class="help-doc"
          @click="openHelp(doc.slug)"
        >
          <span class="help-doc-title">{{ doc.title }}</span>
          <span class="help-doc-summary">{{ doc.summary }}</span>
        </button>
      </div>
    </el-drawer>
  </el-container>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { QuestionFilled } from '@element-plus/icons-vue'
import { HELP_GROUPS } from '@/help/docs'
import { helpLink, type HelpSlug } from '@/help/links'
import { MENU_GROUPS, visibleMenuGroups } from '@/layouts/navigation'
import { primaryRoleLabel } from '@/constants/roles'
import { useAuthStore } from '@/stores/auth'
import { hasSeenTour, startTour, type TourStage } from '@/onboarding/tour'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const groups = MENU_GROUPS

const visibleGroups = computed(() =>
  visibleMenuGroups({
    has: (permission: string) => auth.hasPermission(permission),
    hasAny: (permissions: string[]) => auth.hasAnyPermission(permissions),
    hasAll: (permissions: string[]) => auth.hasAllPermissions(permissions),
  }, groups),
)

/**
 * Highlight the owning top-level entry for nested pages (drafts, schedule editor, ...).
 * Matched on the stable entry path, but returned as the item's resolved target so
 * `el-menu` can find the index it actually rendered.
 */
const activeMenu = computed(() => {
  const path = route.path
  if (path === '/') return '/'
  for (const group of visibleGroups.value) {
    for (const item of group.items) {
      if (item.path !== '/' && (path === item.path || path.startsWith(`${item.path}/`))) {
        return item.target
      }
    }
  }
  return path
})

const helpOpen = ref(false)
const helpGroups = HELP_GROUPS

function openHelp(slug: HelpSlug) {
  helpOpen.value = false
  void router.push(helpLink(slug))
}

const initials = computed(() => (auth.displayName || '?').slice(0, 1))
const roleLabel = computed(() => primaryRoleLabel(auth.roles))

function onCommand(command: string) {
  if (command === 'logout') {
    auth.logout()
    router.replace('/login')
  }
}

// The tour waits one layout frame so the sidebar menu has real geometry to highlight.
const TOUR_DELAY_MS = 600
let tourTimer: number | undefined

function scheduleTour(stage: TourStage) {
  if (!auth.isLoggedIn || !auth.hasPermission('onboarding:manage')) return
  if (hasSeenTour(stage)) return
  if (stage === 'basic' && route.path !== '/') return
  if (stage === 'advanced' && (route.path !== '/workbench' || !hasSeenTour('basic'))) return

  if (tourTimer) window.clearTimeout(tourTimer)
  tourTimer = window.setTimeout(() => {
    tourTimer = undefined
    if (stage === 'basic' ? route.path !== '/' : route.path !== '/workbench') return
    if (hasSeenTour(stage) || !auth.hasPermission('onboarding:manage')) return
    startTour(stage)
  }, TOUR_DELAY_MS)
}

// Basic tour on the home page; the advanced one the first time the workbench is opened.
watch(
  () => route.path,
  (path) => {
    if (path === '/') scheduleTour('basic')
    else if (path === '/workbench') scheduleTour('advanced')
  },
  { immediate: true },
)

// 内容区的滚动容器：el-main 渲染出的真实 <main> 元素。
const mainRef = ref<ComponentPublicInstance | null>(null)

/**
 * 只有「换页面」（路径变化）才把内容区滚回顶部；同页修改筛选/页签/日期
 * 只改查询参数或组件状态，路径不变，不会触发回顶。左侧菜单的滚动位置
 * 从不主动改动，因此切换页面后自然保持。
 */
watch(
  () => route.path,
  async () => {
    await nextTick()
    const el = mainRef.value?.$el as HTMLElement | undefined
    if (el) el.scrollTop = 0
  },
)

onMounted(() => {
  auth.refresh().catch(() => undefined)
})

onUnmounted(() => {
  if (tourTimer) window.clearTimeout(tourTimer)
})
</script>

<style scoped>
.shell {
  /* 外壳固定为一屏高：整页不再滚动，滚动交给左右两个内部区域。 */
  height: 100vh;
  overflow: hidden;
}
.aside {
  background: #fff;
  border-right: 1px solid #eef0f5;
  display: flex;
  flex-direction: column;
  /* 覆盖 el-aside 默认的 overflow:auto，滚动收敛到内部的 .menu。 */
  overflow: hidden;
}
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid #f1f2f6;
}
.logo-mark {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: #4f6ef7;
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 700;
}
.logo-text {
  font-weight: 700;
  font-size: 17px;
}
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
.header {
  background: #fff;
  border-bottom: 1px solid #eef0f5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
}
.page-title {
  font-size: 16px;
  font-weight: 600;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 8px;
  border-radius: 8px;
}
.user-chip:hover {
  background: #f6f7fb;
}
.user-name {
  font-size: 14px;
}
.main {
  padding: 24px;
  background: var(--paiban-bg);
  /* 顶栏固定，内容占满剩余高度并独立滚动；min-height:0 防 flex 撑破。 */
  min-height: 0;
  overflow-y: auto;
}
.help-group + .help-group {
  margin-top: 20px;
}
.help-group-title {
  font-size: 12px;
  color: #9aa1b1;
  letter-spacing: 0.08em;
  margin-bottom: 6px;
}
.help-doc {
  display: block;
  width: 100%;
  padding: 10px 8px;
  border: none;
  border-bottom: 1px dashed #f1f2f6;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.help-doc:hover {
  background: #f8faff;
}
.help-doc-title {
  display: block;
  font-size: 14px;
  color: var(--paiban-ink);
}
.help-doc-summary {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--paiban-muted);
  line-height: 1.5;
}
/* 窄屏（≤900px）：左侧导航让位，内容独占整行（调代工作台等宽内容页的 320px 验收依赖）。 */
@media (max-width: 900px) {
  .aside {
    display: none;
  }
}
/* 更窄（≤520px）：收敛壳层留白，给内容让出宽度，避免横向滚动。 */
@media (max-width: 520px) {
  .header {
    padding: 0 12px;
  }
  .main {
    padding: 12px;
  }
}
</style>
