import { onBeforeUnmount, ref, watch } from 'vue'
import { http } from '@/api/http'
import type { ConstraintConfigRequest } from './types'

/** How long the page waits before asking, so typing a slot does not fire a request. */
const DEBOUNCE_MS = 350

/**
 * The capacity dry run shown above the save bar. The request state (debounce, the
 * newest-answer-wins generation, unmount cleanup, the warnings and the "cannot check
 * right now" message) lives here, so the view only renders what this composable says.
 *
 * <p>Reading an incomplete configuration is skipped on purpose: an unsaved card would
 * report a shortage that has nothing to do with the rules in force. Not asking is the
 * answer, not an error.
 */
export function useConstraintCapacity(props: {
  semesterId: number | null
  configs: ConstraintConfigRequest[]
  incomplete: boolean
}) {
  const warnings = ref<string[]>([])
  const error = ref('')
  let timer: ReturnType<typeof setTimeout> | undefined
  // Only the newest request may write: a late answer from an earlier semester or an
  // earlier configuration must be discarded instead of overwriting the current one.
  let generation = 0

  watch(
    () => [props.semesterId, JSON.stringify(props.configs), props.incomplete] as const,
    () => {
      const current = ++generation
      clearTimeout(timer)
      warnings.value = []
      error.value = ''
      if (!props.semesterId || props.incomplete) return
      timer = setTimeout(async () => {
        try {
          const { data } = await http.post<{ warnings?: string[] }>('/constraints/preview',
            { configs: props.configs }, { params: { semesterId: props.semesterId } })
          if (current === generation) warnings.value = data.warnings ?? []
        } catch {
          if (current === generation) {
            error.value = '暂时无法检查课时容量，请在开始排课时查看系统检查结果。'
          }
        }
      }, DEBOUNCE_MS)
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    ++generation
    clearTimeout(timer)
  })

  return { warnings, error }
}
