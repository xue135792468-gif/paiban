import { computed, nextTick, onBeforeUnmount, ref, watch, type ComputedRef, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import {
  useConstraintWorkspace,
  type WorkspaceDto,
  type WorkspacePayload,
} from './useConstraintWorkspace'
import type { useConstraintDraft } from './useConstraintDraft'
import type { ConstraintProposal, SaveDecision } from './useConstraintAi'
import type { ConstraintConfigDto, ObjectNames } from './types'

/** The methods the AI panel exposes to the page. */
export interface AiPanelHandle {
  loadAudits: () => void | Promise<void>
  clearSelection: () => void
  workspaceState: () => {
    text: string
    runId: number | null
    selectedProposalIds: string[]
    resolved: Record<string, ConstraintProposal>
  }
  restoreState: (payload: WorkspacePayload) => Promise<void>
}

/**
 * The page-level collaboration glue: native-language proposals being added to the
 * borrowed draft, the cross-device workspace autosave/restore, and the conflict
 * choices. It lives outside the view so the page itself stays a rendering shell
 * (ticket 14/10, 14/11).
 */
export function useConstraintCollaboration(options: {
  draft: ReturnType<typeof useConstraintDraft>
  semesterId: Ref<number | null>
  isJunior: ComputedRef<boolean>
  objectNames: ComputedRef<ObjectNames>
}) {
  const aiPanel = ref<AiPanelHandle | null>(null)
  const aiRunId = ref<number | null>(null)
  const aiDecisions = ref<SaveDecision[]>([])
  const aiOutcomes = ref<{ proposalId: string; action: string; message: string }[]>([])
  const workspace = useConstraintWorkspace({ semesterId: options.semesterId })
  const { conflict: workspaceConflict, error: workspaceError } = workspace
  const workspaceStale = ref(false)
  const workspaceIssues = ref<string[]>([])
  const restoringWorkspace = ref(false)
  const suppressAutosave = ref(false)
  const effectivePayloadJson = ref('')
  let lastWorkspaceSignature = ''
  let autosaveTimer: ReturnType<typeof setTimeout> | null = null

  const aiOutcomeType = computed(() =>
    aiOutcomes.value.some((outcome) => outcome.action === 'CONFLICT'
      || outcome.action === 'SKIPPED' || outcome.action === 'REJECTED')
      ? 'warning'
      : 'success',
  )

  function objectNameOf(proposal: ConstraintProposal): string {
    const params = proposal.entries[0]?.params ?? {}
    if (params.teacherId !== undefined) {
      return options.objectNames.value.teacherId.get(Number(params.teacherId))
        ?? `教师#${params.teacherId}`
    }
    if (params.courseId !== undefined) {
      return options.objectNames.value.courseId.get(Number(params.courseId))
        ?? `课程#${params.courseId}`
    }
    if (params.classId !== undefined) {
      return options.objectNames.value.classId.get(Number(params.classId))
        ?? `班级#${params.classId}`
    }
    return '该对象'
  }

  /**
   * Adds the selected proposals to the borrowed draft, then asks the server to
   * validate the merged configuration. A refusal rolls the whole batch back; a
   * control/scalar conflict keeps the old value unless the user explicitly replaces
   * it, and a looser threshold needs its own confirmation.
   */
  async function onAiAdd(payload: { runId: number; proposals: ConstraintProposal[] }) {
    if (!options.semesterId.value) {
      return
    }
    const before = options.draft.snapshotRows()
    const outcomes: { proposalId: string; action: string; message: string }[] = []
    const decisions: SaveDecision[] = []
    for (const proposal of payload.proposals) {
      let outcome = options.draft.applyProposal(proposal.type, proposal, {})
      if (outcome.action === 'CONFLICT') {
        try {
          await ElMessageBox.confirm(outcome.message, '保留旧值还是替换？', {
            confirmButtonText: '用新建议替换',
            cancelButtonText: '保留旧值',
            type: 'warning',
          })
          outcome = options.draft.applyProposal(proposal.type, proposal, { replace: true })
        } catch {
          outcome = { action: 'SKIPPED', message: `已保留旧值，未加入「${proposal.typeName}」。` }
        }
      } else if (outcome.action === 'RELAXES') {
        try {
          await ElMessageBox.confirm(outcome.message, '确认放宽限制', {
            confirmButtonText: '确认放宽并加入',
            cancelButtonText: '取消加入',
            type: 'warning',
          })
          outcome = options.draft.applyProposal(proposal.type, proposal, {
            replace: true,
            confirmRelaxation: true,
            objectName: objectNameOf(proposal),
          })
        } catch {
          outcome = { action: 'SKIPPED', message: '已取消这条放宽要求的加入。' }
        }
      }
      outcomes.push({ proposalId: proposal.proposalId, ...outcome })
      decisions.push({
        proposalId: proposal.proposalId,
        action: outcome.action === 'ADDED' || outcome.action === 'REPLACED'
          ? outcome.action : outcome.action === 'EXISTS' ? 'EXISTS' : 'SKIPPED',
        type: proposal.type,
        reason: outcome.message,
        entries: proposal.entries.map((entry) => entry.params),
      })
    }

    try {
      const { data } = await http.post<{ errors: string[]; invalidReferences: unknown[] }>(
        '/constraints/preview',
        { configs: options.draft.payload() },
        { params: { semesterId: options.semesterId.value } },
      )
      const errors = [...(data.errors ?? []),
        ...(data.invalidReferences ?? []).map((issue) => JSON.stringify(issue))]
      if (errors.length > 0) {
        options.draft.restoreRows(before)
        aiOutcomes.value = [{
          proposalId: '',
          action: 'REJECTED',
          message: `服务端复核未通过，整批未加入：${errors.join('；')}`,
        }]
        ElMessage.error('建议未加入：请先修正冲突')
        return
      }
    } catch (e: any) {
      options.draft.restoreRows(before)
      aiOutcomes.value = [{
        proposalId: '',
        action: 'REJECTED',
        message: `服务端复核失败，整批未加入：${serverMessage(e, '预览失败')}`,
      }]
      return
    }

    aiRunId.value = payload.runId
    aiDecisions.value = decisions
    aiOutcomes.value = outcomes
    aiPanel.value?.clearSelection()
    ElMessage.success('建议已加入当前表格；仍是未保存修改，请检查后统一保存')
  }

  // ------------------------------------------------------- workspace autosave

  function workspacePayload(): WorkspacePayload {
    const ai = aiPanel.value?.workspaceState()
    return {
      text: ai?.text ?? '',
      runId: ai?.runId ?? aiRunId.value,
      selectedProposalIds: ai?.selectedProposalIds ?? [],
      resolved: ai?.resolved ?? {},
      decisions: aiDecisions.value,
      configs: options.draft.payload(),
      isJunior: options.isJunior.value,
    }
  }

  const workspaceSignature = computed(() =>
    JSON.stringify({
      configs: options.draft.payload(),
      ai: aiPanel.value?.workspaceState() ?? null,
      runId: aiRunId.value,
      decisions: aiDecisions.value,
    }),
  )

  function scheduleAutosave() {
    if (autosaveTimer) {
      clearTimeout(autosaveTimer)
    }
    autosaveTimer = setTimeout(() => {
      autosaveTimer = null
      void autosaveWorkspace()
    }, 800)
  }

  async function autosaveWorkspace() {
    if (suppressAutosave.value || restoringWorkspace.value || !options.semesterId.value) {
      return
    }
    const signature = workspaceSignature.value
    if (signature === lastWorkspaceSignature) {
      return
    }
    const ai = aiPanel.value?.workspaceState()
    const hasWork = options.draft.dirty.value || (ai?.text ?? '') !== ''
      || aiDecisions.value.length > 0
    if (!hasWork) {
      return
    }
    const result = await workspace.save(workspacePayload(), options.draft.baseVersion.value)
    if (result === 'SAVED') {
      lastWorkspaceSignature = signature
    }
  }

  async function applyWorkspace(
    restored: WorkspaceDto,
    currentVersion: number,
    effectiveSnapshot: string,
  ) {
    options.draft.rehydrate(
      (restored.payload.configs ?? []) as unknown as ConstraintConfigDto[],
    )
    options.draft.setBaseVersion(currentVersion)
    options.draft.setSnapshot(effectiveSnapshot)
    await nextTick()
    await aiPanel.value?.restoreState(restored.payload)
    aiRunId.value = restored.payload.runId ?? null
    aiDecisions.value = restored.payload.decisions ?? []
    workspaceStale.value = restored.baseVersion !== currentVersion
    workspaceIssues.value = [
      ...(restored.errors ?? []),
      ...(restored.invalidReferences ?? []).map((issue) => JSON.stringify(issue)),
    ]
  }

  async function restoreWorkspace() {
    const currentVersion = options.draft.baseVersion.value
    const effectiveSnapshot = effectivePayloadJson.value
    restoringWorkspace.value = true
    try {
      const restored = await workspace.load()
      if (!restored) {
        workspaceStale.value = false
        workspaceIssues.value = []
        return
      }
      await applyWorkspace(restored, currentVersion, effectiveSnapshot)
    } finally {
      restoringWorkspace.value = false
      await nextTick()
      lastWorkspaceSignature = workspaceSignature.value
      suppressAutosave.value = false
    }
  }

  /** Called after the effective configuration has been hydrated from the server. */
  async function loaded() {
    effectivePayloadJson.value = JSON.stringify(options.draft.payload())
    await restoreWorkspace()
  }

  /** Called after a successful effective save: the workspace is gone server-side. */
  function onEffectiveSaved() {
    suppressAutosave.value = true
    workspace.reset()
    aiDecisions.value = []
    aiRunId.value = null
    aiOutcomes.value = []
    void aiPanel.value?.loadAudits()
  }

  function saveMeta(): { runId: number | null; decisions: SaveDecision[] } {
    return { runId: aiRunId.value, decisions: aiDecisions.value }
  }

  async function keepLocalWorkspace() {
    workspace.adoptServerRevision()
    const result = await workspace.save(workspacePayload(), options.draft.baseVersion.value)
    if (result === 'SAVED') {
      lastWorkspaceSignature = workspaceSignature.value
    }
  }

  async function loadRemoteWorkspace() {
    const remote = workspaceConflict.value
    if (!remote) {
      return
    }
    const currentVersion = options.draft.baseVersion.value
    workspace.adoptServerRevision()
    restoringWorkspace.value = true
    try {
      await applyWorkspace(remote, currentVersion, effectivePayloadJson.value)
    } finally {
      restoringWorkspace.value = false
      await nextTick()
      lastWorkspaceSignature = workspaceSignature.value
    }
  }

  function reset() {
    workspace.reset()
    lastWorkspaceSignature = ''
    aiDecisions.value = []
    aiRunId.value = null
    aiOutcomes.value = []
    workspaceStale.value = false
    workspaceIssues.value = []
  }

  watch(workspaceSignature, () => {
    if (!suppressAutosave.value && !restoringWorkspace.value) {
      scheduleAutosave()
    }
  })

  onBeforeUnmount(() => {
    if (autosaveTimer) {
      clearTimeout(autosaveTimer)
    }
  })

  return {
    aiPanel,
    aiRunId,
    aiDecisions,
    aiOutcomes,
    aiOutcomeType,
    workspaceConflict,
    workspaceError,
    workspaceStale,
    workspaceIssues,
    onAiAdd,
    onEffectiveSaved,
    saveMeta,
    keepLocalWorkspace,
    loadRemoteWorkspace,
    loaded,
    reset,
  }
}
