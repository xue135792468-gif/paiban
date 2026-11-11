import { describe, expect, it } from 'vitest'
import {
  accountEnabledLabel,
  accountRoleLabel,
  describeAccountChange,
  describeAccountState,
  describeAuditRowChange,
  describeAuditTarget,
} from './accountAuditChange'
import type { AccountSnapshot } from '@/api/accountAudit'

const base: AccountSnapshot = {
  username: 'zhang',
  displayName: '张三',
  enabled: true,
  teacherId: 7,
  teacherName: '张三',
  roleCodes: ['teacher'],
}

describe('账号审计的中文快照', () => {
  it('身份与启停都用产品语言', () => {
    expect(accountRoleLabel(['teacher'])).toBe('老师')
    expect(accountRoleLabel(['admin', 'super_admin'])).toBe('教务管理员 / 超级管理员')
    expect(accountRoleLabel([])).toBe('—')
    expect(accountEnabledLabel(true)).toBe('启用')
    expect(accountEnabledLabel(false)).toBe('停用')
  })

  it('新建事件写全新状态，而不是一段空变更', () => {
    expect(describeAccountChange(null, base)).toBe(
      '新建：身份：老师；状态：启用；关联教师：张三（#7）',
    )
    expect(describeAccountState(base)).toBe('身份：老师；状态：启用；关联教师：张三（#7）')
  })

  it('改身份、停用、改姓名、换关联教师各说清楚从什么变成什么', () => {
    expect(describeAccountChange(base, { ...base, roleCodes: ['admin'] })).toBe(
      '身份：老师 → 教务管理员',
    )
    expect(describeAccountChange(base, { ...base, enabled: false })).toBe('状态：启用 → 停用')
    expect(describeAccountChange(base, { ...base, displayName: '张三丰' })).toBe(
      '姓名：张三 → 张三丰',
    )
    expect(
      describeAccountChange(base, { ...base, teacherId: null, teacherName: null }),
    ).toBe('关联教师：张三（#7） → 未关联')
  })

  it('同名不同 ID 的换绑必须显示出来（不能因为姓名一样就吞掉）', () => {
    expect(
      describeAccountChange(
        { ...base, teacherId: 7, teacherName: '李四' },
        { ...base, teacherId: 8, teacherName: '李四' },
      ),
    ).toBe('关联教师：李四（#7） → 李四（#8）')
  })

  it('多条变化按固定顺序拼接', () => {
    expect(
      describeAccountChange(base, {
        ...base,
        roleCodes: ['admin'],
        enabled: false,
        displayName: '张三丰',
      }),
    ).toBe('身份：老师 → 教务管理员；状态：启用 → 停用；姓名：张三 → 张三丰')
  })

  it('重置密码这类看不见的变化不编造内容', () => {
    expect(describeAccountChange(base, base)).toBe('—')
  })

  it('重置密码明确说明已重置，且内容不记录', () => {
    expect(describeAccountChange(base, base, 'ACCOUNT_RESET_PASSWORD')).toBe(
      '密码已重置（内容不记录）',
    )
  })

  it('历史记录与整批摘要只展示旧原文，不猜目标账号', () => {
    const legacy = {
      action: 'ACCOUNT_DISABLE',
      targetUserId: null,
      targetUsername: null,
      targetDisplayName: null,
      detail: '停用用户「旧存档」(legacy_zhang)',
      successRows: 1,
      failedRows: 0,
      before: null,
      after: null,
    }
    expect(describeAuditTarget(legacy)).toBe('历史记录（未记录目标账号）')
    expect(describeAuditRowChange(legacy)).toBe('历史记录：停用用户「旧存档」(legacy_zhang)')

    const summary = {
      action: 'ACCOUNT_IMPORT',
      targetUserId: null,
      targetUsername: null,
      targetDisplayName: null,
      detail: '文件: users.xlsx, 成功 2 行',
      successRows: 2,
      failedRows: 1,
      before: null,
      after: null,
    }
    expect(describeAuditTarget(summary)).toBe('整批摘要')
    expect(describeAuditRowChange(summary)).toBe(
      '整批摘要：成功 2，失败 1；文件: users.xlsx, 成功 2 行',
    )
  })
})
