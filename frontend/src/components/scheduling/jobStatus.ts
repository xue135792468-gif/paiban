import type { DraftItem, JobItem } from './types'

export type JobStatusTagType = 'warning' | 'primary' | 'success' | 'danger' | 'info'

export function isRunning(status?: string | null) {
  return status === 'PENDING' || status === 'RUNNING'
}

export function isTerminal(status?: string | null) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
}

export function parseHardConflicts(score?: string | null): number {
  if (!score) return 0
  const match = /(-?\d+)\s*hard/i.exec(score)
  if (!match) return 0
  const value = Number(match[1])
  return value < 0 ? Math.abs(value) : 0
}

export function getJobHardConflictCount(
  job?: Pick<JobItem, 'score' | 'diagnostics'> | null,
  draft?: Pick<DraftItem, 'scoreHard'> | null,
): number {
  if (!job) return 0
  const fromScore = parseHardConflicts(job.score)
  if (fromScore > 0) return fromScore
  if (draft && draft.scoreHard < 0) return Math.abs(draft.scoreHard)
  if (job.diagnostics && job.diagnostics.length > 0) {
    const sum = job.diagnostics.reduce((total, row) => total + (row.hardCount || 0), 0)
    return sum > 0 ? sum : job.diagnostics.length
  }
  return 0
}

export function isCompletedWithConflicts(
  job?: Pick<JobItem, 'status' | 'score' | 'diagnostics' | 'resultPublishable'> | null,
  draft?: Pick<DraftItem, 'scoreHard'> | null,
): boolean {
  if (!job || job.status !== 'COMPLETED') return false
  if (getJobHardConflictCount(job, draft) > 0) return true
  return job.resultPublishable === false
}

export function getJobStatusTag(
  status?: string | null,
  hasConflicts = false,
): {
  type: JobStatusTagType
  label: string
} {
  switch (status) {
    case 'PENDING':
      return { type: 'warning', label: '排队中' }
    case 'RUNNING':
      return { type: 'primary', label: '求解中' }
    case 'COMPLETED':
      return hasConflicts
        ? { type: 'warning', label: '已完成，但存在冲突暂不可发布' }
        : { type: 'success', label: '已完成' }
    case 'FAILED':
      return { type: 'danger', label: '失败' }
    case 'CANCELLED':
      return { type: 'info', label: '已取消' }
    default:
      return { type: 'info', label: status || '未知' }
  }
}
