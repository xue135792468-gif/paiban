import { ref } from 'vue'
import type { useDraftAdjust } from './useDraftAdjust'
import type { CyclePlan, DraftLesson } from '@/components/draft/types'

export interface CyclePlansOptions {
  adjust: ReturnType<typeof useDraftAdjust>
}

export type CyclePlanStatus = 'FOUND' | 'NONE' | 'BUDGET_EXHAUSTED'

/**
 * State for the automatic plan panel: the search result, which card is applying,
 * and the visible/message pair. Searching never changes the timetable; only an
 * explicit apply does. Wording for "no plan"/"budget exhausted" belongs to
 * `useDraftDrop`, which decides what the user sees after a failed direct swap.
 */
export function useCyclePlans(options: CyclePlansOptions) {
  const adjust = options.adjust

  const visible = ref(false)
  const loading = ref(false)
  const status = ref<CyclePlanStatus | null>(null)
  const message = ref('')
  const plans = ref<CyclePlan[]>([])
  const selectedToken = ref<string | null>(null)
  const applying = ref(false)

  function reset() {
    status.value = null
    message.value = ''
    plans.value = []
    selectedToken.value = null
  }

  async function search(
    sourceLessonId: number,
    targetPeriodId: number,
    targetLessonId: number,
    expectedVersion: number,
    rulesFingerprint: string,
  ) {
    loading.value = true
    reset()
    try {
      const response = await adjust.searchPlans(
        sourceLessonId,
        targetPeriodId,
        targetLessonId,
        expectedVersion,
        rulesFingerprint,
      )
      if (!response) {
        message.value = '搜索联动方案失败，请刷新后重试'
        return null
      }
      status.value = response.status
      message.value = response.message
      plans.value = response.plans ?? []
      const hasPlans = plans.value.length > 0
      if (response.status === 'FOUND' || (response.status === 'BUDGET_EXHAUSTED' && hasPlans)) {
        visible.value = true
      }
      return response
    } finally {
      loading.value = false
    }
  }

  function select(token: string) {
    selectedToken.value = token
  }

  async function apply(plan: CyclePlan, expectedVersion: number, lessons: DraftLesson[]) {
    if (applying.value) return { saved: false }
    applying.value = true
    selectedToken.value = plan.token
    try {
      const outcome = await adjust.applyPlan(plan, expectedVersion, lessons)
      if (outcome.saved) {
        close()
      }
      return outcome
    } finally {
      applying.value = false
    }
  }

  function close() {
    visible.value = false
    reset()
  }

  return {
    visible,
    loading,
    status,
    message,
    plans,
    selectedToken,
    applying,
    search,
    select,
    apply,
    close,
  }
}
