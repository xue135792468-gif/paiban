import { describe, expect, it } from 'vitest'
import { settingsTabs, workbenchTabs } from './sectionTabs'

function can(granted: string[]) {
  return (permission: string) => granted.includes(permission)
}

const SUPER_ADMIN = [
  'scheduling:run',
  'timetable:edit',
  'timetable:view',
  'settings:manage',
  'user:manage',
  'foundation:manage',
  'integration:manage',
  'audit:view',
]

describe('课表工作台页签', () => {
  it('排课与编辑权限才看到草稿与排课页签', () => {
    const tabs = workbenchTabs(can(SUPER_ADMIN))
    expect(tabs.map((tab) => tab.to)).toEqual(['/workbench', '/workbench/timetable'])
  })

  it('老师只有查看课表权限时只留下课表页签', () => {
    const tabs = workbenchTabs(can(['timetable:view', 'timetable:view:own', 'assignment:view:own']))
    expect(tabs.map((tab) => tab.to)).toEqual(['/workbench/timetable'])
    expect(tabs.map((tab) => tab.label)).toEqual(['课表'])
  })
})

describe('设置页签', () => {
  it('超级管理员能看到设置、账号与最近删除', () => {
    expect(settingsTabs(can(SUPER_ADMIN)).map((tab) => tab.to)).toEqual([
      '/settings/general',
      '/settings/users',
      '/settings/recycle-bin',
    ])
  })

  it('教务管理员没有账号页签，业务设置仍保留', () => {
    const tabs = settingsTabs(
      can(['settings:manage', 'foundation:manage', 'scheduling:run', 'substitution:approve']),
    )
    expect(tabs.map((tab) => tab.to)).toEqual(['/settings/general', '/settings/recycle-bin'])
  })
})
