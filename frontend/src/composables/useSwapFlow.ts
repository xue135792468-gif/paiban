import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { useDraftAdjust } from './useDraftAdjust'
import type { DraftConflict, DraftLesson, SwapCandidate, SwapCandidatesResponse } from '@/components/draft/types'

export type CandidateGroup = 'ALL' | 'INTRA_CLASS' | 'CROSS_CLASS'

export interface SwapFlowNotify {
  warning(message: string): void
  error(message: string): void
}

export interface SwapConfirmOutcome {
  saved: boolean
  conflicts: DraftConflict[]
}

export interface SwapFlowOptions {
  adjust: ReturnType<typeof useDraftAdjust>
  notify?: SwapFlowNotify
}

/**
 * Orchestration for the manual swap flow: which lesson is selected, the candidate
 * drawer, the dual-timetable preview and the confirm call. The editor view keeps
 * only the entry points, which is what keeps it under the 600-line threshold.
 */
export function useSwapFlow(options: SwapFlowOptions) {
  const adjust = options.adjust
  const notify = options.notify ?? ElMessage

  const selectedLesson = ref<DraftLesson | null>(null)
  const panelVisible = ref(false)
  const panelLesson = ref<DraftLesson | null>(null)
  const candidates = ref<SwapCandidatesResponse | null>(null)
  const candidatesLoading = ref(false)
  const includeUnavailable = ref(false)
  const initialGroup = ref<CandidateGroup>('ALL')
  const previewVisible = ref(false)
  const previewCandidate = ref<SwapCandidate | null>(null)
  const swapRooms = ref(false)
  const swapping = ref(false)

  let requestSeq = 0

  function selectLesson(lesson: DraftLesson | null) {
    selectedLesson.value = lesson
  }

  function clearSelection() {
    selectedLesson.value = null
  }

  async function loadCandidates() {
    const lesson = panelLesson.value
    if (!lesson) return
    const seq = ++requestSeq
    candidatesLoading.value = true
    const response = await adjust.loadSwapCandidates(lesson.id, includeUnavailable.value)
    if (seq !== requestSeq) return
    candidates.value = response
    candidatesLoading.value = false
  }

  function setIncludeUnavailable(value: boolean) {
    if (includeUnavailable.value === value) return
    includeUnavailable.value = value
    void loadCandidates()
  }

  /** Returns false when the lesson has no period yet, so the caller can keep the old behaviour. */
  function openPanel(lesson: DraftLesson, group: CandidateGroup = 'ALL') {
    if (lesson.periodId == null) {
      notify.warning('该课节尚未排入节次，无法换课')
      return false
    }
    panelLesson.value = lesson
    initialGroup.value = group
    includeUnavailable.value = false
    candidates.value = null
    previewCandidate.value = null
    previewVisible.value = false
    panelVisible.value = true
    void loadCandidates()
    return true
  }

  function openForSelected() {
    const lesson = selectedLesson.value
    return lesson ? openPanel(lesson) : false
  }

  function selectCandidate(candidate: SwapCandidate) {
    previewCandidate.value = candidate
    // A candidate that is only valid when both rooms move must pre-select the box,
    // otherwise confirming it would hit the conflict the server already found.
    swapRooms.value = candidate.roomSwapRequired === true
    previewVisible.value = true
  }

  function closePanel() {
    panelVisible.value = false
    panelLesson.value = null
    candidates.value = null
    previewVisible.value = false
    previewCandidate.value = null
  }

  function closePreview() {
    previewVisible.value = false
    previewCandidate.value = null
  }

  async function confirm(): Promise<SwapConfirmOutcome> {
    const lesson = panelLesson.value
    const candidate = previewCandidate.value
    if (!lesson || !candidate) return { saved: false, conflicts: [] }
    if (swapping.value) return { saved: false, conflicts: [] }
    swapping.value = true
    try {
      const outcome = await adjust.swap(lesson, candidate, swapRooms.value)
      if (outcome.warnings.length > 0) {
        notify.warning(outcome.warnings.join('；'))
      }
      if (!outcome.saved) {
        return { saved: false, conflicts: outcome.conflicts }
      }
      closePanel()
      clearSelection()
      return { saved: true, conflicts: [] }
    } finally {
      swapping.value = false
    }
  }

  return {
    selectedLesson,
    panelVisible,
    panelLesson,
    candidates,
    candidatesLoading,
    includeUnavailable,
    initialGroup,
    previewVisible,
    previewCandidate,
    swapRooms,
    swapping,
    selectLesson,
    clearSelection,
    openPanel,
    openForSelected,
    setIncludeUnavailable,
    selectCandidate,
    confirm,
    closePanel,
    closePreview,
  }
}
