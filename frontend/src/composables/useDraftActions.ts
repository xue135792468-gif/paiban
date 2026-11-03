import type { Ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { DraftDetail } from '@/components/draft/types'

export interface DraftActionsOptions {
  draftId: number
  detail: Ref<DraftDetail | null>
  reload: () => Promise<void> | void
}

/**
 * Rename / publish / delete for one draft page. Moved out of DraftEditorView so
 * ticket 08 can add the optimization entry without crossing the 600-line limit
 * (behaviour is unchanged: confirm dialogs, server messages, reload after write).
 */
export function useDraftActions(options: DraftActionsOptions) {
  const router = useRouter()

  async function rename() {
    if (!options.detail.value) return
    let value: string
    try {
      const result = await ElMessageBox.prompt('输入新的草稿名称', '重命名草稿', {
        inputValue: options.detail.value.draft.name,
        inputValidator: (input) => Boolean(input && input.trim()),
      })
      value = result.value
    } catch {
      return
    }
    try {
      await http.put(`/timetable-drafts/${options.draftId}/name`, { name: value })
      ElMessage.success('已重命名')
      await options.reload()
    } catch (error) {
      ElMessage.error(serverMessage(error, '重命名失败'))
    }
  }

  async function publish() {
    if (!options.detail.value) return
    try {
      await ElMessageBox.confirm(
        '发布后该草稿将成为正式课表基线，后续变更需走调代流程。确定发布？',
        '发布课表',
        { type: 'warning' },
      )
    } catch {
      return
    }
    try {
      const { data } = await http.post(`/timetable-drafts/${options.draftId}/publish`)
      ElMessage.success(`课表已发布，共 ${data.entryCount} 节课`)
      await options.reload()
    } catch (error) {
      ElMessage.error(serverMessage(error, '发布失败'))
    }
  }

  async function remove() {
    const lockedCount = (options.detail.value?.lessons ?? []).filter((lesson) => lesson.locked).length
    const message =
      lockedCount > 0
        ? `该草稿含 ${lockedCount} 节锁定课节，删除会一并移除这些锁定，且无法恢复。确定解锁并删除？`
        : '确定删除该草稿吗？删除后无法恢复。'
    try {
      await ElMessageBox.confirm(message, '删除草稿', { type: 'warning' })
    } catch {
      return
    }
    try {
      await http.delete(`/timetable-drafts/${options.draftId}`, {
        params: { unlockLocked: lockedCount > 0, expectedLockedCount: lockedCount },
      })
      ElMessage.success('已删除')
      router.push('/admin/scheduling')
    } catch (error) {
      ElMessage.error(serverMessage(error, '删除失败'))
      // A 409 means locks changed since the dialog was opened; refresh so the
      // next attempt shows the current count and can be confirmed again. The
      // original error is already surfaced, so a reload failure must not escape.
      await Promise.resolve(options.reload()).catch(() => undefined)
    }
  }

  return { rename, publish, remove }
}
