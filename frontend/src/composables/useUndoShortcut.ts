import { onBeforeUnmount, onMounted, type Ref } from 'vue'

export interface UndoShortcutOptions {
  canUndo: Ref<boolean>
  busy: Ref<boolean>
  undo: () => void
}

/**
 * `Ctrl/Cmd+Z` and `beforeunload` warning for the draft editor. Kept out of the
 * view so the SFC stays under the 600-line limit; the guardrails (no undo inside
 * inputs, never while busy, warn on unload only when undo history exists) stay here.
 */
export function useUndoShortcut(options: UndoShortcutOptions) {
  function onKeydown(event: KeyboardEvent) {
    if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'z') return
    const target = event.target as HTMLElement | null
    const tag = target?.tagName?.toLowerCase()
    if (tag === 'input' || tag === 'textarea' || target?.isContentEditable) return
    if (!options.canUndo.value || options.busy.value) return
    event.preventDefault()
    options.undo()
  }

  function onBeforeUnload(event: BeforeUnloadEvent) {
    if (!options.canUndo.value) return
    event.preventDefault()
    event.returnValue = ''
  }

  onMounted(() => {
    window.addEventListener('keydown', onKeydown)
    window.addEventListener('beforeunload', onBeforeUnload)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown)
    window.removeEventListener('beforeunload', onBeforeUnload)
  })
}
