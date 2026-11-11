import { http } from './http'
import type { LoginType } from '@/stores/auth'

/** 票 02：账号审计查看接口。后端只返回 allowlist 快照，不含密码、哈希或 token。 */
export interface AccountSnapshot {
  username: string
  displayName: string
  enabled: boolean
  teacherId: number | null
  teacherName: string | null
  roleCodes: string[]
  loginType?: LoginType | null
  dingtalkUserId?: string | null
}

export interface AccountAuditEntry {
  id: number
  createdAt: string
  actor: string
  action: string
  targetUserId: number | null
  targetUsername: string | null
  targetDisplayName: string | null
  /** 旧记录的原文描述（纯文本展示）；新事件也有可读摘要。 */
  detail?: string | null
  successRows?: number
  failedRows?: number
  before: AccountSnapshot | null
  after: AccountSnapshot | null
}

export interface AccountAuditPage {
  items: AccountAuditEntry[]
  page: number
  size: number
  total: number
}

export interface AccountActionOption {
  code: string
  label: string
}

/** 目标账号下拉的数据源：现有账号接口，包含已停用账号（历史审计要选得到它们）。 */
export interface AccountOption {
  id: number
  username: string
  displayName: string
  enabled: boolean
}

export interface AccountAuditQuery {
  action?: string
  targetUserId?: number
  page: number
  size: number
}

export async function listAccountAudits(query: AccountAuditQuery): Promise<AccountAuditPage> {
  const { data } = await http.get<AccountAuditPage>('/account-audits', { params: query })
  return data
}

export async function listAccountAuditActions(): Promise<AccountActionOption[]> {
  const { data } = await http.get<AccountActionOption[]>('/account-audits/actions')
  return data ?? []
}

export async function listAccountOptions(): Promise<AccountOption[]> {
  const { data } = await http.get<AccountOption[]>('/users')
  return data ?? []
}
