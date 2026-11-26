import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'

/**
 * Prevents accidental loss of unsaved rule edits on both in-app route changes
 * and browser tab close/reload.
 */
export function useUnsavedGuard(dirty: Ref<boolean>) {
  function onBeforeUnload(event: BeforeUnloadEvent) {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }

  onMounted(() => {
    window.addEventListener('beforeunload', onBeforeUnload)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload)
  })

  onBeforeRouteLeave(async () => {
    if (!dirty.value) return true
    try {
      await ElMessageBox.confirm(
        '当前有未保存的排课规则修改，离开后修改将丢失。确定要离开吗？',
        '未保存的修改',
        {
          type: 'warning',
          confirmButtonText: '离开（放弃修改）',
          cancelButtonText: '留在当前页',
          distinguishCancelAndClose: true,
        },
      )
      return true
    } catch {
      return false
    }
  })
}
