import { ref, type Ref } from 'vue'
import { serverMessage } from '@/api/errors'
import { fetchRuleImpact, type RuleImpact } from '@/api/rules'
import type { ConstraintConfigRequest } from '@/components/constraints/types'

/**
 * The rule page's impact panel: one place that owns "loading / answer / error"
 * for a card preview, the save-time check and the delete confirmation.
 */
export function useRuleImpact(semesterId: Ref<number | null>) {
  const visible = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const impact = ref<RuleImpact | null>(null)
  const title = ref('这条规则的影响')

  /** Loads and shows the answer; returns null when the request failed. */
  async function preview(
    configs: ConstraintConfigRequest[],
    heading: string,
  ): Promise<RuleImpact | null> {
    if (semesterId.value === null) {
      return null
    }
    visible.value = true
    loading.value = true
    error.value = null
    impact.value = null
    title.value = heading
    try {
      impact.value = await fetchRuleImpact(semesterId.value, configs)
      return impact.value
    } catch (e: unknown) {
      error.value = serverMessage(e, '无法获取影响预览')
      return null
    } finally {
      loading.value = false
    }
  }

  function show(result: RuleImpact, heading: string) {
    visible.value = true
    loading.value = false
    error.value = null
    impact.value = result
    title.value = heading
  }

  function hide() {
    visible.value = false
  }

  return { visible, loading, error, impact, title, preview, show, hide }
}
