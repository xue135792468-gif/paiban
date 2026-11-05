import { computed, ref, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { serverMessage } from '@/api/errors'
import { setLocks as apiSetLocks } from '@/api/draft'
import type { DraftDetail, DraftLesson, LockResult } from '@/components/draft/types'

export type DraftLockScope = 'selected' | 'class' | 'day'
export type DraftLockViewMode = 'class' | 'teacher'

export interface LockBatchPayload {
  scope: DraftLockScope
  day?: number
  locked: boolean
}

export interface DraftLockNotify {
  success(message: string): void
  error(message: string): void
  warning(message: string): void
}

export type SetLocksFn = (
  draftId: number,
  body: { lessonIds: number[]; locked: boolean; expectedVersion: number },
) => Promise<LockResult>

export interface DraftLocksOptions {
  draftId: number
  detail: Ref<DraftDetail | null>
  draftEditable: Ref<boolean>
  viewMode: Ref<DraftLockViewMode>
  targetId: Ref<number | null>
  reload: () => Promise<void> | void
  notify?: DraftLockNotify
  /** Injected for tests; production uses `api/draft.setLocks`. */
  setLocks?: SetLocksFn
}

export interface LockOutcome {
  ok: boolean
  updated: number
  error?: string
}

const DAY_NAMES = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

function dayLabel(day: number) {
  return DAY_NAMES[day] ?? `周${day}`
}

function uniqueSorted(ids: number[]) {
  return [...new Set(ids)].sort((a, b) => a - b)
}

/**
 * Selection mode and batch lock/unlock for one draft editor session.
 *
 * The scope is always expanded on the client into explicit lesson ids, and the
 * server still validates ownership and position; a mixed batch is refused as a
 * whole, so this composable never reports "partially locked".
 */
export function useDraftLocks(options: DraftLocksOptions) {
  const notify = options.notify ?? ElMessage
  const setLocksApi = options.setLocks ?? apiSetLocks

  const selectionMode = ref(false)
  const selectedIds = ref<number[]>([])
  const busy = ref(false)

  const lessons = computed(() => options.detail.value?.lessons ?? [])
  const lessonById = computed(() => new Map(lessons.value.map((lesson) => [lesson.id, lesson])))
  const selectedIdSet = computed(() => new Set(selectedIds.value))
  const selectedLessons = computed(() =>
    lessons.value.filter((lesson) => selectedIdSet.value.has(lesson.id)),
  )
  const selectedCount = computed(() => selectedIds.value.length)

  /** Only the class view knows a single administrative class; the teacher view does not. */
  const currentClassId = computed(() =>
    options.viewMode.value === 'class' ? options.targetId.value : null,
  )
  const canUseClassScope = computed(() => currentClassId.value != null)

  const lockedLessonIds = computed(() =>
    lessons.value.filter((lesson) => lesson.locked).map((lesson) => lesson.id),
  )

  const visibleAssignedLessons = computed(() => {
    const target = options.targetId.value
    return lessons.value.filter((lesson) => {
      if (lesson.periodId == null) return false
      if (target == null) return true
      return options.viewMode.value === 'class'
        ? lesson.classId === target
        : lesson.teacherId === target
    })
  })

  const classAssignedLessons = computed(() => {
    const classId = currentClassId.value
    if (classId == null) return []
    return lessons.value.filter((lesson) => lesson.classId === classId && lesson.periodId != null)
  })

  const classScopeCount = computed(() => classAssignedLessons.value.length)

  const dayOptions = computed(() => {
    const counts = new Map<number, number>()
    classAssignedLessons.value.forEach((lesson) => {
      if (lesson.dayOfWeek == null) return
      counts.set(lesson.dayOfWeek, (counts.get(lesson.dayOfWeek) ?? 0) + 1)
    })
    return [...counts.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([day, count]) => ({ day, label: dayLabel(day), count }))
  })

  function toggleSelectionMode() {
    selectionMode.value = !selectionMode.value
    selectedIds.value = []
  }

  function toggle(lessonId: number) {
    if (!selectionMode.value) return
    const next = new Set(selectedIds.value)
    if (next.has(lessonId)) next.delete(lessonId)
    else next.add(lessonId)
    selectedIds.value = [...next]
  }

  function clearSelection() {
    selectedIds.value = []
  }

  /** Selects every scheduled lesson the current view renders; unassigned stays out. */
  function selectAllVisible() {
    if (!selectionMode.value) return
    selectedIds.value = visibleAssignedLessons.value.map((lesson) => lesson.id)
  }

  function canUseScope(scope: DraftLockScope) {
    return scope === 'selected' || canUseClassScope.value
  }

  /** Expands a UI range into concrete lesson ids; every rejection carries user wording. */
  function scope(scopeType: DraftLockScope, day?: number): number[] {
    if (scopeType === 'selected') {
      const ids = uniqueSorted(selectedIds.value)
      if (ids.length === 0) throw new Error('请先选择要锁定的课节')
      const hasUnassigned = ids.some((id) => {
        const lesson = lessonById.value.get(id)
        return !lesson || lesson.periodId == null
      })
      if (hasUnassigned) throw new Error('所选包含未排课节，不能锁定')
      return ids
    }

    if (!canUseClassScope.value) {
      throw new Error('教师视角只能按所选锁定，请切换到「按行政班」')
    }
    const classId = currentClassId.value
    if (classId == null) throw new Error('请先选择行政班')

    const ids = uniqueSorted(
      classAssignedLessons.value
        .filter((lesson) => scopeType === 'class' || lesson.dayOfWeek === day)
        .map((lesson) => lesson.id),
    )
    if (ids.length === 0) {
      throw new Error(scopeType === 'day' ? '当天没有已排课的课节' : '该班没有已排课的课节')
    }
    return ids
  }

  function classNamesFor(ids: number[]) {
    const names = new Set<string>()
    ids.forEach((id) => {
      const lesson = lessonById.value.get(id)
      if (!lesson) return
      names.add(lesson.className ?? `行政班${lesson.classId}`)
    })
    const list = [...names]
    if (list.length === 0) return '当前范围'
    if (list.length <= 2) return list.join('、')
    return `${list.length} 个班级`
  }

  /**
   * One atomic batch. A 409/400 reloads the authoritative grid but keeps the
   * selection so the user can retry; only a success clears it.
   */
  async function setLocks(lessonIds: number[], locked: boolean): Promise<LockOutcome> {
    if (!options.draftEditable.value) {
      notify.warning('当前草稿不可编辑')
      return { ok: false, updated: 0 }
    }
    const ids = uniqueSorted(lessonIds)
    if (ids.length === 0) {
      notify.warning('请选择要锁定的课节')
      return { ok: false, updated: 0 }
    }
    const hasUnassigned = ids.some((id) => {
      const lesson = lessonById.value.get(id)
      return !lesson || lesson.periodId == null
    })
    // Locking needs a real position; unlocking an unassigned lesson is a harmless
    // no-op the server accepts, so the client must not invent a stricter rule.
    if (locked && hasUnassigned) {
      notify.warning('所选包含未排课节，不能锁定')
      return { ok: false, updated: 0 }
    }
    const snapshot = options.detail.value
    if (!snapshot) return { ok: false, updated: 0 }
    if (busy.value) return { ok: false, updated: 0 }

    busy.value = true
    try {
      const result = await setLocksApi(options.draftId, {
        lessonIds: ids,
        locked,
        expectedVersion: snapshot.draft.version,
      })
      await options.reload()
      clearSelection()
      if (result.updated === 0) {
        notify.success('这些课节已经是目标状态')
      } else {
        notify.success(`${locked ? '已锁定' : '已解锁'} ${result.updated} 节`)
      }
      return { ok: true, updated: result.updated }
    } catch (error) {
      const message = serverMessage(error, locked ? '锁定失败' : '解锁失败')
      notify.error(message)
      // The server refused the whole batch; read the truth back, never patch locally.
      await options.reload()
      return { ok: false, updated: 0, error: message }
    } finally {
      busy.value = false
    }
  }

  function lockSingle(lesson: Pick<DraftLesson, 'id'>) {
    return setLocks([lesson.id], true)
  }

  function unlockSingle(lesson: Pick<DraftLesson, 'id'>) {
    return setLocks([lesson.id], false)
  }

  function toggleLockSingle(lesson: DraftLesson) {
    return lesson.locked ? unlockSingle(lesson) : lockSingle(lesson)
  }

  /**
   * Confirms a range command before writing. The wording always names the class
   * scope, the range and the real scheduled count, and the action (lock/unlock).
   */
  async function confirmBatch(payload: LockBatchPayload) {
    let ids: number[]
    try {
      ids = scope(payload.scope, payload.day)
    } catch (error) {
      notify.warning((error as Error).message)
      return
    }
    const action = payload.locked ? '锁定' : '解锁'
    const range =
      payload.scope === 'day'
        ? dayLabel(payload.day ?? 0)
        : payload.scope === 'class'
          ? '全班'
          : '所选课节'
    const classes = classNamesFor(ids)
    try {
      await ElMessageBox.confirm(
        `${classes} ${range} 共 ${ids.length} 节已排课，确定${action}？`,
        `${action}课节`,
        { type: 'warning' },
      )
    } catch {
      return
    }
    await setLocks(ids, payload.locked)
  }

  return {
    selectionMode,
    selectedIds,
    selectedLessons,
    selectedCount,
    busy,
    currentClassId,
    canUseClassScope,
    lockedLessonIds,
    classScopeCount,
    dayOptions,
    canUseScope,
    scope,
    toggleSelectionMode,
    toggle,
    clearSelection,
    selectAllVisible,
    setLocks,
    lockSingle,
    unlockSingle,
    toggleLockSingle,
    confirmBatch,
  }
}
