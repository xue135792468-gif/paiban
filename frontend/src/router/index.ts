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
