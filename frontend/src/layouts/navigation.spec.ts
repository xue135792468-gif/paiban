import { describe, expect, it } from 'vitest'
import {
  MENU_GROUPS,
  dataLandingPath,
  resolveMenuPath,
  visibleMenuGroups,
  workbenchLandingPath,
  type PermissionReader,
} from './navigation'

function reader(permissions: string[]): PermissionReader {
  return {
    has: (permission) => permissions.includes(permission),
    hasAny: (list) => list.some((permission) => permissions.includes(permission)),
    hasAll: (list) => list.every((permission) => permissions.includes(permission)),
  }
}

/** Every permission an admin account gets, so visibility itself is not the subject. */
const ADMIN = [
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
]

function targets(permissions: string[]): Record<string, string> {
  const auth = reader(permissions)
  const entries = visibleMenuGroups(auth).flatMap((group) => group.items)
  return Object.fromEntries(entries.map((item) => [item.path, item.target]))
}

describe('导航落地页按权限解析', () => {
  it('管理员进工作台本体，只读账号进课表页', () => {
    expect(workbenchLandingPath((permission) => permission === 'scheduling:run')).toBe('/workbench')
    expect(workbenchLandingPath((permission) => permission === 'timetable:edit')).toBe('/workbench')
    expect(workbenchLandingPath((permission) => permission === 'timetable:view')).toBe(
      '/workbench/timetable',
    )
    expect(workbenchLandingPath(() => false)).toBe('/workbench/timetable')
  })

  it('数据管理落到第一个能看的列表', () => {
    expect(dataLandingPath((permission) => permission === 'semester:manage')).toBe('/data/semesters')
    expect(dataLandingPath((permission) => permission === 'foundation:manage')).toBe(
      '/data/teachers',
    )
    expect(dataLandingPath((permission) => permission === 'assignment:view:own')).toBe(
      '/data/assignments',
    )
    expect(dataLandingPath(() => false)).toBe('/403')
  })

  it('只改需按权限解析的入口，其余保持原路径（完整管理组合仍进调代工作台）', () => {
    const can = () => true
    expect(resolveMenuPath('/', can)).toBe('/')
    expect(resolveMenuPath('/rules', can)).toBe('/rules')
    expect(resolveMenuPath('/settings', can)).toBe('/settings')
    expect(resolveMenuPath('/substitution', can)).toBe('/substitution')
  })
})

describe('侧边导航可见性', () => {
  it('管理员七项全在，且落地页就是本体', () => {
    expect(targets(ADMIN)).toEqual({
      '/': '/',
      '/workbench': '/workbench',
      '/substitution': '/substitution',
      '/rules': '/rules',
      '/data': '/data/semesters',
      '/ai': '/ai',
      '/settings': '/settings',
    })
  })

  it('教师账号看不到会 403 的入口，工作台落到自己的课表', () => {
    const teacher = ['timetable:view:own', 'assignment:view:own', 'substitution:submit']
    expect(targets(teacher)).toEqual({
      '/': '/',
      '/workbench': '/workbench/timetable',
      '/substitution': '/substitution/requests',
      '/data': '/data/assignments',
    })
  })

  it('只有 foundation:manage 时数据管理落到教师页', () => {
    expect(targets(['foundation:manage'])).toEqual({
      '/': '/',
      '/data': '/data/teachers',
    })
  })

  it('只读课表权限不会把「课表工作台」指向需要编辑权限的页面', () => {
    const viewer = ['timetable:view', 'timetable:view:class']
    const result = targets(viewer)
    expect(result['/workbench']).toBe('/workbench/timetable')
    expect(result).not.toHaveProperty('/data')
    expect(result).not.toHaveProperty('/rules')
  })

  it('没有调代权限的账号看不到调代课入口', () => {
    expect(targets([])).toEqual({ '/': '/' })
    expect(targets(['substitution:view'])).toEqual({ '/': '/' })
  })

  it('保持一级入口的稳定锚点供新手教程与 e2e 定位', () => {
    expect(MENU_GROUPS.flatMap((group) => group.items).map((item) => item.path)).toEqual([
      '/',
      '/workbench',
      '/substitution',
      '/rules',
      '/data',
      '/ai',
      '/settings',
    ])
  })
})
