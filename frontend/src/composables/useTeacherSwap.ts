import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { useDraftAdjust } from './useDraftAdjust'
import type {
  DraftConflict,
  DraftLesson,
  SwapCandidate,
  SwapTargetState,
} from '@/components/draft/types'

export interface TeacherSwapNotify {
  warning(message: string): void
  error(message: string): void
}

export interface TeacherSwapOptions {
  adjust: ReturnType<typeof useDraftAdjust>
  /** Whole draft lesson list; the view narrows it to the teacher/class union for the grid. */
  getLessons: () => DraftLesson[]
  /** The teacher-view grid before swap mode widens it (the selected teacher's lessons). */
  getBaseLessons?: () => DraftLesson[]
  getViewMode?: () => 'class' | 'teacher'
  isEditable?: () => boolean
  getTargetId?: () => number | null
  /** Current draft version, read at confirm time so an old preview cannot overwrite others. */
  getVersion?: () => number | null
  /** Reloads the authoritative draft after a successful or refused confirm. */
  reload?: () => Promise<void> | void
  onConflicts?: (conflicts: DraftConflict[]) => void
  notify?: TeacherSwapNotify
}

export interface TeacherSwapOutcome {
  saved: boolean
  conflicts: DraftConflict[]
}

/**
 * State machine for the teacher-view same-class swap. It reuses the existing
 * `swap-candidates` query (`type=INTRA_CLASS`) and `POST /swap`; no new swap
 * algorithm and no cross-class request lives here.
 */
export function useTeacherSwap(options: TeacherSwapOptions) {
  const notify = options.notify ?? ElMessage
  const sourceLesson = ref<DraftLesson | null>(null)
  const candidates = ref<SwapCandidate[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const previewVisible = ref(false)
  const previewCandidate = ref<SwapCandidate | null>(null)
  const submitting = ref(false)

  let requestSeq = 0

  const active = computed(() => sourceLesson.value != null)

  const candidateById = computed(() => {
    const map = new Map<number, SwapCandidate>()
    candidates.value.forEach((candidate) => map.set(candidate.lessonId, candidate))
    return map
  })

  /** Every lesson of the source lesson's class; the view merges these into the grid. */
  const classLessons = computed(() => {
    const source = sourceLesson.value
    if (!source) return []
    return options.getLessons().filter((lesson) => lesson.classId === source.classId)
  })

  /** The teacher's own grid until swap mode starts, then teacher lessons ∪ source class. */
  const gridLessons = computed(() => {
    const base = options.getBaseLessons?.() ?? []
    if (!active.value) return base
    const seen = new Set(base.map((lesson) => lesson.id))
    return [...base, ...classLessons.value.filter((lesson) => !seen.has(lesson.id))]
  })

  function teacherViewAvailable() {
    return options.getViewMode?.() === 'teacher' && options.isEditable?.() !== false
  }

  function cellLessons(day: number, periodNo: number) {
    return gridLessons.value.filter(
      (lesson) => lesson.dayOfWeek === day && lesson.periodNo === periodNo,
    )
  }

  function onTeacherLesson(lesson: DraftLesson) {
    if (!active.value) {
      void enter(lesson)
      return
    }
    if (lesson.id === sourceLesson.value?.id) {
      exit()
      return
    }
    const state = targets.value[lesson.id]
    if (state?.status === 'swappable' && state.candidate) {
      openPreview(state.candidate)
    }
  }

  /** Returns false outside the editable teacher view so the caller keeps its old path. */
  function handleCellClick(day: number, periodNo: number): boolean {
    if (!teacherViewAvailable()) return false
    const lessons = cellLessons(day, periodNo)
    if (!active.value) {
      const own =
        lessons.find((lesson) => lesson.teacherId === options.getTargetId?.()) ?? lessons[0]
      if (own) onTeacherLesson(own)
      return true
    }
    const swappable = lessons.find(
      (lesson) => targets.value[lesson.id]?.status === 'swappable',
    )
    if (swappable) {
      onTeacherLesson(swappable)
      return true
    }
    if (lessons.some((lesson) => lesson.id === sourceLesson.value?.id)) exit()
    return true
  }

  function handleLessonActivate(lesson: DraftLesson): boolean {
    if (!teacherViewAvailable()) return false
    onTeacherLesson(lesson)
    return true
  }

  function swappableReason(candidate: SwapCandidate): string {
    const className = candidate.className ?? `班级${candidate.classId}`
    const courseName = candidate.courseName ?? '课程'
    const teacherName = candidate.teacherName ? `，${candidate.teacherName}` : ''
    return `和${className}的${courseName}互换${teacherName}`
  }

  const targets = computed<Record<number, SwapTargetState>>(() => {
    const source = sourceLesson.value
    if (!source) return {}
    const map: Record<number, SwapTargetState> = {}
    const byId = candidateById.value
    options.getLessons().forEach((lesson) => {
      if (lesson.id === source.id) {
        map[lesson.id] = { status: 'source', reason: '当前课节' }
        return
      }
      if (lesson.classId !== source.classId) {
        map[lesson.id] = { status: 'foreign', reason: '不同班，只能同班换课' }
        return
      }
      if (loading.value) {
        map[lesson.id] = { status: 'checking', reason: '正在检查可换情况' }
        return
      }
      if (failed.value) {
        map[lesson.id] = { status: 'blocked', reason: '候选加载失败，请重试' }
        return
      }
      const candidate = byId.get(lesson.id)
      if (!candidate) {
        map[lesson.id] = { status: 'blocked', reason: '没有可换的课' }
        return
      }
      if (candidate.available) {
        map[lesson.id] = {
          status: 'swappable',
          reason: swappableReason(candidate),
          candidate,
        }
        return
      }
      const reason = candidate.conflicts.map((conflict) => conflict.message).join('；')
      map[lesson.id] = {
        status: 'blocked',
        reason: reason || '违反必须规则，不能换课',
        candidate,
      }
    })
    return map
  })

  /** Loads the same-class candidates for one lesson. Returns false when it cannot start. */
  async function enter(lesson: DraftLesson): Promise<boolean> {
    if (lesson.periodId == null) {
      notify.warning('该课节尚未排入节次，无法换课')
      return false
    }
    if (lesson.locked) {
      notify.warning('该课节已锁定，先解锁后再换课')
      return false
    }
    const seq = ++requestSeq
    sourceLesson.value = lesson
    candidates.value = []
    failed.value = false
    loading.value = true
    previewVisible.value = false
    previewCandidate.value = null
    const response = await options.adjust.loadSwapCandidates(lesson.id, true, 'INTRA_CLASS')
    if (seq !== requestSeq) return false
    loading.value = false
    if (response == null) {
      failed.value = true
      return false
    }
    candidates.value = response.intraClass
    return true
  }

  async function toggle(lesson: DraftLesson): Promise<boolean> {
    if (sourceLesson.value?.id === lesson.id) {
      exit()
      return true
    }
    return enter(lesson)
  }

  function exit() {
    requestSeq += 1
    sourceLesson.value = null
    candidates.value = []
    loading.value = false
    failed.value = false
    previewVisible.value = false
    previewCandidate.value = null
  }

  /** A grey cell never opens the preview; only an available candidate does. */
  function openPreview(candidate: SwapCandidate): boolean {
    if (!candidate.available) return false
    previewCandidate.value = candidate
    previewVisible.value = true
    return true
  }

  async function confirm(): Promise<TeacherSwapOutcome> {
    const lesson = sourceLesson.value
    const candidate = previewCandidate.value
    if (!lesson || !candidate || submitting.value) return { saved: false, conflicts: [] }
    submitting.value = true
    try {
      const version = options.getVersion?.() ?? null
      const outcome = await options.adjust.swap(lesson, candidate, version ?? undefined)
      if (outcome.warnings.length > 0) {
        notify.warning(outcome.warnings.join('；'))
      }
      if (!outcome.saved) {
        if (outcome.conflicts.length > 0) options.onConflicts?.(outcome.conflicts)
        exit()
        await options.reload?.()
        return { saved: false, conflicts: outcome.conflicts }
      }
      await options.reload?.()
      exit()
      return { saved: true, conflicts: [] }
    } finally {
      submitting.value = false
    }
  }

  function closePreview() {
    previewVisible.value = false
    previewCandidate.value = null
  }

  return {
    sourceLesson,
    candidates,
    loading,
    failed,
    active,
    targets,
    classLessons,
    gridLessons,
    previewVisible,
    previewCandidate,
    submitting,
    enter,
    toggle,
    exit,
    openPreview,
    confirm,
    closePreview,
    handleCellClick,
    handleLessonActivate,
  }
}
