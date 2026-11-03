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
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path" :data-tour="item.path">
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

          <el-popover v-if="canSeeNotifications" placement="bottom-end" :width="340" trigger="click">
            <template #reference>
              <el-badge :value="unread" :hidden="unread === 0" :max="99" class="bell-badge">
                <el-button circle text>
                  <el-icon><Bell /></el-icon>
                </el-button>
              </el-badge>
            </template>
            <div class="notif-head">
              <span>通知</span>
              <el-button link type="primary" @click="router.push('/notifications')">查看全部</el-button>
            </div>
            <div v-if="recent.length === 0" class="notif-empty">暂无通知</div>
            <div
              v-for="item in recent"
              :key="item.id"
              class="notif-item"
              :class="{ unread: !item.read }"
              @click="openNotification(item)"
            >
              <div class="notif-title">{{ item.title }}</div>
              <div class="notif-content">{{ item.content }}</div>
              <div class="notif-time">{{ formatTime(item.createdAt) }}</div>
            </div>
          </el-popover>

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
import {
  Bell,
  Calendar,
  ChatDotRound,
  DataLine,
  Document,
  List,
  MagicStick,
  QuestionFilled,
  Setting,
} from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { HELP_GROUPS } from '@/help/docs'
import { helpLink, type HelpSlug } from '@/help/links'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { hasSeenTour, startTour, type TourStage } from '@/onboarding/tour'

interface MenuItem {
  label: string
  path: string
  icon: unknown
  permission?: string
  permissions?: string[]
  allPermissions?: string[]
}

interface MenuGroup {
  title: string
  items: MenuItem[]
}

interface NotificationItem {
  id: number
  title: string
  content: string
  link?: string
  read: boolean
  createdAt: string
}

const auth = useAuthStore()
const settings = useSettingsStore()
const route = useRoute()
const router = useRouter()

const groups: MenuGroup[] = [
  {
    title: '导航',
    items: [
      { label: '总览', path: '/', icon: DataLine },
      {
        label: '课表工作台',
        path: '/workbench',
        icon: Calendar,
        permissions: [
          'scheduling:run',
          'scheduling:view',
          'timetable:edit',
          'timetable:view',
          'timetable:view:own',
          'timetable:view:class',
        ],
      },
      {
        label: '调代课',
        path: '/substitution',
        icon: ChatDotRound,
        permissions: ['substitution:submit', 'substitution:approve', 'substitution:view'],
      },
      { label: '排课规则', path: '/rules', icon: Document, permission: 'constraint:manage' },
      {
        label: '数据管理',
        path: '/data',
        icon: List,
        permissions: [
          'semester:manage',
          'foundation:manage',
          'assignment:manage',
          'assignment:view:own',
        ],
      },
      { label: 'AI 助手', path: '/ai', icon: MagicStick, permission: 'settings:manage' },
      {
        label: '设置',
        path: '/settings',
        icon: Setting,
        permissions: ['settings:manage', 'user:manage'],
      },
    ],
  },
]

const visibleGroups = computed(() =>
  groups
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) =>
          (!item.permission || auth.hasPermission(item.permission)) &&
          (!item.permissions || auth.hasAnyPermission(item.permissions)) &&
          (!item.allPermissions || auth.hasAllPermissions(item.allPermissions)),
      ),
    }))
    .filter((group) => group.items.length > 0),
)

/** Highlight the owning top-level entry for nested pages (drafts, schedule editor, ...). */
const activeMenu = computed(() => {
  const path = route.path
  if (path === '/') return '/'
  for (const group of groups) {
    for (const item of group.items) {
      if (item.path !== '/' && (path === item.path || path.startsWith(`${item.path}/`))) {
        return item.path
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
const roleLabel = computed(() => {
  if (auth.hasRole('admin')) return '教务管理员'
  if (auth.hasRole('head_teacher')) return '班主任'
  if (auth.hasRole('teacher')) return '教师'
  return ''
})

const canSeeNotifications = computed(() => auth.hasPermission('notification:view'))
const unread = ref(0)
const recent = ref<NotificationItem[]>([])
let timer: number | undefined

async function loadNotifications() {
  if (!canSeeNotifications.value) return
  try {
    const [countRes, recentRes] = await Promise.all([
      http.get('/notifications/unread-count'),
      http.get('/notifications/recent'),
    ])
    unread.value = countRes.data.count
    recent.value = recentRes.data
  } catch {
    // notifications are non-critical
  }
}

async function openNotification(item: NotificationItem) {
  try {
    if (!item.read) {
      await http.post(`/notifications/${item.id}/read`)
    }
  } catch {
    // ignore
  }
  await loadNotifications()
  if (item.link) {
    router.push(item.link)
  }
}

function formatTime(value: string) {
  return value ? value.replace('T', ' ').slice(5, 16) : ''
}

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
  settings.load()
  loadNotifications()
  timer = window.setInterval(loadNotifications, 30000)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
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
.bell-badge {
  display: inline-flex;
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
.notif-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  padding-bottom: 8px;
  border-bottom: 1px solid #f1f2f6;
}
.notif-empty {
  text-align: center;
  color: var(--paiban-muted);
  padding: 24px 0;
  font-size: 13px;
}
.notif-item {
  padding: 10px 6px;
  border-bottom: 1px dashed #f1f2f6;
  cursor: pointer;
}
.notif-item:hover {
  background: #f8faff;
}
.notif-item.unread .notif-title::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #4f6ef7;
  margin-right: 6px;
  vertical-align: middle;
}
.notif-title {
  font-size: 13px;
  font-weight: 600;
}
.notif-content {
  font-size: 12px;
  color: #6b7280;
  margin-top: 3px;
  line-height: 1.5;
}
.notif-time {
  font-size: 11px;
  color: #9ca3af;
  margin-top: 4px;
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
