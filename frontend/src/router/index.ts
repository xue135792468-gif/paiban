import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
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
      path: '/',
      component: () => import('@/layouts/MainLayout.vue'),
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/views/HomeView.vue'),
          meta: { title: '工作台' },
        },
        {
          path: 'admin/semesters',
          name: 'semesters',
          component: () => import('@/views/SemestersView.vue'),
          meta: { title: '学期与作息', permission: 'semester:manage' },
        },
        {
          path: 'admin/semesters/:id/schedule',
          name: 'schedule-editor',
          component: () => import('@/views/ScheduleEditorView.vue'),
          meta: { title: '作息表', permission: 'semester:manage' },
        },
        {
          path: 'admin/teachers',
          name: 'teachers',
          component: () => import('@/views/TeachersView.vue'),
          meta: { title: '教师', permission: 'foundation:manage' },
        },
        {
          path: 'admin/rooms',
          name: 'rooms',
          component: () => import('@/views/RoomsView.vue'),
          meta: { title: '教室', permission: 'foundation:manage' },
        },
        {
          path: 'admin/courses',
          name: 'courses',
          component: () => import('@/views/CoursesView.vue'),
          meta: { title: '课程', permission: 'foundation:manage' },
        },
        {
          path: 'admin/grades',
          name: 'grades',
          component: () => import('@/views/GradesView.vue'),
          meta: { title: '年级', permission: 'foundation:manage' },
        },
        {
          path: 'admin/classes',
          name: 'classes',
          component: () => import('@/views/ClassesView.vue'),
          meta: { title: '行政班', permission: 'foundation:manage' },
        },
        {
          path: 'admin/assignments',
          name: 'assignments',
          component: () => import('@/views/AssignmentsView.vue'),
          meta: { title: '授课任务', permission: 'assignment:manage' },
        },
        {
          path: 'admin/constraints',
          name: 'constraints',
          component: () => import('@/views/ConstraintsView.vue'),
          meta: { title: '约束配置', permission: 'constraint:manage' },
        },
        {
          path: 'admin/scheduling',
          name: 'scheduling',
          component: () => import('@/views/SchedulingView.vue'),
          meta: { title: '自动排课', permission: 'scheduling:run' },
        },
        {
          path: 'admin/drafts/:id',
          name: 'draft-editor',
          component: () => import('@/views/DraftEditorView.vue'),
          meta: { title: '课表草稿', permission: 'timetable:edit' },
        },
        {
          path: 'admin/users',
          name: 'users',
          component: () => import('@/views/UserAdminView.vue'),
          meta: { title: '用户管理', permission: 'user:manage' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  const required = to.meta.permission as string | undefined
  if (required && !auth.hasPermission(required)) {
    return { name: 'forbidden' }
  }
  return true
})

export default router
