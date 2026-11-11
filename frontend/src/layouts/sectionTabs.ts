import type { PageTabItem } from '@/components/layout/PageTabs.vue'
import { canManageDrafts } from '@/constants/permissions'

/** The permission surface these tab builders need; keeps the store out of the pure functions. */
export type Can = (permission: string) => boolean

/**
 * Tabs of 课表工作台. 草稿与排课 opens the scheduler (its route demands
 * `canManageDrafts`: `scheduling:run` or `timetable:edit`), so a read-only viewer —
 * teachers now hold `timetable:view` — must not see it, otherwise the tab leads
 * straight to a 403. The same predicate guards the route and the nav landing page.
 */
export function workbenchTabs(can: Can): PageTabItem[] {
  const tabs: PageTabItem[] = []
  if (canManageDrafts(can)) {
    tabs.push({ label: '草稿与排课', to: '/workbench', activePrefix: '/workbench/drafts' })
  }
  tabs.push({ label: '课表', to: '/workbench/timetable', tour: '/workbench/timetable' })
  return tabs
}

/**
 * Tabs of 设置. 账号 needs `user:manage` and 账号审计 needs `audit:view:account`
 * (both 超级管理员 only), 最近删除 needs `foundation:manage`; 教务管理员 keeps the
 * business settings tab.
 */
export function settingsTabs(can: Can): PageTabItem[] {
  const tabs: PageTabItem[] = [{ label: '设置', to: '/settings/general' }]
  if (can('user:manage')) {
    tabs.push({ label: '账号', to: '/settings/users' })
  }
  if (can('audit:view:account')) {
    tabs.push({ label: '账号审计', to: '/settings/account-audit' })
  }
  if (can('foundation:manage')) {
    tabs.push({ label: '最近删除', to: '/settings/recycle-bin' })
  }
  return tabs
}
