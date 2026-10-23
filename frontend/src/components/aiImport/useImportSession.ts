import { computed, onUnmounted, ref } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
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

/** Where the last import came from, kept so an empty page can offer to continue it. */
export interface LastSession {
  id: number
  fileName: string
  status: string
  savedAt: string
}

export type RestoreOutcome = 'restored' | 'failed' | 'unusable'

const STATUS_LABELS: Record<string, string> = {
  PARSING: '解析中',
  PREVIEW: '待确认',
  EDITED: '已微调',
  COMMITTED: '已入库',
  FAILED: '解析失败',
  PARTIAL: '部分结果',
  ABANDONED: '已放弃',
}

/** Statuses a restored session can render; anything else is not worth showing. */
const RESTORABLE_STATUSES = new Set([
  'PARSING',
  'PREVIEW',
  'EDITED',
  'COMMITTED',
  'FAILED',
  'PARTIAL',
])

/** Statuses that must not accept an edit or a commit. */
const LOCKED_STATUSES = new Set(['PARSING', 'PARTIAL', 'ABANDONED'])

/** How often a PARSING session is re-read while the batches are being parsed. */
const POLL_INTERVAL_MS = 2000

const LAST_SESSION_KEY = 'paiban.aiImport.lastSession'

export function statusText(status: string): string {
  return STATUS_LABELS[status] ?? status
}

export function statusTagType(status: string): 'success' | 'danger' | 'info' | 'warning' {
  if (status === 'COMMITTED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PARSING' || status === 'PARTIAL') return 'warning'
  return 'info'
}

/** A corrupted or half-written record is treated as absent, never as a crash. */
function readLastSession(): LastSession | null {
  try {
    const raw = localStorage.getItem(LAST_SESSION_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<LastSession> | null
    if (typeof parsed?.id !== 'number' || !parsed.fileName || !parsed.status || !parsed.savedAt) {
      return null
    }
    return {
      id: parsed.id,
      fileName: parsed.fileName,
      status: parsed.status,
      savedAt: parsed.savedAt,
    }
  } catch {
    return null
  }
}

/**
 * The preview half of the import flow: the session state, the issue-derived commit
 * gate, and the calls that change a preview (save edits, continue, reparse, commit).
 * Uploading and parsing stay in the view because they own the file/text inputs.
 */
export function useImportSession() {
  const route = useRoute()
  const router = useRouter()
  const preview = ref<ImportPreview | null>(null)
  const editing = ref(false)
  const committing = ref(false)
  const continuing = ref(false)
  const reparsing = ref(false)
  const commitErrors = ref<RowError[]>([])
  /** Null until this page commits: a restored COMMITTED session has no known count. */
  const importedCount = ref<number | null>(null)
  const lastSession = ref<LastSession | null>(readLastSession())

  const isTimetable = computed(() => preview.value?.targetType === 'timetable')
  const previewIssues = computed(() => preview.value?.issues ?? [])
  const remainingIssues = computed(() => remainingIssueCount(previewIssues.value))
  const allIssuesResolved = computed(() => canCommit(previewIssues.value))
  const commitDisabled = computed(
    () =>
      !preview.value ||
      preview.value.status === 'COMMITTED' ||
      preview.value.status === 'FAILED' ||
      LOCKED_STATUSES.has(preview.value.status) ||
      (isTimetable.value && !allIssuesResolved.value),
  )
  const commitLabel = computed(() => {
    if (preview.value?.status === 'COMMITTED') return '已入库'
    if (preview.value?.status === 'FAILED') return '解析失败'
    if (preview.value?.status === 'PARSING') return '解析中…'
    if (preview.value?.status === 'PARTIAL') return '先重试或放弃'
    if (preview.value?.status === 'ABANDONED') return '已放弃'
    if (isTimetable.value && !allIssuesResolved.value) {
      return `确认入库（还有 ${remainingIssues.value} 项未解决）`
    }
    return '确认入库'
  })
  const statusLabel = computed(() => {
    if (!preview.value) return ''
    return statusText(preview.value.status)
  })
  const statusType = computed(() => {
    if (!preview.value) return 'info'
    return statusTagType(preview.value.status)
  })
  const isParsing = computed(() => preview.value?.status === 'PARSING')
  const polling = ref(false)
  let pollTimer: ReturnType<typeof setInterval> | null = null

  /**
   * A PARSING session is filled in batch by batch on the server, so the page re-reads
   * it every couple of seconds and stops as soon as the status moves on.
   */
  function stopPolling() {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
    polling.value = false
  }

  function startPolling(sessionId: number) {
    stopPolling()
    polling.value = true
    pollTimer = setInterval(() => {
      void pollProgress(sessionId)
    }, POLL_INTERVAL_MS)
  }

  async function pollProgress(sessionId: number) {
    try {
      const { data } = await http.get<ImportPreview>(`/ai/import/sessions/${sessionId}`)
      if (data && preview.value?.sessionId === data.sessionId) {
        // setPreview re-evaluates the polling state: PREVIEW ends the loop.
        setPreview(data)
      }
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 404 || status === 403) {
        stopPolling()
        ElMessage.error(
          status === 404
            ? '该导入会话已不存在，请重新上传文件'
            : '没有权限查看该导入会话，请重新上传文件',
        )
      }
      // Any other failure is a blip: the next tick tries again.
    }
  }

  function syncPolling(data: ImportPreview) {
    if (data.status === 'PARSING') {
      startPolling(data.sessionId)
    } else {
      stopPolling()
    }
  }

  /** Shows a freshly parsed or server-updated preview and drops stale row errors. */
  function setPreview(data: ImportPreview) {
    preview.value = data
    commitErrors.value = []
    importedCount.value = null
    rememberStatus(data.sessionId, data.status)
    syncPolling(data)
  }

  function storeLastSession(record: LastSession | null) {
    lastSession.value = record
    if (record) {
      localStorage.setItem(LAST_SESSION_KEY, JSON.stringify(record))
    } else {
      localStorage.removeItem(LAST_SESSION_KEY)
    }
  }

  /** Remembers where an import came from so the empty page can offer to continue it. */
  function rememberSession(data: ImportPreview, fileName?: string) {
    storeLastSession({
      id: data.sessionId,
      fileName: fileName?.trim() || '粘贴文本',
      status: data.status,
      savedAt: new Date().toISOString(),
    })
  }

  /** Forgets the stored last-import record; with an id, only when it is that session. */
  function forgetSession(sessionId?: number) {
    if (sessionId !== undefined && lastSession.value?.id !== sessionId) return
    storeLastSession(null)
  }

  /** Keeps the stored record honest when this session changes status. */
  function rememberStatus(sessionId: number, status: string) {
    const record = lastSession.value
    if (!record || record.id !== sessionId) return
    storeLastSession({ ...record, status })
  }

  /** The query without `session`; unrelated params such as `target` stay put. */
  function queryWithoutSession(): LocationQueryRaw {
    const query: LocationQueryRaw = { ...route.query }
    delete query.session
    return query
  }

  function dropSessionFromUrl() {
    if (!route.query.session) return
    void router.replace({ query: queryWithoutSession() })
  }

  /**
   * Restores a server-side session into the preview. A session the server no longer
   * knows, or whose status this page cannot render, falls back to the empty import page.
   * A transient failure keeps `?session=` so a refresh can retry it.
   */
  async function restore(sessionId: number | string): Promise<RestoreOutcome> {
    const id = encodeURIComponent(String(sessionId).trim())
    try {
      const { data } = await http.get<ImportPreview>(`/ai/import/sessions/${id}`)
      if (!data || !RESTORABLE_STATUSES.has(data.status)) {
        if (data) forgetSession(data.sessionId)
        clear()
        ElMessage.error('该导入会话已不可用，请重新上传文件')
        return 'unusable'
      }
      setPreview(data)
      editing.value = false
      void router.replace({
        query: { ...route.query, session: String(data.sessionId), target: data.targetType },
      })
      return 'restored'
    } catch (error) {
      const status = (error as { response?: { status?: number } })?.response?.status
      if (status === 404 || status === 403) {
        const requested = Number(String(sessionId).trim())
        if (Number.isFinite(requested)) forgetSession(requested)
        clear()
        ElMessage.error(
          status === 404
            ? '上次的导入会话已不存在，请重新上传文件'
            : '没有权限查看该导入会话，请重新上传文件',
        )
        return 'failed'
      }
      resetState()
      ElMessage.error(serverMessage(error, '恢复导入预览失败，请重新上传文件'))
      return 'failed'
    }
  }

  async function saveRows() {
    if (!preview.value) return
    const { data } = await http.put<ImportPreview>(
      `/ai/import/sessions/${preview.value.sessionId}/rows`,
      { rows: preview.value.rows },
    )
    setPreview(data)
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
      rememberStatus(current.sessionId, 'COMMITTED')
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
      setPreview(data)
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
      setPreview(data)
      editing.value = false
      ElMessage.success('已重新解析')
    } catch (error) {
      ElMessage.error(aiParseErrorMessage(error))
    } finally {
      reparsing.value = false
    }
  }

  /** Clears the preview half only; the URL marker survives so a refresh can retry. */
  function resetState() {
    stopPolling()
    preview.value = null
    editing.value = false
    commitErrors.value = []
    importedCount.value = null
  }

  /** Drops the session and its URL marker; the view also clears its file/text inputs. */
  function clear() {
    resetState()
    dropSessionFromUrl()
  }

  // Leaving the page must not leave a timer behind calling the API.
  onUnmounted(stopPolling)

  return {
    preview,
    editing,
    committing,
    continuing,
    reparsing,
    commitErrors,
    importedCount,
    lastSession,
    isTimetable,
    isParsing,
    polling,
    previewIssues,
    remainingIssues,
    allIssuesResolved,
    commitDisabled,
    commitLabel,
    statusLabel,
    statusType,
    setPreview,
    rememberSession,
    forgetSession,
    restore,
    dropSessionFromUrl,
    saveRows,
    commit,
    continueWithAi,
    reparse,
    stopPolling,
    clear,
  }
}
