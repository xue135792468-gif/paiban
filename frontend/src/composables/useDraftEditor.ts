import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import { getDraft, getValidation, listCourses } from '@/api/draft'
import { canPublishNow } from '@/components/draft/publishBlockers'
import { blockedPeriodsForClass } from '@/components/draft/availability'
import type {
  DraftCourse,
  DraftDetail,
  DraftLesson,
  DraftValidation,
} from '@/components/draft/types'

export type DraftViewMode = 'class' | 'teacher' | 'room'

export interface DraftEditorOptions {
  draftId: number
  canEdit: Ref<boolean>
  /** Called after every successful load, for example to clear a stale selection. */
  onLoaded?: () => void
}

/**
 * Loading and derived state for one draft editor. The view keeps only layout and
 * the entry points; keeping this here is what lets the SFC stay under 600 lines.
 */
export function useDraftEditor(options: DraftEditorOptions) {
  const draftId = options.draftId
  const loading = ref(false)
  const detail = ref<DraftDetail | null>(null)
  const validation = ref<DraftValidation | null>(null)
  const courses = ref<DraftCourse[]>([])
  const viewMode = ref<DraftViewMode>('class')
  const targetId = ref<number | null>(null)

  const draftEditable = computed(
    () => options.canEdit.value && detail.value?.draft.status === 'DRAFT',
  )

  const blockedPeriods = computed(() =>
    viewMode.value === 'class'
      ? blockedPeriodsForClass(detail.value?.blockedSlots ?? [], targetId.value)
      : {},
  )

  const filteredLessons = computed(() => {
    const lessons = detail.value?.lessons ?? []
    if (targetId.value == null) return lessons
    return lessons.filter((lesson) => {
      if (viewMode.value === 'class') return lesson.classId === targetId.value
      if (viewMode.value === 'teacher') return lesson.teacherId === targetId.value
      return lesson.roomId === targetId.value
    })
  })

  const unassigned = computed(() => filteredLessons.value.filter((lesson) => !lesson.periodId))

  const hardConflicts = computed(() => validation.value?.hardConflicts ?? 0)
  // The server's whole-draft judgement is the only opinion on whether publishing can work.
  const blockers = computed(() => validation.value?.blockers ?? [])
  // The button follows the blockers themselves; an unfinished check keeps it disabled.
  const publishable = computed(() => canPublishNow(validation.value))
  const scoreLabel = computed(
    () => `${detail.value?.draft.scoreHard ?? 0} hard / ${detail.value?.draft.scoreSoft ?? 0} soft`,
  )

  const targetOptions = computed(() => {
    if (viewMode.value === 'room') {
      return (detail.value?.rooms ?? []).map((room) => ({ id: room.id, name: room.name }))
    }
    const map = new Map<number, string>()
    detail.value?.lessons.forEach((lesson) => {
      if (viewMode.value === 'class') {
        map.set(lesson.classId, lesson.className ?? `行政班${lesson.classId}`)
      } else {
        map.set(lesson.teacherId, lesson.teacherName ?? `教师${lesson.teacherId}`)
      }
    })
    return [...map.entries()]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name))
  })

  const draftVersion = computed(() => detail.value?.draft.version ?? 0)
  const rulesFingerprint = computed(() => detail.value?.rulesFingerprint ?? '')

  function resetTarget() {
    const options = targetOptions.value
    targetId.value = options.length > 0 ? options[0].id : null
  }

  async function load() {
    loading.value = true
    try {
      const [detailRes, validationRes, courseRes] = await Promise.all([
        getDraft(draftId),
        getValidation(draftId),
        listCourses(),
      ])
      detail.value = detailRes
      validation.value = validationRes
      courses.value = courseRes
      if (targetId.value == null) resetTarget()
      options.onLoaded?.()
    } catch (error) {
      ElMessage.error(serverMessage(error, '加载草稿失败'))
    } finally {
      loading.value = false
    }
  }

  function lessonAt(day: number, periodNo: number): DraftLesson | undefined {
    return filteredLessons.value.find(
      (item) => item.dayOfWeek === day && item.periodNo === periodNo,
    )
  }

  return {
    loading,
    detail,
    validation,
    courses,
    viewMode,
    targetId,
    draftEditable,
    blockedPeriods,
    filteredLessons,
    unassigned,
    hardConflicts,
    blockers,
    publishable,
    scoreLabel,
    targetOptions,
    draftVersion,
    rulesFingerprint,
    resetTarget,
    load,
    lessonAt,
  }
}
