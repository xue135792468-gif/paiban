import { ref, type Ref } from 'vue'
import { http } from '@/api/http'
import { serverMessage } from '@/api/errors'
import type { ConstraintConfigRequest } from './types'
import type { ConstraintProposal, SaveDecision } from './useConstraintAi'

/**
 * The unfinished work of this account in this semester (ticket 14/11): the idea text,
 * the run, manual completions/selections, the unsaved table configuration and the
 * effective version it started from. It is never applied, never crosses accounts, and
 * a successful save removes it.
 */
export interface WorkspacePayload {
  text?: string
  runId?: number | null
  selectedProposalIds?: string[]
  resolved?: Record<string, ConstraintProposal>
  decisions?: SaveDecision[]
  configs?: ConstraintConfigRequest[]
  isJunior?: boolean
}

export interface WorkspaceDto {
  exists: boolean
  revision: number
  baseVersion: number
  payload: WorkspacePayload
  updatedAt: string | null
  errors: string[]
  invalidReferences: unknown[]
}

export type WorkspaceSaveResult = 'SAVED' | 'CONFLICT' | 'FAILED'

export function useConstraintWorkspace(options: { semesterId: Ref<number | null> }) {
  const revision = ref(0)
  const loading = ref(false)
  /** A newer autosave from the same account; the local draft stays untouched. */
  const conflict = ref<WorkspaceDto | null>(null)
  const error = ref<string | null>(null)
  const saving = ref(false)

  async function load(): Promise<WorkspaceDto | null> {
    if (!options.semesterId.value) {
      return null
    }
    loading.value = true
    error.value = null
    try {
      const { data } = await http.get<WorkspaceDto>('/constraint-workspace', {
        params: { semesterId: options.semesterId.value },
      })
      revision.value = data.exists ? data.revision : 0
      conflict.value = null
      return data.exists ? data : null
    } catch (e: any) {
      error.value = serverMessage(e, '读取未保存工作失败')
      return null
    } finally {
      loading.value = false
    }
  }

  /**
   * Autosave based on the revision this device holds. A 409 carries the other device's
   * copy; the caller keeps the local draft and asks the user which one to continue.
   */
  async function save(
    payload: WorkspacePayload,
    baseVersion: number,
  ): Promise<WorkspaceSaveResult> {
    if (!options.semesterId.value) {
      return 'FAILED'
    }
    saving.value = true
    error.value = null
    try {
      const { data } = await http.put<WorkspaceDto>(
        '/constraint-workspace',
        { baseRevision: revision.value, baseVersion, payload },
        { params: { semesterId: options.semesterId.value } },
      )
      revision.value = data.revision
      conflict.value = null
      return 'SAVED'
    } catch (e: any) {
      if (e?.response?.status === 409) {
        const latest = e.response.data?.workspace
        if (latest) {
          conflict.value = latest as WorkspaceDto
        }
        return 'CONFLICT'
      }
      // The page keeps its content; cross-device restore simply did not update.
      error.value = serverMessage(e, '未保存工作暂存失败，跨设备恢复尚未更新')
      return 'FAILED'
    } finally {
      saving.value = false
    }
  }

  /** The user chose to overwrite the other device: continue from its revision. */
  function adoptServerRevision() {
    if (conflict.value) {
      revision.value = conflict.value.revision
      conflict.value = null
    }
  }

  async function discard(): Promise<void> {
    if (!options.semesterId.value) {
      return
    }
    try {
      await http.delete('/constraint-workspace', {
        params: { semesterId: options.semesterId.value },
      })
    } catch {
      // Best effort: the next save deletes it as well.
    } finally {
      revision.value = 0
      conflict.value = null
    }
  }

  /** After an effective save the workspace is gone; this device starts from zero. */
  function reset() {
    revision.value = 0
    conflict.value = null
    error.value = null
  }

  return {
    revision,
    loading,
    saving,
    conflict,
    error,
    load,
    save,
    adoptServerRevision,
    discard,
    reset,
  }
}
