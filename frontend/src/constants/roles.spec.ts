import { describe, expect, it } from 'vitest'
import { PRESET_ROLES, primaryRoleLabel, presetRoleLabel } from './roles'

describe('预置身份', () => {
  it('只提供超级管理员、教务管理员、老师三个身份', () => {
    expect(PRESET_ROLES.map((role) => role.code)).toEqual(['super_admin', 'admin', 'teacher'])
    expect(PRESET_ROLES.map((role) => role.label)).toEqual(['超级管理员', '教务管理员', '老师'])
  })

  it('账号持多个角色时按权限高低给出一个主身份', () => {
    expect(primaryRoleLabel(['teacher', 'super_admin'])).toBe('超级管理员')
    expect(primaryRoleLabel(['teacher', 'admin'])).toBe('教务管理员')
    expect(primaryRoleLabel(['teacher'])).toBe('老师')
    expect(primaryRoleLabel([])).toBe('')
  })

  it('已下线的班主任身份不再显示成产品角色', () => {
    expect(primaryRoleLabel(['head_teacher'])).toBe('')
    expect(presetRoleLabel('head_teacher')).toBe('head_teacher')
  })
})
