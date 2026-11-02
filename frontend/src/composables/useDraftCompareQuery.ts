import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

/**
 * The "compare with the source draft" entry (`/admin/drafts/{new}?compare={old}`).
 * It only decides whether the dialog opens and which side is A; the dialog itself
 * owns loading and the diff (ticket 08).
 */
export function useDraftCompareQuery() {
  const route = useRoute()
  const compareVisible = ref(false)

  const compareSourceId = computed(() => {
    const raw = Number(route.query.compare)
    return Number.isFinite(raw) && raw > 0 ? raw : null
  })

  watch(
    () => route.query.compare,
    (value) => {
      if (value != null && compareSourceId.value != null) compareVisible.value = true
    },
    { immediate: true },
  )

  function openCompare() {
    compareVisible.value = true
  }

  return { compareVisible, compareSourceId, openCompare }
}
