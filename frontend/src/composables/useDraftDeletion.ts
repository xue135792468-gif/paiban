import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

/** Minimal shape shared by the scheduling list and the draft editor. */
export type DraftDeleteTarget = {
  id: number
  name: string
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
 * draft editor. Locks travel with the draft: deleting it removes its `locked`
 * flags along with every lesson, so no separate unlock step is asked for.
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

  try {
    await confirm(
      `确定删除草稿「${draft.name}」吗？删除后可在「最近删除」中恢复（7 天内）。`,
      '删除草稿',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return 'cancelled'
  }

  try {
    await client.delete(`/timetable-drafts/${draft.id}`)
    notify.success('草稿已删除')
    return 'deleted'
  } catch (error) {
    notify.error(serverMessage(error, '删除失败'))
    return 'failed'
  }
}
