import { unref, type Ref } from 'vue'
import type { useDraftAdjust } from './useDraftAdjust'
import type { useCyclePlans } from './useCyclePlans'
import type { DraftConflict, DraftDetail, DraftLesson } from '@/components/draft/types'

export interface DraftDropNotify {
  warning(message: string): void
  error(message: string): void
}

export interface DraftDropOptions {
  detail: Ref<DraftDetail | null>
  draftEditable: Ref<boolean>
  adjust: ReturnType<typeof useDraftAdjust>
  cyclePlans: ReturnType<typeof useCyclePlans>
  reload: () => Promise<void> | void
  notify: DraftDropNotify
  showConflicts: (conflicts: DraftConflict[]) => void
}

/**
 * The one place that decides what a drop means. The grid passes the ids of the
 * lessons the user can actually see in the target cell, so:
 * - one visible same-class lesson → swap with it;
 * - no visible lesson → move (other classes sharing that time are normal and hidden
 *   in class view);
 * - only visible foreign lessons → the user is targeting another class's lesson,
 *   which only the explicit cross-class entry may change.
 */
export function useDraftDrop(options: DraftDropOptions) {
  const { detail, draftEditable, adjust, cyclePlans, reload, notify, showConflicts } = options

  async function drop(source: DraftLesson, targetPeriodId: number, visibleLessonIds: number[] = []) {
    const snapshot = unref(detail)
    if (!snapshot || !draftEditable.value) return

    // Dropping back where it started is not an error and must not save anything.
    if (source.periodId === targetPeriodId) return

    if (source.locked) {
      notify.warning('该课节已锁定，请先解锁后再移动')
      return
    }

    const visibleIds = new Set(visibleLessonIds)
    const visibleCell = snapshot.lessons.filter((lesson) => visibleIds.has(lesson.id))
    const sameClass = visibleCell.filter((lesson) => lesson.classId === source.classId)
    if (sameClass.length > 1) {
      notify.warning('该格有多节课，请先修复后再拖拽')
      return
    }

    const version = snapshot.draft.version
    const fingerprint = snapshot.rulesFingerprint

    if (sameClass.length === 0) {
      // A visible foreign lesson means the user dropped onto another class's card;
      // that is a teacher exchange, which only the explicit entry may change.
      if (visibleCell.length > 0) {
        notify.warning('跨班落点只能通过换课入口调整教师')
        return
      }
      // Otherwise the cell is visually empty. Other classes sharing this time are
      // hidden in class view and normal; the server still checks teacher/room.
      const outcome = await adjust.moveTo(source, targetPeriodId, version, fingerprint)
      if (!outcome.saved && outcome.conflicts.length > 0) {
        showConflicts(outcome.conflicts)
      }
      await reload()
      return
    }

    const target = sameClass[0]
    if (target.locked) {
      notify.warning('落点课节已锁定，不能换位')
      return
    }

    const outcome = await adjust.swapLessons(source, target, version, fingerprint)
    if (outcome.saved) {
      await reload()
      return
    }

    if (outcome.addedConflicts.length === 0) {
      // Network, stale version or lock change: the server owns the truth now.
      if (outcome.conflicts.length > 0) showConflicts(outcome.conflicts)
      await reload()
      return
    }

    // The direct swap would add a conflict: search automatically. The user never
    // has to press a separate "find plans" button.
    const result = await cyclePlans.search(source.id, targetPeriodId, target.id, version, fingerprint)
    if (!result) {
      await reload()
      return
    }
    if (result.status === 'NONE') {
      notify.warning('当前范围和上限内没有可用方案')
      return
    }
    if (result.status === 'BUDGET_EXHAUSTED' && result.plans.length === 0) {
      notify.warning('搜索未完成，尚未找到方案；这不代表一定无解')
      return
    }
    if (result.status === 'BUDGET_EXHAUSTED') {
      notify.warning('搜索未完成，以下是已独立验证的部分方案')
    }
  }

  return { drop }
}
