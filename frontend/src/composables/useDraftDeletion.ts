import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

/** Minimal shape shared by the scheduling list and the draft editor. */
export type DraftDeleteTarget = {
  id: number
  name: string
  lockedCount?: number | null
}

export type DraftDeleteOutcome = 'deleted' | 'cancelled' | 'failed'

export interface DraftDeleteDeps {
  client?: {
    delete(url: string, config?: { params?: Record<string, unknown> }): Promise<unknown>
  }
  confirm?: (
    message: string,
    title: string,
    options?: Record<string, unknown>,
  ) => Promise<unknown>
  notify?: { success(message: string): void; error(message: string): void }
}

/**
 * Delete a draft behind one confirmation, shared by the scheduling list and the
 * draft editor. A draft with locks asks for an explicit "unlock and delete": the
 * request then carries the count the user just saw, so the server can reject a
 * stale dialog with 409 instead of silently removing a different set of locks.
 */
export async function deleteDraftWithConfirmation(
  draft: DraftDeleteTarget,
  deps: DraftDeleteDeps = {},
): Promise<DraftDeleteOutcome> {
  const client = deps.client ?? http
  const confirm =
    deps.confirm ??
    ((message: string, title: string, options?: Record<string, unknown>) =>
      ElMessageBox.confirm(message, title, options as never))
  const notify = deps.notify ?? ElMessage

  const lockedCount = Math.max(0, draft.lockedCount ?? 0)
  const message =
    lockedCount > 0
      ? `该草稿「${draft.name}」含 ${lockedCount} 节锁定课节，删除会一并移除这些锁定。`
        + '删除后可在「最近删除」中恢复（7 天内）。确定解锁并删除？'
      : `确定删除草稿「${draft.name}」吗？删除后可在「最近删除」中恢复（7 天内）。`

  try {
    await confirm(message, '删除草稿', {
      confirmButtonText: lockedCount > 0 ? '解锁并删除' : '确定删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return 'cancelled'
  }

  try {
    await client.delete(`/timetable-drafts/${draft.id}`, {
      params: { unlockLocked: lockedCount > 0, expectedLockedCount: lockedCount },
    })
    notify.success('草稿已删除')
    return 'deleted'
  } catch (error) {
    notify.error(serverMessage(error, '删除失败'))
    return 'failed'
  }
}
