import { computed, nextTick, ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

/** The idle panel stays out of the page; a URL job or the menu brings it back. */
export function useOptimizationEntry(route: RouteLocationNormalizedLoaded) {
  const section = ref<{ openLaunch: () => void } | null>(null)
  const requested = ref(false)
  const visible = computed(() => requested.value || route.query.job != null)

  function open() {
    requested.value = true
    void nextTick(() => section.value?.openLaunch())
  }

  return { section, visible, open }
}
