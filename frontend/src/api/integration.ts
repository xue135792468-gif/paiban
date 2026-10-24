/** Result of an organisation sync (`/integrations/{platform}/sync`). */
export interface SyncResult {
  success: boolean
  created: number
  updated: number
  deactivated: number
  skipped: number
  suspected: number
  departmentsScanned: number
  usersScanned: number
  elapsedMs: number
  message: string
}

export interface DingTalkConfig {
  enabled: boolean
  appKey: string
  appSecret?: string
  hasSecret: boolean
  agentId: string
  corpId?: string
  syncStrategy: string
}

export interface FeishuConfig {
  enabled: boolean
  appId: string
  appSecret?: string
  hasSecret: boolean
  syncStrategy: string
  verificationToken?: string
  verificationTokenConfigured: boolean
}

export interface SyncFieldChange {
  field: string
  label: string
  localValue: string | null
  remoteValue: string | null
}

export interface SyncCandidate {
  id: number
  name: string
  employeeNo: string | null
  phone: string | null
}

export type SyncCategory = 'CREATE' | 'MERGE' | 'SUSPECTED' | 'SKIP'

export interface SyncItem {
  userId: string
  name: string
  employeeNo: string | null
  phone: string | null
  departments: string[]
  active: boolean
  category: SyncCategory
  reason: string
  matchedTeacherId: number | null
  matchedTeacherName: string | null
  fills: SyncFieldChange[]
  conflicts: SyncFieldChange[]
  candidates: SyncCandidate[]
}

export interface SyncDepartmentGroup {
  department: string
  items: SyncItem[]
}

export interface SyncSummary {
  create: number
  merge: number
  suspected: number
  skip: number
}

export interface SyncPreview {
  success: boolean
  message: string
  departmentsScanned: number
  usersScanned: number
  skippedDepartments: number
  failedDepartments: number
  elapsedMs: number
  skippedDepartmentNames: string[]
  summary: SyncSummary
  departments: SyncDepartmentGroup[]
}

export type SuspectedAction = 'MERGE' | 'CREATE' | 'SKIP'

export interface SuspectedDecision {
  userId: string
  action: SuspectedAction
  teacherId?: number
}

export interface SyncConfirmRequest {
  excludedDepartments: string[]
  excludedUserIds: string[]
  suspectedDecisions: SuspectedDecision[]
}

/**
 * Mirrors the backend exclusion rule: a user is dropped only when every department
 * they belong to is excluded (or when the user is explicitly excluded).
 */
export function isUserIncluded(
  item: Pick<SyncItem, 'userId' | 'departments'>,
  excludedDepartments: string[],
  excludedUserIds: string[],
): boolean {
  if (excludedUserIds.includes(item.userId)) {
    return false
  }
  if (item.departments.length === 0) {
    return true
  }
  return !item.departments.every((department) => excludedDepartments.includes(department))
}
