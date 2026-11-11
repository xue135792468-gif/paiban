import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '@/router'
import { resolveMenuPath, visibleMenuGroups, type PermissionReader } from '@/layouts/navigation'
import { useAuthStore } from '@/stores/auth'

/**
 * 票 01 的越权核对（前端一侧）：老师新增的「查看全部课表」只是只读扩权，
 * 路由守卫与侧边菜单都不能因此把老师当成管理员。
 */
const SUPER_ADMIN = [
  'scheduling:run',
  'scheduling:view',
  'timetable:edit',
  'timetable:view',
  'timetable:view:own',
  'timetable:view:class',
  'substitution:submit',
  'substitution:approve',
  'constraint:manage',
  'semester:manage',
  'foundation:manage',
  'assignment:manage',
  'settings:manage',
  'user:manage',
  'integration:manage',
  'audit:view:account',
]

/** 教务管理员 = 超级管理员减去用户与角色管理、集成配置、账号审计查看。 */
const ACADEMIC_ADMIN = SUPER_ADMIN.filter(
  (permission) =>
    !['user:manage', 'integration:manage', 'audit:view:account'].includes(permission),
)

/** 老师 = 原有权限 + 全校课表只读。 */
const TEACHER = [
  'timetable:view:own',
  'timetable:view',
  'assignment:view:own',
  'substitution:submit',
]

function signIn(permissions: string[]) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.token = 'test-token'
  auth.user = {
    id: 1,
    username: 'tester',
    displayName: '测试账号',
    enabled: true,
    roles: [],
    permissions,
  }
  return auth
}

function makeRouter() {
  const router = createRouter({ history: createMemoryHistory(), routes })
  router.beforeEach((to) => {
    const auth = useAuthStore()
    if (to.meta.public) return true
    if (!auth.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
    const required = to.meta.permission as string | undefined
    if (required && !auth.hasPermission(required)) return { name: 'forbidden' }
    const any = to.meta.permissionAny as string[] | undefined
    if (any && !auth.hasAnyPermission(any)) return { name: 'forbidden' }
    return true
  })
  return router
}

async function landingPermission(path: string) {
  signIn(TEACHER)
  const router = makeRouter()
  await router.push(path)
  return router.currentRoute.value.name
}

function menuTargets(permissions: string[]): string[] {
  const reader: PermissionReader = {
    has: (permission) => permissions.includes(permission),
    hasAny: (list) => list.some((permission) => permissions.includes(permission)),
    hasAll: (list) => list.every((permission) => permissions.includes(permission)),
  }
  return visibleMenuGroups(reader).flatMap((group) => group.items.map((item) => item.path))
}

beforeEach(() => {
  localStorage.clear()
})

describe('老师只有只读课表权限', () => {
  it('进得了课表页，进不了草稿与排课、规则、设置', async () => {
    expect(await landingPermission('/workbench/timetable')).toBe('timetable')
    expect(await landingPermission('/workbench')).toBe('forbidden')
    expect(await landingPermission('/workbench/drafts/1')).toBe('forbidden')
    expect(await landingPermission('/rules')).toBe('forbidden')
    expect(await landingPermission('/settings/general')).toBe('forbidden')
    expect(await landingPermission('/settings/users')).toBe('forbidden')
  })

  it('菜单里没有排课规则与设置，工作台落到课表页', async () => {
    signIn(TEACHER)
    const router = makeRouter()
    await router.push('/')

    expect(menuTargets(TEACHER)).toEqual(['/', '/workbench', '/substitution', '/data'])
    // 菜单点「课表工作台」走的是解析后的落地页；守卫本身对裸 /workbench 仍返回 403。
    const target = resolveMenuPath('/workbench', (permission) => TEACHER.includes(permission))
    expect(target).toBe('/workbench/timetable')
    await router.push(target)
    expect(router.currentRoute.value.name).toBe('timetable')
  })
})

describe('教务管理员碰不到账号与集成配置', () => {
  it('业务页面照常，账号页被守卫拦下', async () => {
    signIn(ACADEMIC_ADMIN)
    const router = makeRouter()

    for (const path of ['/workbench', '/rules', '/settings/general', '/substitution']) {
      await router.push(path)
      expect(router.currentRoute.value.name).not.toBe('forbidden')
    }

    await router.push('/settings/users')
    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('菜单里仍有设置入口，因为业务设置归教务', () => {
    expect(menuTargets(ACADEMIC_ADMIN)).toContain('/settings')
  })
})

describe('超级管理员全权', () => {
  it('账号页与设置页都能打开', async () => {
    signIn(SUPER_ADMIN)
    const router = makeRouter()

    await router.push('/settings/users')
    expect(router.currentRoute.value.name).toBe('users')
    await router.push('/settings/general')
    expect(router.currentRoute.value.name).toBe('settings')
  })
})
