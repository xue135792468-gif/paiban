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
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Calendar, DataLine, Setting, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

interface MenuItem {
  label: string
  path: string
  icon: unknown
  permission?: string
}

interface MenuGroup {
  title: string
  items: MenuItem[]
}

const groups: MenuGroup[] = [
  {
    title: '概览',
    items: [{ label: '工作台', path: '/', icon: DataLine }],
  },
  {
    title: '教务',
    items: [
      { label: '学期与作息', path: '/admin/semesters', icon: Calendar, permission: 'semester:manage' },
    ],
  },
  {
    title: '系统',
    items: [{ label: '用户管理', path: '/admin/users', icon: User, permission: 'user:manage' }],
  },
  {
    title: '设置',
    items: [{ label: '设置中心', path: '/admin/settings', icon: Setting, permission: 'settings:manage' }],
  },
]

const visibleGroups = computed(() =>
  groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.permission || auth.hasPermission(item.permission)),
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

function onCommand(command: string) {
  if (command === 'logout') {
    auth.logout()
    router.replace('/login')
  }
}

onMounted(() => {
  auth.refresh().catch(() => undefined)
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
</style>
