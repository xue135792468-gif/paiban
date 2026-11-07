import { http } from './http'

/** Core entities ticket 03 makes deletable/disableable. Path words match the API. */
export type LifecycleEntityType = 'teacher' | 'class' | 'course' | 'grade' | 'semester' | 'assignment'

/** Entity types that only need a display label in the recycle bin. */
export type LifecycleLabelType =
  | LifecycleEntityType
  | 'import_recipe'
  | 'constraint_rule'
  | 'timetable_draft'
  | 'ai_provider'
  | 'ai_model'
  | 'dingtalk_config'
  | 'rule_group'

export type DeleteMode = 'DELETE' | 'CASCADE'

export interface DeleteImpact {
  entityType: string
  entityId: number
  label: string
  publishedRefs: number
  substitutionRefs: number
  draftRefs: number
  assignmentRefs: number
  ruleRefs: number
  canDelete: boolean
  canDisable: boolean
  cascadeAssignments: number
  cascadeClasses: number
}

export interface DeleteResult {
  batchId: string
  deletedCount: number
}

export interface RestoreResult {
  batchId: string
  restoredCount: number
}

export type RecentDeletionStatus = 'PENDING' | 'RESTORED' | 'EXPIRED'

export interface RecentDeletion {
  id: number
  batchId: string
  entityType: string
  entityId: number
  entityLabel: string
  primary: boolean
  deletedBy: string
  deletedAt: string
  expiresAt: string
  restoredAt?: string | null
  restoredBy?: string | null
  status: RecentDeletionStatus
  daysLeft: number
}

const BASE_PATH: Record<LifecycleEntityType, string> = {
  teacher: '/teachers',
  class: '/classes',
  course: '/courses',
  grade: '/grades',
  semester: '/semesters',
  assignment: '/assignments',
}

export const LIFECYCLE_ENTITY_LABEL: Record<LifecycleLabelType, string> = {
  teacher: '教师',
  class: '行政班',
  course: '课程',
  grade: '年级',
  semester: '学期',
  assignment: '授课任务',
  import_recipe: '导入配方',
  constraint_rule: '排课规则',
  timetable_draft: '课表草稿',
  ai_provider: 'AI 供应商',
  ai_model: 'AI 模型',
  dingtalk_config: '钉钉配置',
  rule_group: '分组',
}

export async function getDeleteImpact(
  type: LifecycleEntityType,
  id: number,
): Promise<DeleteImpact> {
  const { data } = await http.get<DeleteImpact>(`${BASE_PATH[type]}/${id}/delete-impact`)
  return data
}

export async function deleteEntity(
  type: LifecycleEntityType,
  id: number,
  mode: DeleteMode = 'DELETE',
): Promise<DeleteResult> {
  const { data } = await http.delete<DeleteResult>(`${BASE_PATH[type]}/${id}`, {
    params: { mode },
  })
  return data
}

export async function disableEntity(type: LifecycleEntityType, id: number): Promise<void> {
  await http.post(`${BASE_PATH[type]}/${id}/disable`)
}

export async function enableEntity(type: LifecycleEntityType, id: number): Promise<void> {
  await http.post(`${BASE_PATH[type]}/${id}/enable`)
}

export async function listRecentDeletions(includeClosed = false): Promise<RecentDeletion[]> {
  const { data } = await http.get<RecentDeletion[]>('/recent-deletions', {
    params: includeClosed ? { includeClosed: true } : undefined,
  })
  return data ?? []
}

export async function restoreDeletion(batchId: string): Promise<RestoreResult> {
  const { data } = await http.post<RestoreResult>(`/recent-deletions/${batchId}/restore`)
  return data
}

/** Batch rows shown by “最近删除”, one row per deletion batch. */
export interface RecentDeletionBatch {
  batchId: string
  label: string
  entityTypes: string[]
  itemCount: number
  deletedAt: string
  expiresAt: string
  restoredAt?: string | null
  status: RecentDeletionStatus
  daysLeft: number
}

/**
 * Groups raw recycle rows by batch. Kept as a pure function so the view stays a
 * renderer and the grouping has one unit test.
 */
export function groupRecentDeletions(rows: RecentDeletion[]): RecentDeletionBatch[] {
  const batches = new Map<string, RecentDeletion[]>()
  for (const row of rows) {
    const list = batches.get(row.batchId) ?? []
    list.push(row)
    batches.set(row.batchId, list)
  }
  return [...batches.entries()]
    .map(([batchId, items]) => {
      const primary = items.find((item) => item.primary) ?? items[0]
      const pending = items.find((item) => item.status === 'PENDING')
      const representative = pending ?? primary
      const types = [...new Set(items.map((item) => item.entityType))]
      return {
        batchId,
        label: primary.entityLabel,
        entityTypes: types,
        itemCount: items.length,
        deletedAt: representative.deletedAt,
        expiresAt: representative.expiresAt,
        restoredAt: representative.restoredAt,
        status: pending ? 'PENDING' : representative.status,
        daysLeft: representative.daysLeft,
      } satisfies RecentDeletionBatch
    })
    .sort((a, b) => b.deletedAt.localeCompare(a.deletedAt))
}
