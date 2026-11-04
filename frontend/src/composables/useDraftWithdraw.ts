import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

export interface DraftWithdrawTarget {
  id: number
  semesterId?: number
}

export interface WithdrawPreview {
  published: boolean
  draftId?: number | null
  publishId?: number | null
  futureSubstitutions: number
  totalSubstitutions: number
}

export interface WithdrawResult {
  draftId: number
  publishId: number
  withdrawnAt: string
  cancelledSubstitutions: number
  removedEntries: number
}

export type DraftWithdrawOutcome = 'withdrawn' | 'cancelled' | 'failed'

export interface DraftWithdrawDeps {
  client?: {
    get(url: string, config?: {
      params?: Record<string, unknown>
    }): Promise<{ data: WithdrawPreview }>
    post(url: string): Promise<{ data: WithdrawResult }>
  }
  confirm?: (
    message: string,
    title: string,
    options?: Record<string, unknown>,
  ) => Promise<unknown>
  notify?: { success(message: string): void; error(message: string): void }
}

/**
 * Withdraws a published baseline back to a draft behind one explicit confirmation.
 *
 * <p>The dialog must name how many future substitutions will stop applying. Those
 * rows are cancelled, never deleted: the product keeps them as history. With N=0
 * the withdrawal is still allowed (a fresh baseline may have no substitutions).
 */
export async function withdrawDraftWithConfirmation(
  draft: DraftWithdrawTarget,
  deps: DraftWithdrawDeps = {},
): Promise<DraftWithdrawOutcome> {
  const client = deps.client ?? http
  const confirm =
    deps.confirm ??
    ((message: string, title: string, options?: Record<string, unknown>) =>
      ElMessageBox.confirm(message, title, options as never))
  const notify = deps.notify ?? ElMessage

  let preview: WithdrawPreview
  try {
    const { data } = await client.get('/timetable/withdraw-preview', {
      params: draft.semesterId == null ? undefined : { semesterId: draft.semesterId },
    })
    preview = data
  } catch (error) {
    notify.error(serverMessage(error, '无法读取撤回信息'))
    return 'failed'
  }
  if (!preview?.published) {
    notify.error('当前学期没有已发布课表可撤回')
    return 'failed'
  }

  const future = Math.max(0, preview.futureSubstitutions ?? 0)
  const consequence =
    future > 0
      ? `有 ${future} 条未来日期的调代会失效（记录保留在历史）；`
      : '当前没有未来日期的调代会失效；'
  try {
    await confirm(
      `${consequence}撤回后课表回到草稿，可编辑并重新发布。确定撤回？`,
      '撤回课表',
      { confirmButtonText: '撤回为草稿', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return 'cancelled'
  }

  try {
    const { data } = await client.post(`/timetable-drafts/${draft.id}/withdraw`)
    notify.success(
      data.cancelledSubstitutions > 0
        ? `已撤回为草稿，${data.cancelledSubstitutions} 条未来调代已失效`
        : '已撤回为草稿',
    )
    return 'withdrawn'
  } catch (error) {
    notify.error(serverMessage(error, '撤回失败'))
    return 'failed'
  }
}
