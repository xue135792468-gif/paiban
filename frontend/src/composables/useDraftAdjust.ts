import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type {
  CyclePlan,
  DraftChangeItem,
  DraftChangeResult,
  DraftConflict,
  DraftLesson,
  DraftViolation,
  MoveTargetState,
  SwapCandidatesResponse,
  SwapResult,
} from '@/components/draft/types'

/** Minimal HTTP surface the composable needs, so tests can supply a fake. */
export interface DraftAdjustClient {
  get(url: string, config?: { params?: Record<string, unknown> }): Promise<{ data: any }>
  patch(url: string, body: unknown): Promise<{ data: any }>
  post(url: string, body: unknown): Promise<{ data: any }>
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

export interface SwapOutcome {
  saved: boolean
  type?: SwapResult['type']
  conflicts: DraftConflict[]
  warnings: string[]
  error?: string
}

/** One atomic change-set result, with both the structured and the legacy shapes. */
export interface ChangeOutcome {
  saved: boolean
  version?: number
  addedConflicts: DraftViolation[]
  remainingConflicts: DraftViolation[]
  /** Legacy conflict list for the existing "冲突原因" dialog. */
  conflicts: DraftConflict[]
  error?: string
}

export interface UndoOutcome {
  undone: boolean
  conflicts?: DraftConflict[]
  error?: string
}

export interface UndoContext {
  expectedVersion?: number
  rulesFingerprint?: string
}

/** Moves are undone by moving back; a swap is its own inverse. */
export type UndoEntry =
  | { kind: 'move'; lessonId: number; from: { periodId: number | null; roomId: number | null } }
  | { kind: 'swap'; lessonId: number; targetLessonId: number; swapRooms: boolean }
  /** One atomic action; the items are already inverted for the undo call. */
  | { kind: 'changes'; items: DraftChangeItem[]; label: string }

type LessonPosition = Pick<DraftLesson, 'id' | 'periodId' | 'roomId'> & { teacherId?: number }

const DEFAULT_UNDO_LIMIT = 20

function violationsToConflicts(violations: DraftViolation[] | undefined): DraftConflict[] {
  return (violations ?? []).map((violation) => ({
    type: violation.code,
    message: violation.message,
    lessonId: violation.lessonId ?? 0,
  }))
}

/**
 * The reverse of one change item: what used to be the target becomes what we now
 * expect, and vice versa. A null `toTeacherId`/`toRoomId` means "keep", so the
 * inverse expects the same value the original item carried as its source.
 */
function inverseItem(item: DraftChangeItem): DraftChangeItem {
  return {
    lessonId: item.lessonId,
    toPeriodId: item.expectedPeriodId,
    toTeacherId: item.expectedTeacherId,
    toRoomId: item.expectedRoomId,
    expectedPeriodId: item.toPeriodId,
    expectedTeacherId: item.toTeacherId ?? item.expectedTeacherId,
    expectedRoomId: item.toRoomId ?? item.expectedRoomId,
  }
}

/**
 * Adjustment state for one draft editor session: the write calls, the in-session
 * undo stack and the shared error wording. A browser refresh drops the stack,
 * which the spec accepts as a known limit.
 */
export function useDraftAdjust(draftId: number, options: DraftAdjustOptions = {}) {
  const client = options.client ?? http
  const notify = options.notify ?? ElMessage
  const limit = options.limit ?? DEFAULT_UNDO_LIMIT

  const undoStack = ref<UndoEntry[]>([])
  const undoing = ref(false)
  const busy = ref(false)
  const lastVersion = ref<number | null>(null)
  const lastFingerprint = ref('')
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

  function swapUrl() {
    return `/timetable-drafts/${draftId}/swap`
  }

  function changesUrl() {
    return `/timetable-drafts/${draftId}/changes`
  }

  function remember(version?: number, fingerprint?: string) {
    if (version != null) lastVersion.value = version
    if (fingerprint != null) lastFingerprint.value = fingerprint
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
      // A lesson dragged out of the unassigned drawer has no period to return to;
      // undo sends periodId null, which the server reads as "back to unassigned".
      const changed = fromPeriodId !== target.periodId || (lesson.roomId ?? null) !== roomId
      if (changed) {
        push({
          kind: 'move',
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

  async function swap(
    lesson: Pick<DraftLesson, 'id'>,
    candidate: { lessonId: number },
    swapRooms = false,
  ): Promise<SwapOutcome> {
    try {
      const { data } = await client.post(swapUrl(), {
        lessonId: lesson.id,
        targetLessonId: candidate.lessonId,
        swapRooms,
      })
      const warnings: string[] = data.warnings ?? []
      if (!data.saved) {
        return { saved: false, type: data.type, conflicts: data.conflicts ?? [], warnings }
      }
      push({
        kind: 'swap',
        lessonId: lesson.id,
        targetLessonId: candidate.lessonId,
        swapRooms,
      })
      notify.success('已换课')
      return { saved: true, type: data.type, conflicts: [], warnings }
    } catch (error) {
      const message = serverMessage(error, '换课失败')
      notify.error(message)
      return { saved: false, conflicts: [], warnings: [], error: message }
    }
  }

  /**
   * Applies one atomic change set. Only a `saved=true` answer pushes an undo
   * entry; a 200 + saved=false refusal and a 409 both leave the stack untouched.
   */
  async function applyChangeSet(
    items: DraftChangeItem[],
    expectedVersion: number,
    rulesFingerprint: string,
    label: string,
  ): Promise<ChangeOutcome> {
    if (items.length === 0) {
      return { saved: false, addedConflicts: [], remainingConflicts: [], conflicts: [] }
    }
    if (busy.value) {
      return { saved: false, addedConflicts: [], remainingConflicts: [], conflicts: [] }
    }
    busy.value = true
    try {
      const { data } = (await client.post(changesUrl(), {
        changes: items,
        expectedVersion,
        rulesFingerprint,
        verifySource: true,
      })) as { data: DraftChangeResult }
      remember(data.version, rulesFingerprint)
      if (!data.saved) {
        const added = data.addedConflicts ?? []
        return {
          saved: false,
          version: data.version,
          addedConflicts: added,
          remainingConflicts: data.remainingConflicts ?? [],
          conflicts: violationsToConflicts(added.length ? added : data.conflicts),
        }
      }
      push({ kind: 'changes', items: items.map(inverseItem), label })
      notify.success(label || '已保存调整')
      return {
        saved: true,
        version: data.version,
        addedConflicts: [],
        remainingConflicts: data.remainingConflicts ?? [],
        conflicts: [],
      }
    } catch (error) {
      const message = serverMessage(error, '调整失败，请刷新后重试')
      notify.error(message)
      return {
        saved: false,
        addedConflicts: [],
        remainingConflicts: [],
        conflicts: [],
        error: message,
      }
    } finally {
      busy.value = false
    }
  }

  /** Moves one lesson to an empty period; teacher and room stay with the lesson. */
  async function moveTo(
    lesson: LessonPosition,
    targetPeriodId: number,
    expectedVersion: number,
    rulesFingerprint: string,
  ): Promise<ChangeOutcome> {
    const item: DraftChangeItem = {
      lessonId: lesson.id,
      toPeriodId: targetPeriodId,
      toTeacherId: null,
      toRoomId: null,
      expectedPeriodId: lesson.periodId ?? null,
      expectedTeacherId: lesson.teacherId ?? 0,
      expectedRoomId: lesson.roomId ?? null,
    }
    return applyChangeSet([item], expectedVersion, rulesFingerprint, '已移动课节')
  }

  /** Exchanges the periods of two same-class lessons; teacher and room never move. */
  async function swapLessons(
    source: LessonPosition,
    target: LessonPosition,
    expectedVersion: number,
    rulesFingerprint: string,
  ): Promise<ChangeOutcome> {
    const sourceItem: DraftChangeItem = {
      lessonId: source.id,
      toPeriodId: target.periodId ?? null,
      toTeacherId: null,
      toRoomId: null,
      expectedPeriodId: source.periodId ?? null,
      expectedTeacherId: source.teacherId ?? 0,
      expectedRoomId: source.roomId ?? null,
    }
    const targetItem: DraftChangeItem = {
      lessonId: target.id,
      toPeriodId: source.periodId ?? null,
      toTeacherId: null,
      toRoomId: null,
      expectedPeriodId: target.periodId ?? null,
      expectedTeacherId: target.teacherId ?? 0,
      expectedRoomId: target.roomId ?? null,
    }
    return applyChangeSet([sourceItem, targetItem], expectedVersion, rulesFingerprint, '已交换两节课')
  }

  /** Searches up to three independently verified same-class plans. */
  async function searchPlans(
    sourceLessonId: number,
    targetPeriodId: number,
    targetLessonId: number,
    expectedVersion: number,
    rulesFingerprint: string,
  ) {
    try {
      const { data } = await client.post(`/timetable-drafts/${draftId}/cycle-plans/search`, {
        sourceLessonId,
        targetPeriodId,
        targetLessonId,
        expectedVersion,
        rulesFingerprint,
      })
      return data as import('@/components/draft/types').CyclePlanSearch
    } catch (error) {
      notify.error(serverMessage(error, '搜索联动方案失败，请刷新后重试'))
      return null
    }
  }

  /**
   * Applies a searched plan as one atomic action. The undo entry reverses every
   * move from the plan, so a whole cycle is restored in one click.
   */
  async function applyPlan(
    plan: CyclePlan,
    expectedVersion: number,
    lessons: DraftLesson[],
  ): Promise<ChangeOutcome> {
    if (busy.value) {
      return { saved: false, addedConflicts: [], remainingConflicts: [], conflicts: [] }
    }
    busy.value = true
    try {
      const byId = new Map(lessons.map((lesson) => [lesson.id, lesson]))
      const inverse: DraftChangeItem[] = plan.moves.map((move) => {
        const lesson = byId.get(move.lessonId)
        return {
          lessonId: move.lessonId,
          toPeriodId: move.fromPeriodId,
          toTeacherId: lesson?.teacherId ?? move.teacherId,
          toRoomId: lesson?.roomId ?? null,
          expectedPeriodId: move.toPeriodId,
          expectedTeacherId: lesson?.teacherId ?? move.teacherId,
          expectedRoomId: lesson?.roomId ?? null,
        }
      })
      const { data } = (await client.post(
        `/timetable-drafts/${draftId}/cycle-plans/apply`,
        { token: plan.token, expectedVersion },
      )) as { data: DraftChangeResult }
      remember(data.version)
      if (!data.saved) {
        const added = data.addedConflicts ?? []
        return {
          saved: false,
          version: data.version,
          addedConflicts: added,
          remainingConflicts: data.remainingConflicts ?? [],
          conflicts: violationsToConflicts(added.length ? added : data.conflicts),
        }
      }
      push({ kind: 'changes', items: inverse, label: '联动方案' })
      notify.success('联动方案已应用')
      return {
        saved: true,
        version: data.version,
        addedConflicts: [],
        remainingConflicts: data.remainingConflicts ?? [],
        conflicts: [],
      }
    } catch (error) {
      const message = serverMessage(error, '应用联动方案失败，请刷新后重试')
      notify.error(message)
      return {
        saved: false,
        addedConflicts: [],
        remainingConflicts: [],
        conflicts: [],
        error: message,
      }
    } finally {
      busy.value = false
    }
  }

  async function undo(context: UndoContext = {}): Promise<UndoOutcome> {
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
      if (entry.kind === 'changes') {
        const expectedVersion = context.expectedVersion ?? lastVersion.value
        const rulesFingerprint = context.rulesFingerprint ?? lastFingerprint.value
        if (expectedVersion == null) {
          notify.error('撤销失败，请刷新后重试')
          return { undone: false }
        }
        const { data } = (await client.post(changesUrl(), {
          changes: entry.items,
          expectedVersion,
          rulesFingerprint,
          verifySource: true,
        })) as { data: DraftChangeResult }
        if (!data.saved) {
          const conflicts = violationsToConflicts(data.addedConflicts ?? data.conflicts)
          notify.error('撤销失败：课表可能已被其他调整修改，请刷新后重试')
          return { undone: false, conflicts }
        }
        remember(data.version)
      } else if (entry.kind === 'move') {
        const { data } = await client.patch(lessonUrl(entry.lessonId), {
          periodId: entry.from.periodId,
          roomId: entry.from.roomId,
        })
        if (!data.saved) {
          notify.error('撤销失败：课表可能已被其他调整修改，请刷新后重试')
          return { undone: false, conflicts: data.conflicts ?? [] }
        }
        remember(data.version)
      } else {
        // Swapping the same pair again restores both lessons.
        const { data } = await client.post(swapUrl(), {
          lessonId: entry.lessonId,
          targetLessonId: entry.targetLessonId,
          swapRooms: entry.swapRooms,
        })
        if (!data.saved) {
          notify.error('撤销失败：课表可能已被其他调整修改，请刷新后重试')
          return { undone: false, conflicts: data.conflicts ?? [] }
        }
        remember(data.version)
      }
      undoStack.value.pop()
      notify.success('已撤销上一步调整')
      return { undone: true }
    } catch (error) {
      const message = serverMessage(error, '撤销失败，请先解锁或刷新后重试')
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

  async function loadSwapCandidates(
    lessonId: number,
    includeUnavailable = false,
  ): Promise<SwapCandidatesResponse | null> {
    try {
      const { data } = await client.get(
        `/timetable-drafts/${draftId}/lessons/${lessonId}/swap-candidates`,
        { params: { type: 'ALL', includeUnavailable } },
      )
      return {
        lessonId: data.lessonId,
        intraClass: data.intraClass ?? [],
        crossClass: data.crossClass ?? [],
      }
    } catch (error) {
      notify.error(serverMessage(error, '获取换课候选失败'))
      return null
    }
  }

  function clear() {
    undoStack.value = []
  }

  return {
    undoStack,
    undoCount,
    canUndo,
    undoing,
    busy: busy as Ref<boolean>,
    move,
    swap,
    applyChangeSet,
    moveTo,
    swapLessons,
    searchPlans,
    applyPlan,
    undo,
    loadMoveTargets,
    loadSwapCandidates,
    clear,
  }
}
