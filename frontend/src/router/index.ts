import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { DRAFT_MANAGER_PERMISSIONS } from '@/constants/permissions'
import { dataLandingPath } from '@/layouts/navigation'
import { useAuthStore } from '@/stores/auth'
import { authGuard } from './guard'

/**
 * Single route table for the app. Exported so route-level specs can build a
 * memory-history router without booting the real one (and its auth guard).
 *
 * The product-trim IA keeps seven top-level pages. Legacy entries stay as
 * redirects so old bookmarks, notification links and in-app pushes keep working.
 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
  },
  {
    // Top-level so print styles can drop the shell chrome; HelpView owns its own nav.
    path: '/help',
    name: 'help',
    component: () => import('@/views/HelpView.vue'),
    meta: { title: '帮助中心' },
  },
  {
    path: '/help/:slug',
    name: 'help-doc',
    component: () => import('@/views/HelpView.vue'),
    meta: { title: '帮助中心' },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '总览' },
      },

      // 1. 课表工作台：草稿与排课 + 课表。
      {
        path: 'workbench',
        component: () => import('@/layouts/WorkbenchLayout.vue'),
        children: [
          {
            path: '',
            name: 'scheduling',
            component: () => import('@/views/SchedulingView.vue'),
            meta: {
              title: '课表工作台',
              permissionAny: [...DRAFT_MANAGER_PERMISSIONS],
            },
          },
          {
            path: 'timetable',
            name: 'timetable',
            component: () => import('@/views/TimetableView.vue'),
            meta: { title: '课表' },
          },
          {
            path: 'drafts/:id',
            name: 'draft-editor',
            component: () => import('@/views/DraftEditorView.vue'),
            meta: { title: '草稿', permission: 'timetable:edit' },
          },
        ],
      },

      // 2. 调代课：管理员工作台 + 自助申请。
      {
        path: 'substitution',
        component: () => import('@/layouts/SubstitutionLayout.vue'),
        children: [
          {
            path: '',
            name: 'substitution-workbench',
            component: () => import('@/views/SubstitutionWorkbenchView.vue'),
            meta: {
              title: '调代课',
              permissionAny: ['substitution:submit', 'substitution:approve'],
            },
          },
          {
            path: 'requests',
            name: 'substitutions',
            component: () => import('@/views/SubstitutionsView.vue'),
            meta: { title: '我的调代', permission: 'substitution:submit' },
          },
        ],
      },

      // 3. 排课规则：积木式句子编辑器。
      {
        path: 'rules',
        name: 'rules',
        component: () => import('@/views/RuleBuilderView.vue'),
        meta: { title: '排课规则', permission: 'constraint:manage' },
      },

      // 4. 数据管理。落地页按权限解析：学期 → 教师 → 授课任务，与侧边导航同一份规则。
      {
        path: 'data',
        component: () => import('@/layouts/DataLayout.vue'),
        redirect: () => dataLandingPath((permission) => useAuthStore().hasPermission(permission)),
        children: [
          {
            path: 'semesters',
            name: 'semesters',
            component: () => import('@/views/SemestersView.vue'),
            meta: { title: '学期与作息', permission: 'semester:manage' },
          },
          {
            path: 'semesters/:id/schedule',
            name: 'schedule-editor',
            component: () => import('@/views/ScheduleEditorView.vue'),
            meta: { title: '作息表', permission: 'semester:manage' },
          },
          {
            path: 'teachers',
            name: 'teachers',
            component: () => import('@/views/TeachersView.vue'),
            meta: { title: '教师', permission: 'foundation:manage' },
          },
          {
            path: 'courses',
            name: 'courses',
            component: () => import('@/views/CoursesView.vue'),
            meta: { title: '课程', permission: 'foundation:manage' },
          },
          {
            path: 'grades',
            name: 'grades',
            component: () => import('@/views/GradesView.vue'),
            meta: { title: '年级', permission: 'foundation:manage' },
          },
          {
            path: 'classes',
            name: 'classes',
            component: () => import('@/views/ClassesView.vue'),
            meta: { title: '行政班', permission: 'foundation:manage' },
          },
          {
            path: 'assignments',
            name: 'assignments',
            component: () => import('@/views/AssignmentsView.vue'),
            meta: { title: '授课任务', permissionAny: ['assignment:manage', 'assignment:view:own'] },
          },
          {
            path: 'import',
            name: 'ai-import',
            component: () => import('@/views/AiDataImportView.vue'),
            meta: { title: '导入', permission: 'foundation:manage' },
          },
          {
            path: 'import/manual',
            name: 'manual-import',
            component: () => import('@/views/ManualImportView.vue'),
            meta: { title: '手动导入', permission: 'foundation:manage' },
          },
          {
            path: 'recipes',
            name: 'import-recipes',
            component: () => import('@/views/ImportRecipesView.vue'),
            meta: { title: '导入配方', permission: 'foundation:manage' },
          },
        ],
      },

      // 5. AI 助手。
      {
        path: 'ai',
        name: 'ai-providers',
        component: () => import('@/views/AiProvidersView.vue'),
        meta: { title: 'AI 助手', permission: 'settings:manage' },
      },

      // 6. 设置。
      {
        path: 'settings',
        component: () => import('@/layouts/SettingsLayout.vue'),
        redirect: '/settings/general',
        children: [
          {
            path: 'general',
            name: 'settings',
            component: () => import('@/views/SettingsView.vue'),
            meta: { title: '设置', permission: 'settings:manage' },
          },
          {
            path: 'users',
            name: 'users',
            component: () => import('@/views/UserAdminView.vue'),
            meta: { title: '账号', permission: 'user:manage' },
          },
          {
            // 票 02：账号审计只给持有「账号审计查看」的超级管理员。
            path: 'account-audit',
            name: 'account-audit',
            component: () => import('@/views/AccountAuditView.vue'),
            meta: { title: '账号审计', permission: 'audit:view:account' },
          },
          {
            path: 'recycle-bin',
            name: 'recycle-bin',
            component: () => import('@/views/RecentDeletionsView.vue'),
            meta: { title: '最近删除', permission: 'foundation:manage' },
          },
        ],
      },

      // 旧的高级入口已下线：所有规则都在规则页，旧书签直接回到 `/rules`。
      { path: 'rules/advanced', redirect: '/rules' },

      // 系统页：保留路由，但不再出现在一级导航。
      {
        path: 'onboarding',
        name: 'onboarding',
        component: () => import('@/views/OnboardingView.vue'),
        meta: { title: '初始化向导', permission: 'onboarding:manage' },
      },

      // 旧路由兼容重定向（必须排在兜底 catch-all 之前）。
      { path: 'admin/semesters', redirect: '/data/semesters' },
      {
        path: 'admin/semesters/:id/schedule',
        redirect: (to) => ({ path: `/data/semesters/${to.params.id}/schedule`, query: to.query }),
      },
      { path: 'admin/teachers', redirect: '/data/teachers' },
      { path: 'admin/courses', redirect: '/data/courses' },
      { path: 'admin/grades', redirect: '/data/grades' },
      { path: 'admin/classes', redirect: '/data/classes' },
      { path: 'admin/assignments', redirect: '/data/assignments' },
      {
        path: 'admin/ai-import',
        redirect: (to) => ({ path: '/data/import', query: to.query }),
      },
      {
        path: 'admin/drafts/:id',
        redirect: (to) => ({
          name: 'draft-editor',
          params: { id: to.params.id },
          query: to.query,
        }),
      },
      { path: 'timetable', redirect: '/workbench/timetable' },
      { path: 'admin/scheduling', redirect: '/workbench' },
      {
        // Notifications still link to /substitutions?requestId=... for their
        // request history; keep that intent while the default page is the workbench.
        path: 'substitutions',
        redirect: (to) =>
          to.query.requestId !== undefined
            ? { path: '/substitution/requests', query: to.query }
            : { path: '/substitution' },
      },
      { path: 'substitutions/workbench', redirect: '/substitution' },
      { path: 'admin/constraints', redirect: '/rules' },
      { path: 'admin/ai-providers', redirect: '/ai' },
      { path: 'admin/settings', redirect: '/settings/general' },
      { path: 'admin/users', redirect: '/settings/users' },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(authGuard)

export default router
