import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { DraftConflict, DraftLesson, MoveTargetState } from '@/components/draft/types'

/** Minimal HTTP surface the composable needs, so tests can supply a fake. */
export interface DraftAdjustClient {
  get(url: string): Promise<{ data: any }>
  patch(url: string, body: unknown): Promise<{ data: any }>
}

export interface DraftAdjustNotify {
  success(message: string): void
  error(message: string): void
  warning(message: string): void
}

export interface DraftAdjustOptions {
  client?: DraftAdjustClient
  notify?: DraftAdjustNotify
  /** Undo depth for this session; the spec asks for 20. */
  limit?: number
}

export interface MoveOutcome {
  saved: boolean
  conflicts: DraftConflict[]
  error?: string
}

export interface UndoOutcome {
  undone: boolean
  conflicts?: DraftConflict[]
  error?: string
}

interface UndoEntry {
  lessonId: number
  from: { periodId: number; roomId: number | null }
}

type LessonPosition = Pick<DraftLesson, 'id' | 'periodId' | 'roomId'>

const DEFAULT_UNDO_LIMIT = 20

function serverMessage(error: unknown, fallback: string): string {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message ?? fallback
}

/**
 * Adjustment state for one draft editor session: the move write call, the
 * in-session undo stack and the shared error wording. A browser refresh drops
 * the stack, which the spec accepts as a known limit.
 */
export function useDraftAdjust(draftId: number, options: DraftAdjustOptions = {}) {
  const client = options.client ?? http
  const notify = options.notify ?? ElMessage
  const limit = options.limit ?? DEFAULT_UNDO_LIMIT

  const undoStack = ref<UndoEntry[]>([])
  const undoing = ref(false)
  const undoCount = computed(() => undoStack.value.length)
  const canUndo = computed(() => undoStack.value.length > 0)

  function push(entry: UndoEntry) {
    undoStack.value.push(entry)
    while (undoStack.value.length > limit) {
      undoStack.value.shift()
    }
  }

  function lessonUrl(lessonId: number) {
    return `/timetable-drafts/${draftId}/lessons/${lessonId}`
  }

  async function move(
    lesson: LessonPosition,
    target: { periodId: number; roomId?: number | null },
  ): Promise<MoveOutcome> {
    const roomId = target.roomId ?? lesson.roomId ?? null
    const fromPeriodId = lesson.periodId ?? null
    try {
      const { data } = await client.patch(lessonUrl(lesson.id), {
        periodId: target.periodId,
        roomId,
      })
      if (!data.saved) {
        return { saved: false, conflicts: data.conflicts ?? [] }
      }
      // A lesson dragged out of the unassigned drawer has no period to return
      // to, and a no-op move has nothing to reverse.
      const changed = fromPeriodId !== target.periodId || (lesson.roomId ?? null) !== roomId
      if (fromPeriodId != null && changed) {
        push({
          lessonId: lesson.id,
          from: { periodId: fromPeriodId, roomId: lesson.roomId ?? null },
        })
      }
      notify.success('已移动课节')
      return { saved: true, conflicts: [] }
    } catch (error) {
      const message = serverMessage(error, '移动失败')
      notify.error(message)
      return { saved: false, conflicts: [], error: message }
    }
  }

  async function undo(): Promise<UndoOutcome> {
    const entry = undoStack.value[undoStack.value.length - 1]
    if (!entry) {
      notify.warning('没有可撤销的操作')
      return { undone: false }
    }
    if (undoing.value) {
      return { undone: false }
    }
    undoing.value = true
    try {
      const { data } = await client.patch(lessonUrl(entry.lessonId), {
        periodId: entry.from.periodId,
        roomId: entry.from.roomId,
      })
      if (!data.saved) {
        notify.error('撤销失败：课表可能已被其他调整修改，请刷新后重试')
        return { undone: false, conflicts: data.conflicts ?? [] }
      }
      undoStack.value.pop()
      notify.success('已撤销上一步调整')
      return { undone: true }
    } catch (error) {
      const message = serverMessage(error, '撤销失败，请稍后重试')
      notify.error(message)
      return { undone: false, error: message }
    } finally {
      undoing.value = false
    }
  }

  /** One request covers every period, replacing one validate-move per period. */
  async function loadMoveTargets(lessonId: number): Promise<Record<number, MoveTargetState>> {
    try {
      const { data } = await client.get(`/timetable-drafts/${draftId}/lessons/${lessonId}/move-targets`)
      const targets: Record<number, MoveTargetState> = {}
      for (const target of data.targets ?? []) {
        targets[target.periodId] = {
          available: target.available,
          conflicts: target.conflicts ?? [],
        }
      }
      return targets
    } catch (error) {
      notify.error(serverMessage(error, '获取可放置节次失败'))
      return {}
    }
  }

  function clear() {
    undoStack.value = []
  }

  return { undoStack, undoCount, canUndo, undoing, move, undo, loadMoveTargets, clear }
}
