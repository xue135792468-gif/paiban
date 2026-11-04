import { http } from '@/api/http'

export type MigrationAction = 'RETAIN' | 'CONVERT' | 'DISABLE' | 'REVIEW'

export interface MigrationRow {
  oldType: string
  oldTypeName: string
  oldEntryId: number
  action: MigrationAction | string
  newType: string | null
  groupKey: string | null
  params: Record<string, unknown>
  note: string | null
  requiresReview: boolean
}

export interface MigrationPreview {
  semesterId: number
  configCount: number
  entryCount: number
  settingsFingerprint: string
  retained: number
  converted: number
  disabled: number
  review: number
  rows: MigrationRow[]
}

export interface MigrationEvaluation {
  skipped: boolean
  lessons: number
  hardConflicts: number
  softPenalty: number
  settingsFingerprint: string | null
  configCount: number
  enabledConfigCount: number
  entryCount: number
  enabledEntryCount: number
}

export interface MigrationReport {
  runId: string
  semesterId: number
  status: string
  startedAt: string
  finishedAt: string | null
  runBy: string
  retained: number
  converted: number
  disabled: number
  review: number
  before: MigrationEvaluation
  after: MigrationEvaluation
  rows: MigrationRow[]
}

/** Dry run of the 26-to-19 migration: counts plus one row per old config/entry. */
export async function fetchMigrationPreview(semesterId: number): Promise<MigrationPreview> {
  const { data } = await http.get<MigrationPreview>('/constraint-migration/preview', {
    params: { semesterId },
  })
  return data
}

/** Applies the migration (idempotent) and returns its before/after report. */
export async function runConstraintMigration(semesterId: number): Promise<MigrationReport> {
  const { data } = await http.post<MigrationReport>('/constraint-migration/run', null, {
    params: { semesterId },
  })
  return data
}

/** The latest run, or null when the semester has never been migrated. */
export async function fetchMigrationReport(
  semesterId: number,
): Promise<MigrationReport | null> {
  const response = await http.get<MigrationReport>('/constraint-migration/report', {
    params: { semesterId },
    validateStatus: (status) => status === 204 || (status >= 200 && status < 300),
  })
  return response.status === 204 ? null : (response.data ?? null)
}

export function actionLabel(action: string): string {
  switch (action) {
    case 'RETAIN':
      return '保留'
    case 'CONVERT':
      return '转换'
    case 'DISABLE':
      return '停用'
    case 'REVIEW':
      return '待确认'
    default:
      return action
  }
}

export function actionTagType(action: string): 'success' | 'info' | 'danger' | 'warning' {
  switch (action) {
    case 'RETAIN':
      return 'success'
    case 'DISABLE':
      return 'info'
    case 'REVIEW':
      return 'warning'
    default:
      return 'info'
  }
}
