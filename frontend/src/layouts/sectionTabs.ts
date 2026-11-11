import type { PageTabItem } from '@/components/layout/PageTabs.vue'

/** The permission surface these tab builders need; keeps the store out of the pure functions. */
export type Can = (permission: string) => boolean

/**
 * Tabs of 课表工作台. 草稿与排课 opens the scheduler (its route demands
 * `scheduling:run` / `timetable:edit`), so a read-only viewer — teachers now hold
 * `timetable:view` — must not see it, otherwise the tab leads straight to a 403.
 */
export function workbenchTabs(can: Can): PageTabItem[] {
  const tabs: PageTabItem[] = []
  if (can('scheduling:run') || can('timetable:edit')) {
    tabs.push({ label: '草稿与排课', to: '/workbench', activePrefix: '/workbench/drafts' })
  }
  tabs.push({ label: '课表', to: '/workbench/timetable', tour: '/workbench/timetable' })
  return tabs
}

/**
 * Tabs of 设置. 账号 needs `user:manage` (超级管理员 only) and 最近删除 needs
 * `foundation:manage`; 教务管理员 keeps the business settings tab.
 */
export function settingsTabs(can: Can): PageTabItem[] {
  const tabs: PageTabItem[] = [{ label: '设置', to: '/settings/general' }]
  if (can('user:manage')) {
    tabs.push({ label: '账号', to: '/settings/users' })
  }
  if (can('foundation:manage')) {
    tabs.push({ label: '最近删除', to: '/settings/recycle-bin' })
  }
  return tabs
}
