import type { NavigationGuard } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 登录与权限守卫：应用路由与守卫回归测试**共用这一份实现**。
 *
 * 之前 `roleAccess.spec.ts` 在测试里抄了一遍同样的判断，断言的是副本：真守卫被改坏
 * （例如把权限判断改成恒放行）测试仍然全绿。守卫放在这里导出后，路由直接接线，
 * 测试也直接接线，篡改真守卫会立刻让矩阵测试变红。
 */
export const authGuard: NavigationGuard = (to) => {
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
  const any = to.meta.permissionAny as string[] | undefined
  if (any && !auth.hasAnyPermission(any)) {
    return { name: 'forbidden' }
  }
  return true
}
