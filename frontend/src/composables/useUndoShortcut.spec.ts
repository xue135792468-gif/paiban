import { defineComponent, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { useUndoShortcut } from './useUndoShortcut'

function mountHost(initialCanUndo = false) {
  const canUndo = ref(initialCanUndo)
  const busy = ref(false)
  const undo = vi.fn()
  const Host = defineComponent({
    setup() {
      useUndoShortcut({ canUndo, busy, undo })
      return () => null
    },
  })
  const wrapper = mount(Host)
  return { wrapper, canUndo, busy, undo }
}

describe('useUndoShortcut beforeunload guard', () => {
  it('does not block unload when undo history is empty', () => {
    const { wrapper } = mountHost(false)
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(false)
    wrapper.unmount()
  })

  it('blocks unload when undo history exists and detaches on unmount', () => {
    const { wrapper, canUndo } = mountHost(true)
    const first = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(first)
    expect(first.defaultPrevented).toBe(true)

    canUndo.value = false
    const cleared = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(cleared)
    expect(cleared.defaultPrevented).toBe(false)

    canUndo.value = true
    wrapper.unmount()
    const afterUnmount = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterUnmount)
    expect(afterUnmount.defaultPrevented).toBe(false)
  })
})
