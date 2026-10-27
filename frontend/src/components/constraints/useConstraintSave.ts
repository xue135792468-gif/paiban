import { ref, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import { mergeConfigs } from './conflictMerge'
import { emptySaveErrors, parseSaveErrors, type SaveErrorReport } from './saveErrors'
import type { useConstraintDraft } from './useConstraintDraft'
import type { ConstraintConfigDto } from './types'

/**
 * What a 409 carries when the configuration moved under the user: the newer version and
 * the configuration it belongs to. The page keeps the draft untouched until the user
 * decides, so nothing is overwritten silently.
 */
export interface SaveConflict {
  message: string
  version: number
  configs: ConstraintConfigDto[]
}

/**
 * The save half of the constraint page: pre-flight checks (contradictory conditions,
 * missing required parameters, looser threshold exceptions) before the request, then
 * the single versioned `PUT` and the error report that lets the page point at the
 * offending type/entry.
 *
 * HTTP lives here rather than in the view so the page keeps rendering concerns only,
 * and so the pre-check order is stated once.
 */
export function useConstraintSave(options: {
  draft: ReturnType<typeof useConstraintDraft>
  semesterId: Ref<number | null>
  conflicts: Ref<string[]>
  /** Exceptions that loosen a global limit; the user must confirm them explicitly. */
  relaxations?: () => string[]
  reload: () => Promise<void>
}) {
  const saving = ref(false)
  const saveMessage = ref<string | null>(null)
  const saveReport = ref<SaveErrorReport>(emptySaveErrors())
  const conflict = ref<SaveConflict | null>(null)

  async function save() {
    await persist(true)
  }

  async function persist(precheck: boolean) {
    if (!options.semesterId.value) {
      return
    }
    if (precheck) {
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
      // A looser exception replaces the global limit for that object. Nothing is saved
      // until the user has seen what will actually run.
      const relaxations = options.relaxations?.() ?? []
      if (relaxations.length > 0) {
        try {
          await ElMessageBox.confirm(
            relaxations.join('\n'),
            '确认放宽限制',
            {
              confirmButtonText: '确认放宽并保存',
              cancelButtonText: '返回修改',
              type: 'warning',
            },
          )
        } catch {
          return
        }
      }
    }

    saving.value = true
    saveMessage.value = null
    saveReport.value = emptySaveErrors()
    try {
      await http.put(
        '/constraints',
        { configs: options.draft.payload() },
        {
          params: {
            semesterId: options.semesterId.value,
            baseVersion: options.draft.baseVersion.value,
          },
        },
      )
      conflict.value = null
      ElMessage.success('约束配置已保存')
      await options.reload()
    } catch (e: any) {
      if (e?.response?.status === 409) {
        await captureConflict(e)
        return
      }
      // Keep the user's input and point at the offending type/entry.
      saveMessage.value = serverMessage(e, '保存约束配置失败')
      saveReport.value = parseSaveErrors(saveMessage.value, options.draft.rows.value)
      ElMessage.error(saveMessage.value)
    } finally {
      saving.value = false
    }
  }

  /**
   * A business 409 carries the latest configuration with the new version. A race lost
   * inside the server (optimistic lock) carries only a message, so the latest state is
   * fetched through the read endpoint before the page offers its choices.
   */
  async function captureConflict(error: any) {
    const body = error?.response?.data
    const message = serverMessage(error, '约束配置已被他人修改，请核对后再保存')
    if (typeof body?.version === 'number' && Array.isArray(body?.configs)) {
      conflict.value = { message, version: body.version, configs: body.configs }
      return
    }
    try {
      const { data } = await http.get<{ version: number; configs: ConstraintConfigDto[] }>(
        '/constraints/state',
        { params: { semesterId: options.semesterId.value } },
      )
      conflict.value = { message, version: data.version, configs: data.configs ?? [] }
    } catch {
      saveMessage.value = message
      ElMessage.error(message)
    }
  }

  /** Discards the local draft and continues from the server's configuration. */
  function takeTheirs() {
    if (!conflict.value) {
      return
    }
    options.draft.rehydrate(conflict.value.configs)
    options.draft.setBaseVersion(conflict.value.version)
    conflict.value = null
    ElMessage.success('已加载最新配置，你的未保存修改已放弃')
  }

  /**
   * Keeps every type the user edited, takes the newer server row for every type they
   * did not, then saves the merged configuration on top of the new version.
   */
  async function keepMine() {
    if (!conflict.value) {
      return
    }
    const merged = mergeConfigs(
      options.draft.basePayload.value,
      options.draft.payload(),
      conflict.value.configs,
    )
    options.draft.setBaseVersion(conflict.value.version)
    options.draft.rehydrate(merged)
    conflict.value = null
    await persist(true)
  }

  /** Drops the previous save report, e.g. after the page reloaded from the server. */
  function clearErrors() {
    saveMessage.value = null
    saveReport.value = emptySaveErrors()
    conflict.value = null
  }

  return {
    saving,
    saveMessage,
    saveReport,
    conflict,
    save,
    takeTheirs,
    keepMine,
    clearErrors,
  }
}
