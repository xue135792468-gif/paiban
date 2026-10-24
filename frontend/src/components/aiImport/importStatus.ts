/**
 * Every judgement about an import session's status lives here: the labels the page shows,
 * which statuses can be restored, which block an edit or a commit, and the small
 * predicates the templates use instead of comparing status literals.
 */
export const STATUS_LABELS: Record<string, string> = {
  PARSING: '解析中',
  PREVIEW: '待确认',
  EDITED: '已微调',
  COMMITTED: '已入库',
  FAILED: '解析失败',
  PARTIAL: '部分结果',
  ABANDONED: '已放弃',
}

/** Statuses a restored session can render; anything else is not worth showing. */
export const RESTORABLE_STATUSES: ReadonlySet<string> = new Set([
  'PARSING',
  'PREVIEW',
  'EDITED',
  'COMMITTED',
  'FAILED',
  'PARTIAL',
])

/** Statuses that must not accept an edit or a commit. */
export const LOCKED_STATUSES: ReadonlySet<string> = new Set(['PARSING', 'PARTIAL', 'ABANDONED'])

/** Statuses whose rows the user may still correct. */
export const EDITABLE_STATUSES: ReadonlySet<string> = new Set(['PREVIEW', 'EDITED'])

export function statusText(status: string): string {
  return STATUS_LABELS[status] ?? status
}

export function statusTagType(status: string): 'success' | 'danger' | 'info' | 'warning' {
  if (status === 'COMMITTED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PARSING' || status === 'PARTIAL') return 'warning'
  return 'info'
}

export function isParsing(status?: string | null): boolean {
  return status === 'PARSING'
}

export function isPartial(status?: string | null): boolean {
  return status === 'PARTIAL'
}

export function isAbandoned(status?: string | null): boolean {
  return status === 'ABANDONED'
}

export function isCommitted(status?: string | null): boolean {
  return status === 'COMMITTED'
}

export function isFailed(status?: string | null): boolean {
  return status === 'FAILED'
}

/** The import is locked: parsing, partly parsed (retry or abandon first), or abandoned. */
export function isLocked(status?: string | null): boolean {
  return status != null && LOCKED_STATUSES.has(status)
}

export function isEditable(status?: string | null): boolean {
  return status != null && EDITABLE_STATUSES.has(status)
}

export function isRestorable(status?: string | null): boolean {
  return status != null && RESTORABLE_STATUSES.has(status)
}
