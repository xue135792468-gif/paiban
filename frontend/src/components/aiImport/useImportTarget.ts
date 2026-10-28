import { computed, ref, watch, type Ref } from 'vue'
import { preselectedTarget } from './importTargets'

/**
 * The import target state. Excel defaults to the whole workbook (every sheet is read);
 * text and images keep a single target because they have no workbook shape. A teacher
 * fallback is remembered so switching back to Excel restores the workbook default
 * instead of silently leaving a teacher-only parse behind.
 */
export function useImportTarget(
  queryTarget: unknown,
  inputMode: Ref<'excel' | 'text' | 'image'>,
) {
  const selectedTarget = ref(preselectedTarget(queryTarget))
  let workbookFellBack = false

  watch(inputMode, (mode) => {
    if (mode === 'excel') {
      if (workbookFellBack) {
        selectedTarget.value = 'workbook'
        workbookFellBack = false
      }
      return
    }
    if (selectedTarget.value === 'workbook') {
      selectedTarget.value = 'teacher'
      workbookFellBack = true
    }
  })

  // Images are teacher-only; leaving the tab selected after switching target would
  // send a request the backend rejects.
  watch(selectedTarget, (target) => {
    if (target !== 'teacher' && inputMode.value === 'image') {
      inputMode.value = 'excel'
    }
  })

  /** The target the upload actually sends: workbook never leaves the Excel path. */
  const requestTarget = computed(() => {
    if (inputMode.value === 'excel') {
      return selectedTarget.value
    }
    return selectedTarget.value === 'workbook' ? 'teacher' : selectedTarget.value
  })

  function selectTarget(code: string) {
    selectedTarget.value = code
    workbookFellBack = false
  }

  function resetTarget() {
    const excel = inputMode.value === 'excel'
    selectedTarget.value = excel ? 'workbook' : 'teacher'
    // Resetting on a text/image tab leaves the teacher default; coming back to Excel
    // must still land on the workbook instead of a teacher-only parse.
    workbookFellBack = !excel
  }

  function applySuggestedTarget(suggested: string) {
    const fellBack = inputMode.value !== 'excel' && suggested === 'workbook'
    selectedTarget.value = fellBack ? 'teacher' : suggested
    workbookFellBack = fellBack
  }

  function restoreTarget(targetType: string) {
    selectedTarget.value = preselectedTarget(targetType)
  }

  return {
    selectedTarget,
    requestTarget,
    selectTarget,
    resetTarget,
    applySuggestedTarget,
    restoreTarget,
  }
}
