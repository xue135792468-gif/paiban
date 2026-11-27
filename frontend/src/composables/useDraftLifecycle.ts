import { onBeforeUnmount, onMounted } from 'vue'

/** Escape leaves teacher-view swap mode; mount also loads the authoritative draft. */
export function useDraftLifecycle(isSwapping: () => boolean, exitSwap: () => void, load: () => unknown) {
  function onKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && isSwapping()) exitSwap()
  }
  onMounted(() => {
    window.addEventListener('keydown', onKeydown)
    void load()
  })
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}
