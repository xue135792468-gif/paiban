import {
  Calendar,
  ChatDotRound,
  DataLine,
  Document,
  List,
  MagicStick,
  Setting,
} from '@element-plus/icons-vue'

/**
 * 侧边导航的唯一一份定义。
 *
 * 菜单项的可见性和它点进去的落地页必须是同一套权限，否则就会出现
 * 「看得见、点进去 403」：课表工作台对只读账号要落到课表页，数据管理
 * 对没有学期权限的账号要落到它确实能看的第一个列表。
 */
export interface MenuItem {
  label: string
  /** 一级入口路径，同时充当菜单高亮与 e2e/新手教程的定位锚（`data-tour`）。 */
  path: string
  icon: unknown
  permission?: string
  permissions?: string[]
  allPermissions?: string[]
}

export interface MenuGroup {
  title: string
  items: MenuItem[]
}

/** The auth surface this module needs; keeps the store out of the pure functions. */
export interface PermissionReader {
  has(permission: string): boolean
  hasAny(permissions: string[]): boolean
  hasAll(permissions: string[]): boolean
}

export const MENU_GROUPS: MenuGroup[] = [
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
        permissions: ['substitution:submit', 'substitution:approve'],
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

/**
 * First page of 数据管理 the caller is actually allowed to open. Mirrors the
 * `redirect` of the `/data` route so the menu and the router cannot disagree.
 */
export function dataLandingPath(can: (permission: string) => boolean): string {
  if (can('semester:manage')) return '/data/semesters'
  if (can('foundation:manage')) return '/data/teachers'
  if (can('assignment:manage') || can('assignment:view:own')) return '/data/assignments'
  return '/403'
}

/**
 * First page of 课表工作台 the caller may open: editors and schedulers get the
 * workbench itself, read-only timetable viewers get the timetable page.
 */
export function workbenchLandingPath(can: (permission: string) => boolean): string {
  return can('scheduling:run') || can('timetable:edit') ? '/workbench' : '/workbench/timetable'
}

/** Where a menu entry actually navigates to for this caller. */
export function resolveMenuPath(path: string, can: (permission: string) => boolean): string {
  if (path === '/workbench') return workbenchLandingPath(can)
  if (path === '/data') return dataLandingPath(can)
  return path
}

function isVisible(item: MenuItem, auth: PermissionReader): boolean {
  return (
    (!item.permission || auth.has(item.permission)) &&
    (!item.permissions || auth.hasAny(item.permissions)) &&
    (!item.allPermissions || auth.hasAll(item.allPermissions))
  )
}

export interface ResolvedMenuItem extends MenuItem {
  /** Resolved landing path; `path` stays the stable entry id for highlight and tours. */
  target: string
}

export interface ResolvedMenuGroup {
  title: string
  items: ResolvedMenuItem[]
}

/** Menu groups filtered by permission, with each item carrying its landing path. */
export function visibleMenuGroups(
  auth: PermissionReader,
  groups: MenuGroup[] = MENU_GROUPS,
): ResolvedMenuGroup[] {
  const can = (permission: string) => auth.has(permission)
  return groups
    .map((group) => ({
      title: group.title,
      items: group.items
        .filter((item) => isVisible(item, auth))
        .map((item) => ({ ...item, target: resolveMenuPath(item.path, can) })),
    }))
    .filter((group) => group.items.length > 0)
}
