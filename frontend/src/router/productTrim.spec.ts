import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router'
import { routes } from '@/router'
import { useAuthStore } from '@/stores/auth'

/**
 * Route-level contract for the seven-page shell. A memory-history router is
 * built from the exported table so the assertions do not need the auth store
 * or the browser history used by the running app.
 */
function makeRouter() {
  return createRouter({ history: createMemoryHistory(), routes })
}

/** The auth store backs the permission-aware `/data` redirect. */
function signIn(permissions: string[]) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    id: 1,
    username: 'tester',
    displayName: '测试账号',
    enabled: true,
    roles: ['admin'],
    permissions,
  }
  return auth
}

const mainChildren = (routes.find((route) => route.path === '/')?.children ??
  []) as RouteRecordRaw[]

describe('product trim routing', () => {
  it('keeps seven top-level pages in the agreed order', () => {
    expect(mainChildren.slice(0, 7).map((route) => route.path)).toEqual([
      '',
      'workbench',
      'substitution',
      'rules',
      'data',
      'ai',
      'settings',
    ])

    const workbench = mainChildren[1]
    expect(workbench.children?.[0].name).toBe('scheduling')
    expect(workbench.children?.[0].meta?.title).toBe('课表工作台')

    const substitution = mainChildren[2]
    expect(substitution.children?.[0].name).toBe('substitution-workbench')
    expect(substitution.children?.[0].meta?.title).toBe('调代课')

    const rules = mainChildren[3]
    expect(rules.name).toBe('rules')
    expect(rules.meta?.title).toBe('排课规则')

    const ai = mainChildren[5]
    expect(ai.name).toBe('ai-providers')
    expect(ai.meta?.title).toBe('AI 助手')

    expect(mainChildren[0].name).toBe('home')
    expect(mainChildren[0].meta?.title).toBe('总览')
  })

  it('exposes the sub pages under their top-level layouts', async () => {
    const workbench = mainChildren[1].children ?? []
    expect(workbench.map((route) => route.path)).toEqual(['', 'timetable', 'drafts/:id'])
    expect(workbench[0].meta?.permissionAny).toEqual(['scheduling:run', 'timetable:edit'])
    expect(workbench[1].meta?.title).toBe('课表')
    expect(workbench[2].meta?.permission).toBe('timetable:edit')

    const substitution = mainChildren[2].children ?? []
    expect(substitution.map((route) => route.path)).toEqual(['', 'requests'])

    const data = mainChildren[4].children ?? []
    expect(data.map((route) => route.path)).toEqual([
      'semesters',
      'semesters/:id/schedule',
      'teachers',
      'courses',
      'grades',
      'classes',
      'assignments',
      'import',
      'import/manual',
      'recipes',
    ])

    const settings = mainChildren[6].children ?? []
    // 票 03 adds the 7-day recycle bin under the settings shell.
    expect(settings.map((route) => route.path)).toEqual(['general', 'users', 'recycle-bin'])
  })

  it('redirects every legacy entry to its new home', async () => {
    const cases: Array<[string, string]> = [
      ['/admin/semesters', '/data/semesters'],
      ['/admin/semesters/7/schedule', '/data/semesters/7/schedule'],
      ['/admin/teachers', '/data/teachers'],
      ['/admin/courses', '/data/courses'],
      ['/admin/grades', '/data/grades'],
      ['/admin/classes', '/data/classes'],
      ['/admin/assignments', '/data/assignments'],
      ['/admin/ai-import?target=workbook', '/data/import?target=workbook'],
      ['/admin/drafts/12', '/workbench/drafts/12'],
      ['/admin/drafts/12?compare=3', '/workbench/drafts/12?compare=3'],
      ['/timetable', '/workbench/timetable'],
      ['/admin/scheduling', '/workbench'],
      ['/substitutions', '/substitution'],
      ['/substitutions?requestId=9', '/substitution/requests?requestId=9'],
      ['/substitutions/workbench', '/substitution'],
      ['/admin/constraints', '/rules'],
      ['/admin/ai-providers', '/ai'],
      ['/admin/settings', '/settings/general'],
      ['/admin/users', '/settings/users'],
    ]

    const router = makeRouter()
    for (const [from, to] of cases) {
      await router.push(from)
      expect(router.currentRoute.value.fullPath, from).toBe(to)
    }
  }, 60_000)

  it('keeps system pages reachable and sends unknown paths home', async () => {
    const router = makeRouter()

    for (const path of [
      '/help',
      '/help/foundation',
      '/onboarding',
      '/login',
      '/403',
    ]) {
      await router.push(path)
      expect(router.currentRoute.value.path, path).toBe(path)
    }

    await router.push('/no/such/page')
    expect(router.currentRoute.value.path).toBe('/')
  }, 30_000)

  it('redirects the bare layout paths to their first section', async () => {
    signIn(['semester:manage', 'foundation:manage'])
    const router = makeRouter()

    await router.push('/data')
    expect(router.currentRoute.value.fullPath).toBe('/data/semesters')

    await router.push('/settings')
    expect(router.currentRoute.value.fullPath).toBe('/settings/general')
  }, 30_000)

  it('sends /data to the first list the caller may open', async () => {
    const cases: Array<[string[], string]> = [
      [['semester:manage'], '/data/semesters'],
      [['foundation:manage'], '/data/teachers'],
      [['assignment:manage'], '/data/assignments'],
      [['assignment:view:own'], '/data/assignments'],
      [[], '/403'],
    ]

    for (const [permissions, expected] of cases) {
      signIn(permissions)
      const router = makeRouter()
      await router.push('/data')
      expect(router.currentRoute.value.fullPath, permissions.join(',') || 'no permissions').toBe(
        expected,
      )
    }
  }, 60_000)
})
