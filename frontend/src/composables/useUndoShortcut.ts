import { onBeforeUnmount, onMounted, type Ref } from 'vue'

export interface UndoShortcutOptions {
  canUndo: Ref<boolean>
  busy: Ref<boolean>
  undo: () => void
}

/**
 * `Ctrl/Cmd+Z` for the draft editor. Kept out of the view so the SFC stays under
 * the 600-line limit; the guardrails (no undo inside inputs, never while busy)
 * are the same as before.
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

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}
