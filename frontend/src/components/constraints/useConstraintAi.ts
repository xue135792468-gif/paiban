import { computed, ref, type Ref } from 'vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'

export interface AiOption {
  id: number
  name: string
}

export interface ProposalCandidate {
  field: string
  label: string
  options: AiOption[]
}

export interface ProposalEntry {
  params: Record<string, unknown>
  groupKey?: string | null
  enabled?: boolean
  hard?: boolean | null
  weight?: number | null
}

export interface ConstraintProposal {
  proposalId: string
  sourceText: string
  type: string
  typeName: string
  strength: 'HARD' | 'SOFT' | 'INHERIT'
  effectiveHard: boolean
  effectiveWeight: number | null
  status: 'READY' | 'NEEDS_INPUT' | 'UNSUPPORTED' | 'INVALID'
  explanation: string
  reason: string
  params: Record<string, unknown>
  entries: ProposalEntry[]
  candidates: ProposalCandidate[]
}

export interface GenerationRun {
  id: number
  clientRunId: string
  semesterId: number
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'
  inputText: string
  errorCode: string | null
  errorMessage: string | null
  providerName: string | null
  modelName: string | null
  inputTokens: number | null
  outputTokens: number | null
  latencyMs: number | null
  startedAt: string
  finishedAt: string | null
  proposals: ConstraintProposal[]
}

export interface GenerationRunSummary {
  id: number
  status: GenerationRun['status']
  inputText: string
  errorCode: string | null
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
  proposalCount: number
}

export interface GenerationRunDetail {
  run: GenerationRun
  rawResponse: string | null
  modelContent: string | null
  lateResponse: string | null
  contextJson: string | null
}

/** The manual choices the save records next to the final configuration. */
export interface SaveDecision {
  proposalId: string
  action: 'ADDED' | 'REPLACED' | 'SKIPPED' | 'EXISTS'
  type: string
  reason: string
  entries: Record<string, unknown>[]
}

export interface SaveAudit {
  id: number
  semesterId: number
  version: number
  runId: number | null
  actor: string | null
  createdAt: string
  decisions: SaveDecision[]
  finalConfigs: unknown[]
}

export interface ResolveOverrides {
  teacherId?: number
  courseId?: number
  classId?: number
  strength?: 'HARD' | 'SOFT' | 'INHERIT'
  days?: number[]
  periods?: number[]
  section?: string
  sectionOrdinals?: number[]
  values?: Record<string, unknown>
}

export interface AiAvailability {
  available: boolean
  reason: 'FEATURE_DISABLED' | 'NOT_CONFIGURED' | null
  message: string | null
}

let runSeed = 0

/** A browser-generated identity for one click; the server deduplicates on it. */
function clientRunId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  runSeed += 1
  return `run-${Date.now()}-${runSeed}`
}

/**
 * The read-only natural-language half of the constraint page (ticket 14/08).
 *
 * It only ever reads proposals and records: the input, the run identity and the
 * cancel call live here, while adding suggestions to the table belongs to the later
 * confirmation ticket. A failed or timed-out run keeps the original text so the user
 * can retry deliberately; nothing is retried automatically.
 */
export function useConstraintAi(options: {
  semesterId: Ref<number | null>
  canReadAll?: () => boolean
}) {
  const availability = ref<AiAvailability>({
    available: false,
    reason: null,
    message: null,
  })
  const checking = ref(false)
  const text = ref('')
  const generating = ref(false)
  /** The click identity while the synchronous generate call is still waiting. */
  const pendingClientRunId = ref<string | null>(null)
  const run = ref<GenerationRun | null>(null)
  const error = ref<string | null>(null)
  const history = ref<GenerationRunSummary[]>([])
  const historyLoading = ref(false)
  const detail = ref<GenerationRunDetail | null>(null)
  const scopeAll = ref(false)
  /** Proposals the user resolved manually (a homonym choice or a strength edit). */
  const resolved = ref<Record<string, ConstraintProposal>>({})
  const selectedIds = ref<string[]>([])
  const audits = ref<SaveAudit[]>([])

  const proposals = computed(() =>
    (run.value?.proposals ?? []).map((proposal) => resolved.value[proposal.proposalId] ?? proposal),
  )
  const selectedProposals = computed(() =>
    proposals.value.filter((proposal) => selectedIds.value.includes(proposal.proposalId)
      && proposal.status === 'READY'),
  )
  const counts = computed(() => ({
    total: proposals.value.length,
    ready: proposals.value.filter((proposal) => proposal.status === 'READY').length,
    needsInput: proposals.value.filter((proposal) => proposal.status === 'NEEDS_INPUT').length,
    unsupported: proposals.value.filter(
      (proposal) => proposal.status === 'UNSUPPORTED' || proposal.status === 'INVALID',
    ).length,
  }))
  const canReadAll = computed(() => options.canReadAll?.() ?? false)

  async function checkAvailability() {
    if (!options.semesterId.value) {
      return
    }
    checking.value = true
    try {
      const { data } = await http.get<AiAvailability>('/constraint-proposals/availability')
      availability.value = data
    } catch (e: any) {
      availability.value = {
        available: false,
        reason: null,
        message: serverMessage(e, '无法读取 AI 可用状态'),
      }
    } finally {
      checking.value = false
    }
  }

  async function generate() {
    if (!options.semesterId.value) {
      return
    }
    const value = text.value.trim()
    if (!value) {
      error.value = '请先输入排课要求'
      return
    }
    generating.value = true
    error.value = null
    detail.value = null
    const pending = clientRunId()
    pendingClientRunId.value = pending
    try {
      const { data } = await http.post<GenerationRun>(
        '/constraint-proposals',
        { clientRunId: pending, text: value },
        { params: { semesterId: options.semesterId.value }, timeout: 65_000 },
      )
      run.value = data
      resolved.value = {}
      selectedIds.value = []
      await loadHistory()
    } catch (e: any) {
      // The original text stays in the box: the user retries deliberately.
      error.value = serverMessage(e, '生成建议失败，请稍后重试')
    } finally {
      pendingClientRunId.value = null
      generating.value = false
    }
  }

  /**
   * Stops waiting; the answer may still arrive in the record, never in this page.
   * While the synchronous generate call is in flight, the only handle is the click
   * identity, so the cancel goes through that.
   */
  async function cancel() {
    const current = run.value
    try {
      const { data } = current && current.status === 'RUNNING'
        ? await http.post<GenerationRun>(`/constraint-proposals/${current.id}/cancel`)
        : await http.post<GenerationRun>('/constraint-proposals/cancel', null, {
            params: { clientRunId: pendingClientRunId.value },
          })
      run.value = data
      await loadHistory()
    } catch (e: any) {
      error.value = serverMessage(e, '取消生成失败')
    }
  }

  async function loadHistory() {
    if (!options.semesterId.value) {
      history.value = []
      return
    }
    historyLoading.value = true
    try {
      const { data } = await http.get<GenerationRunSummary[]>('/constraint-proposals', {
        params: {
          semesterId: options.semesterId.value,
          scope: scopeAll.value && canReadAll.value ? 'all' : 'mine',
        },
      })
      history.value = data ?? []
    } catch (e: any) {
      error.value = serverMessage(e, '加载生成记录失败')
    } finally {
      historyLoading.value = false
    }
  }

  async function openRun(id: number) {
    try {
      const { data } = await http.get<GenerationRunDetail>(`/constraint-proposals/${id}`)
      detail.value = data
      run.value = data.run
      text.value = data.run.inputText
    } catch (e: any) {
      error.value = serverMessage(e, '加载生成记录失败')
    }
  }

  function setScopeAll(value: boolean) {
    scopeAll.value = value
    void loadHistory()
    void loadAudits()
  }

  function clearSelection() {
    selectedIds.value = []
  }

  function toggleSelected(proposalId: string, selected: boolean) {
    const current = new Set(selectedIds.value)
    if (selected) {
      current.add(proposalId)
    } else {
      current.delete(proposalId)
    }
    selectedIds.value = Array.from(current)
  }

  /**
   * Completes/edits one proposal without a model call. The returned suggestion
   * replaces the displayed one so the user can review it before selecting it.
   */
  async function resolveProposal(
    proposalId: string,
    overrides: ResolveOverrides,
  ): Promise<ConstraintProposal | null> {
    const current = run.value
    if (!current) {
      return null
    }
    try {
      const { data } = await http.post<ConstraintProposal>(
        `/constraint-proposals/${current.id}/resolve`,
        { proposalId, ...overrides },
      )
      resolved.value = { ...resolved.value, [proposalId]: data }
      if (data.status !== 'READY' && selectedIds.value.includes(proposalId)) {
        toggleSelected(proposalId, false)
      }
      return data
    } catch (e: any) {
      error.value = serverMessage(e, '补齐建议失败')
      return null
    }
  }

  /** Saved confirmations: the final configuration and the decisions behind it. */
  async function loadAudits() {
    if (!options.semesterId.value) {
      audits.value = []
      return
    }
    try {
      const { data } = await http.get<SaveAudit[]>('/constraint-audits', {
        params: {
          semesterId: options.semesterId.value,
          scope: scopeAll.value && canReadAll.value ? 'all' : 'mine',
        },
      })
      audits.value = data ?? []
    } catch (e: any) {
      error.value = serverMessage(e, '加载保存记录失败')
    }
  }

  /** The part of the unsaved work this panel owns, for the workspace autosave. */
  function workspaceState() {
    return {
      text: text.value,
      runId: run.value?.id ?? null,
      selectedProposalIds: [...selectedIds.value],
      resolved: resolved.value,
    }
  }

  /** Rebuilds the panel from a restored workspace; no model call is made. */
  async function restoreState(payload: {
    text?: string
    runId?: number | null
    selectedProposalIds?: string[]
    resolved?: Record<string, ConstraintProposal>
  }) {
    selectedIds.value = payload.selectedProposalIds ?? []
    resolved.value = payload.resolved ?? {}
    if (payload.runId) {
      await openRun(payload.runId)
    }
    text.value = payload.text ?? ''
  }

  /** Called when the semester changes: a run of another semester is not shown here. */
  function reset() {
    run.value = null
    detail.value = null
    text.value = ''
    error.value = null
    history.value = []
    resolved.value = {}
    selectedIds.value = []
    audits.value = []
    void checkAvailability()
    void loadHistory()
    void loadAudits()
  }

  return {
    availability,
    checking,
    text,
    generating,
    run,
    proposals,
    selectedProposals,
    counts,
    error,
    history,
    historyLoading,
    detail,
    scopeAll,
    canReadAll,
    checkAvailability,
    generate,
    cancel,
    loadHistory,
    openRun,
    setScopeAll,
    toggleSelected,
    clearSelection,
    resolveProposal,
    selectedIds,
    loadAudits,
    audits,
    workspaceState,
    restoreState,
    reset,
  }
}
