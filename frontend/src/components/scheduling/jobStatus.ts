export type JobStatusTagType = 'warning' | 'primary' | 'success' | 'danger' | 'info'

export function isRunning(status?: string | null) {
  return status === 'PENDING' || status === 'RUNNING'
}

export function isTerminal(status?: string | null) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
}

export function getJobStatusTag(status?: string | null): {
  type: JobStatusTagType
  label: string
} {
  switch (status) {
    case 'PENDING':
      return { type: 'warning', label: '排队中' }
    case 'RUNNING':
      return { type: 'primary', label: '求解中' }
    case 'COMPLETED':
      return { type: 'success', label: '已完成' }
    case 'FAILED':
      return { type: 'danger', label: '失败' }
    case 'CANCELLED':
      return { type: 'info', label: '已取消' }
    default:
      return { type: 'info', label: status || '未知' }
  }
}
