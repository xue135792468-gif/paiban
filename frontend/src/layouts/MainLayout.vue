<template>
  <el-container class="shell">
    <el-aside width="228px" class="aside">
      <div class="logo">
        <span class="logo-mark">排</span>
        <span class="logo-text">排课系统</span>
      </div>
      <el-menu :default-active="route.path" router class="menu">
        <template v-for="group in visibleGroups" :key="group.title">
          <div class="group-title">{{ group.title }}</div>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="page-title">{{ route.meta.title ?? '' }}</div>
        <div class="header-right">
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
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell,
  Calendar,
  ChatDotRound,
  Cpu,
  DataLine,
  Document,
  Guide,
  List,
  MagicStick,
  OfficeBuilding,
  QuestionFilled,
  Reading,
  School,
  Setting,
  Tickets,
  Timer,
  User,
} from '@element-plus/icons-vue'
import { http } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'

interface MenuItem {
  label: string
  path: string
  icon: unknown
  permission?: string
  permissions?: string[]
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
    title: '概览',
    items: [{ label: '工作台', path: '/', icon: DataLine }],
  },
  {
    title: '教务',
    items: [
      { label: '学期与作息', path: '/admin/semesters', icon: Calendar, permission: 'semester:manage' },
      { label: '教师', path: '/admin/teachers', icon: User, permission: 'foundation:manage' },
      { label: '教室', path: '/admin/rooms', icon: OfficeBuilding, permission: 'foundation:manage' },
      { label: '课程', path: '/admin/courses', icon: Reading, permission: 'foundation:manage' },
      { label: '年级', path: '/admin/grades', icon: School, permission: 'foundation:manage' },
      { label: '行政班', path: '/admin/classes', icon: Tickets, permission: 'foundation:manage' },
      {
        label: '授课任务',
        path: '/admin/assignments',
        icon: List,
        permissions: ['assignment:manage', 'assignment:view:own'],
      },
      { label: '约束配置', path: '/admin/constraints', icon: Document, permission: 'constraint:manage' },
      { label: 'AI 数据导入', path: '/admin/ai-import', icon: MagicStick, permission: 'foundation:manage' },
      { label: '自动排课', path: '/admin/scheduling', icon: Timer, permission: 'scheduling:run' },
    ],
  },
  {
    title: '课表',
    items: [{ label: '课表查看', path: '/timetable', icon: Calendar }],
  },
  {
    title: '调代',
    items: [{ label: '调代课', path: '/substitutions', icon: ChatDotRound, permission: 'substitution:submit' }],
  },
  {
    title: '系统',
    items: [
      { label: '通知中心', path: '/notifications', icon: Bell, permission: 'notification:view' },
      { label: '设置中心', path: '/admin/settings', icon: Setting, permission: 'settings:manage' },
      { label: 'AI 供应商', path: '/admin/ai-providers', icon: Cpu, permission: 'settings:manage' },
      { label: 'AI 操作历史', path: '/admin/ai-history', icon: Document, permission: 'settings:manage' },
      { label: '初始化向导', path: '/onboarding', icon: Guide, permission: 'onboarding:manage' },
      { label: '用户管理', path: '/admin/users', icon: User, permission: 'user:manage' },
      { label: '帮助中心', path: '/help', icon: QuestionFilled },
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
          (!item.permissions || auth.hasAnyPermission(item.permissions)),
      ),
    }))
    .filter((group) => group.items.length > 0),
)

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

onMounted(() => {
  auth.refresh().catch(() => undefined)
  settings.load()
  loadNotifications()
  timer = window.setInterval(loadNotifications, 30000)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.shell {
  min-height: 100vh;
}
.aside {
  background: #fff;
  border-right: 1px solid #eef0f5;
  display: flex;
  flex-direction: column;
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
</style>
