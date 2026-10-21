import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AI_IMPORT_TIMEOUT_MS, http } from '@/api/http'
import { aiParseErrorMessage, serverMessage } from '@/api/errors'
import { canCommit, remainingIssueCount, type ImportPreview } from './timetablePreview'
import { targetTitle } from './importTargets'

export interface RowError {
  row: number
  field: string
  message: string
}

export interface CommitResult {
  imported: number
  errors?: RowError[]
  warnings?: string[]
  draftId?: number | null
}

const STATUS_LABELS: Record<string, string> = {
  PREVIEW: '待确认',
  EDITED: '已微调',
  COMMITTED: '已入库',
  FAILED: '解析失败',
}

/**
 * The preview half of the import flow: the session state, the issue-derived commit
 * gate, and the calls that change a preview (save edits, continue, reparse, commit).
 * Uploading and parsing stay in the view because they own the file/text inputs.
 */
export function useImportSession() {
  const router = useRouter()
  const preview = ref<ImportPreview | null>(null)
  const editing = ref(false)
  const committing = ref(false)
  const continuing = ref(false)
  const reparsing = ref(false)
  const commitErrors = ref<RowError[]>([])
  const importedCount = ref(0)

  const isTimetable = computed(() => preview.value?.targetType === 'timetable')
  const previewIssues = computed(() => preview.value?.issues ?? [])
  const remainingIssues = computed(() => remainingIssueCount(previewIssues.value))
  const allIssuesResolved = computed(() => canCommit(previewIssues.value))
  const commitDisabled = computed(
    () =>
      !preview.value ||
      preview.value.status === 'COMMITTED' ||
      (isTimetable.value && !allIssuesResolved.value),
  )
  const commitLabel = computed(() => {
    if (preview.value?.status === 'COMMITTED') return '已入库'
    if (isTimetable.value && !allIssuesResolved.value) {
      return `确认入库（还有 ${remainingIssues.value} 项未解决）`
    }
    return '确认入库'
  })
  const statusLabel = computed(() => {
    if (!preview.value) return ''
    return STATUS_LABELS[preview.value.status] ?? preview.value.status
  })
  const statusType = computed(() => {
    if (!preview.value) return 'info'
    if (preview.value.status === 'COMMITTED') return 'success'
    if (preview.value.status === 'FAILED') return 'danger'
    return 'info'
  })

  /** Shows a freshly parsed or server-updated preview and drops stale row errors. */
  function setPreview(data: ImportPreview) {
    preview.value = data
    commitErrors.value = []
  }

  async function saveRows() {
    if (!preview.value) return
    const { data } = await http.put<ImportPreview>(
      `/ai/import/sessions/${preview.value.sessionId}/rows`,
      { rows: preview.value.rows },
    )
    preview.value = data
  }

  async function commit() {
    const current = preview.value
    if (!current) return
    committing.value = true
    commitErrors.value = []
    try {
      if (!isTimetable.value && editing.value) {
        await saveRows()
      }
      const { data } = await http.post<CommitResult>(
        `/ai/import/sessions/${current.sessionId}/commit`,
      )
      importedCount.value = data.imported
      preview.value = { ...current, status: 'COMMITTED' }
      if (data.draftId) {
        for (const warning of data.warnings ?? []) {
          ElMessage({ type: 'warning', message: warning, duration: 6000, showClose: true })
        }
        ElMessage.success(`已生成草稿，共 ${data.imported} 节课`)
        router.push(`/admin/drafts/${data.draftId}`)
        return
      }
      ElMessage.success(`已入库 ${data.imported} 条${targetTitle(current.targetType)}数据`)
    } catch (error) {
      const errors = (error as { response?: { data?: { errors?: RowError[] } } }).response?.data
        ?.errors
      if (errors?.length) {
        commitErrors.value = errors
        if (!isTimetable.value) {
          editing.value = true
        }
        ElMessage.error('有数据未通过校验，请修正后再入库')
      } else {
        ElMessage.error(serverMessage(error, '操作失败，请稍后重试'))
      }
    } finally {
      committing.value = false
    }
  }

  async function continueWithAi() {
    if (!preview.value) return
    continuing.value = true
    try {
      await saveRows()
      const { data } = await http.post<ImportPreview>(
        `/ai/import/sessions/${preview.value.sessionId}/continue`,
        undefined,
        { timeout: AI_IMPORT_TIMEOUT_MS },
      )
      preview.value = data
      ElMessage.success('AI 已基于你的修正继续解析')
    } catch (error) {
      ElMessage.error(aiParseErrorMessage(error))
    } finally {
      continuing.value = false
    }
  }

  async function reparse() {
    if (!preview.value) return
    try {
      await ElMessageBox.confirm(
        '将丢弃当前预览并重新调用 AI 解析原始文件，确定继续？',
        '打回重析',
        { type: 'warning' },
      )
    } catch {
      return
    }
    reparsing.value = true
    try {
      const { data } = await http.post<ImportPreview>(
        `/ai/import/sessions/${preview.value.sessionId}/reparse`,
        undefined,
        { timeout: AI_IMPORT_TIMEOUT_MS },
      )
      preview.value = data
      editing.value = false
      commitErrors.value = []
      ElMessage.success('已重新解析')
    } catch (error) {
      ElMessage.error(aiParseErrorMessage(error))
    } finally {
      reparsing.value = false
    }
  }

  /** Drops the session; the view also clears its file/text inputs. */
  function clear() {
    preview.value = null
    editing.value = false
    commitErrors.value = []
    importedCount.value = 0
  }

  return {
    preview,
    editing,
    committing,
    continuing,
    reparsing,
    commitErrors,
    importedCount,
    isTimetable,
    previewIssues,
    remainingIssues,
    allIssuesResolved,
    commitDisabled,
    commitLabel,
    statusLabel,
    statusType,
    setPreview,
    saveRows,
    commit,
    continueWithAi,
    reparse,
    clear,
  }
}
