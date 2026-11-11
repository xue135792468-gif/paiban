import type { AccountSnapshot } from '@/api/accountAudit'
import { presetRoleLabel } from '@/constants/roles'

/**
 * 票 02：把审计快照翻成中文，页面不裸露 JSON。
 * 纯函数，便于单测覆盖「身份 / 启用 / 姓名 / 关联教师」四类变化的说法。
 */

export function accountRoleLabel(roles: string[] | null | undefined): string {
  if (!roles || roles.length === 0) {
    return '—'
  }
  return roles.map(presetRoleLabel).join(' / ')
}

export function accountEnabledLabel(enabled: boolean): string {
  return enabled ? '启用' : '停用'
}

function teacherText(snapshot: AccountSnapshot): string {
  if (!snapshot.teacherId) {
    return '未关联'
  }
  // 带上 ID：同名不同人换绑时要看得出「换了一个人」，而不是两段一样的姓名。
  const name = snapshot.teacherName ?? '教师'
  return `${name}（#${snapshot.teacherId}）`
}

/** 单条快照的完整状态（新建事件直接展示它）。 */
export function describeAccountState(snapshot: AccountSnapshot | null): string {
  if (!snapshot) {
    return '—'
  }
  const parts = [`身份：${accountRoleLabel(snapshot.roleCodes)}`, `状态：${accountEnabledLabel(snapshot.enabled)}`]
  if (snapshot.teacherId) {
    parts.push(`关联教师：${teacherText(snapshot)}`)
  }
  return parts.join('；')
}

/** 变更前 → 变更后；没有可见变化时写「—」（例如重置密码只改哈希）。 */
export function describeAccountChange(
  before: AccountSnapshot | null,
  after: AccountSnapshot | null,
): string {
  if (!before) {
    return after ? `新建：${describeAccountState(after)}` : '—'
  }
  if (!after) {
    return '—'
  }
  const changes: string[] = []
  if (accountRoleLabel(before.roleCodes) !== accountRoleLabel(after.roleCodes)) {
    changes.push(
      `身份：${accountRoleLabel(before.roleCodes)} → ${accountRoleLabel(after.roleCodes)}`,
    )
  }
  if (before.enabled !== after.enabled) {
    changes.push(`状态：${accountEnabledLabel(before.enabled)} → ${accountEnabledLabel(after.enabled)}`)
  }
  if (before.displayName !== after.displayName) {
    changes.push(`姓名：${before.displayName} → ${after.displayName}`)
  }
  // 按稳定 ID 判定换绑：同名不同 ID 也要显示变化。
  if (before.teacherId !== after.teacherId) {
    changes.push(`关联教师：${teacherText(before)} → ${teacherText(after)}`)
  }
  return changes.length > 0 ? changes.join('；') : '—'
}
