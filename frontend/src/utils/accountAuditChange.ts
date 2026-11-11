import type { AccountAuditEntry, AccountSnapshot } from '@/api/accountAudit'
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

export function accountLoginTypeLabel(loginType?: string | null): string {
  if (loginType === 'DINGTALK') return '钉钉免登'
  if (loginType === 'PASSWORD') return '密码登录'
  return '—'
}

function dingtalkUidText(uid?: string | null): string {
  const trimmed = uid?.trim()
  return trimmed ? trimmed : '—'
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
  if (snapshot.loginType) {
    parts.push(`登录方式：${accountLoginTypeLabel(snapshot.loginType)}`)
  }
  if (snapshot.dingtalkUserId?.trim()) {
    parts.push(`钉钉UID：${snapshot.dingtalkUserId.trim()}`)
  }
  return parts.join('；')
}

/** 变更前 → 变更后；没有可见变化时写「—」（例如重置密码只改哈希）。 */
export function describeAccountChange(
  before: AccountSnapshot | null,
  after: AccountSnapshot | null,
  action?: string,
): string {
  if (action === 'ACCOUNT_RESET_PASSWORD') {
    return '密码已重置（内容不记录）'
  }
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
  const beforeLoginType = before.loginType ?? null
  const afterLoginType = after.loginType ?? null
  if (beforeLoginType && afterLoginType && beforeLoginType !== afterLoginType) {
    changes.push(
      `登录方式：${accountLoginTypeLabel(beforeLoginType)} → ${accountLoginTypeLabel(afterLoginType)}`,
    )
  }
  const beforeUid = before.dingtalkUserId?.trim() || null
  const afterUid = after.dingtalkUserId?.trim() || null
  const isLegacyUnknownBefore = before.loginType == null && before.dingtalkUserId == null
  if (
    beforeUid !== afterUid &&
    (!isLegacyUnknownBefore || action === 'DINGTALK_LINK')
  ) {
    changes.push(`钉钉UID：${dingtalkUidText(beforeUid)} → ${dingtalkUidText(afterUid)}`)
  }
  return changes.length > 0 ? changes.join('；') : '—'
}

type AuditRow = Pick<
  AccountAuditEntry,
  | 'action'
  | 'targetUserId'
  | 'targetUsername'
  | 'targetDisplayName'
  | 'detail'
  | 'successRows'
  | 'failedRows'
  | 'before'
  | 'after'
>

/** 批量导入没有单一目标账号，整批摘要不是某个人的事件。 */
function isBatchSummary(entry: AuditRow): boolean {
  return entry.action === 'ACCOUNT_IMPORT' && entry.targetUserId == null
}

/**
 * 目标账号列：逐账号事件显示人；整批摘要与升级前的历史记录明确区分。
 * 历史行没有记录目标 ID，不能从描述文本猜人。
 */
export function describeAuditTarget(entry: AuditRow): string {
  if (entry.targetUserId != null) {
    const username = entry.targetUsername ?? ''
    const displayName = entry.targetDisplayName ?? username
    return username ? `${displayName}（${username}）` : displayName
  }
  return isBatchSummary(entry) ? '整批摘要' : '历史记录（未记录目标账号）'
}

/**
 * 变更列：逐账号事件翻译前后快照；历史记录保留可读原文；整批摘要给出成功 / 失败数。
 * 旧原文只作为文本渲染，不解析、不 v-html。
 */
export function describeAuditRowChange(entry: AuditRow): string {
  if (entry.targetUserId != null) {
    return describeAccountChange(entry.before, entry.after, entry.action)
  }
  const detail = entry.detail?.trim()
  if (isBatchSummary(entry)) {
    const counts = `成功 ${entry.successRows ?? 0}，失败 ${entry.failedRows ?? 0}`
    return detail ? `整批摘要：${counts}；${detail}` : `整批摘要：${counts}`
  }
  return detail ? `历史记录：${detail}` : '历史记录（未记录变更明细）'
}
