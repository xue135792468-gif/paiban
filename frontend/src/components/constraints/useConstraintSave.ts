import { ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { emptySaveErrors, parseSaveErrors, type SaveErrorReport } from './saveErrors'
import type { useConstraintDraft } from './useConstraintDraft'

/**
 * The save half of the constraint page: pre-flight checks (contradictory conditions,
 * missing required parameters) before the request, then the single `PUT` and the
 * error report that lets the page point at the offending type/entry.
 *
 * HTTP lives here rather than in the view so the page keeps rendering concerns only,
 * and so the pre-check order (conflicts → required → request) is stated once.
 */
export function useConstraintSave(options: {
  draft: ReturnType<typeof useConstraintDraft>
  semesterId: Ref<number | null>
  conflicts: Ref<string[]>
  reload: () => Promise<void>
}) {
  const saving = ref(false)
  const saveMessage = ref<string | null>(null)
  const saveReport = ref<SaveErrorReport>(emptySaveErrors())

  async function save() {
    if (!options.semesterId.value) {
      return
    }
    // Nothing is silently rewritten: contradictory entries block the save and the
    // user's input stays exactly as typed until they change one of them.
    if (options.conflicts.value.length > 0) {
      ElMessage.warning('同一目标的互斥条件冲突，请先改掉其中一条再保存')
      return
    }
    const warnings = options.draft.warnings()
    if (warnings.length > 0) {
      ElMessage.warning(
        `请先填写必填参数：${warnings.slice(0, 3).join('、')}${warnings.length > 3 ? ' 等' : ''}`,
      )
      return
    }

    saving.value = true
    saveMessage.value = null
    saveReport.value = emptySaveErrors()
    try {
      await http.put(
        '/constraints',
        { configs: options.draft.payload() },
        { params: { semesterId: options.semesterId.value } },
      )
      ElMessage.success('约束配置已保存')
      await options.reload()
    } catch (e: any) {
      // Keep the user's input and point at the offending type/entry.
      saveMessage.value = serverMessage(e, '保存约束配置失败')
      saveReport.value = parseSaveErrors(saveMessage.value, options.draft.rows.value)
      ElMessage.error(saveMessage.value)
    } finally {
      saving.value = false
    }
  }

  /** Drops the previous save report, e.g. after the page reloaded from the server. */
  function clearErrors() {
    saveMessage.value = null
    saveReport.value = emptySaveErrors()
  }

  return { saving, saveMessage, saveReport, save, clearErrors }
}
