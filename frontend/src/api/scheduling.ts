import { http } from './http'

/**
 * One scheduling job as `GET /scheduling-jobs/{id}` returns it (contracts §5.1).
 * The 07 fields are appended last; a legacy job simply leaves them null. The
 * order mirrors `SchedulingDtos.JobDto` so a shape change is easy to spot.
 */
export interface OptimizationJob {
  id: number
  semesterId: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string
  score?: string | null
  progress: number
  elapsedMs: number
  estimatedRemainingMs?: number
  errorMessage?: string | null
  draftId?: number | null
  draftName?: string | null
  warmStartSource?: 'BASELINE' | 'DRAFT' | 'NONE' | 'SOURCE_DRAFT' | string | null
  solveSeconds: number
  createdAt: string
  startedAt?: string | null
  finishedAt?: string | null
  /** The draft the user explicitly continued from; null for the legacy default source. */
  sourceDraftId?: number | null
  sourceDraftVersion?: number | null
  inheritedLockCount?: number | null
  /** Whole-draft verdict from the server; never derive publishing from the job status. */
  resultPublishable?: boolean | null
  resultBlockers?: string[] | null
}

export interface StartOptimizationParams {
  semesterId?: number
  /** Required here: the entry always continues from the concrete draft the user sees. */
  sourceDraftId: number
  draftName?: string
  maxSeconds?: number
}

/**
 * Starts an explicit-source job. `sourceDraftId` is what makes the new draft start
 * from the user's current arrangement and locks instead of the default warm start
 * (baseline / oldest draft / none).
 */
export async function startOptimizationJob(
  params: StartOptimizationParams,
): Promise<OptimizationJob> {
  const { data } = await http.post<OptimizationJob>(
    '/scheduling-jobs',
    {
      draftName: params.draftName,
      maxSeconds: params.maxSeconds,
      sourceDraftId: params.sourceDraftId,
    },
    { params: { semesterId: params.semesterId } },
  )
  return data
}

export async function getOptimizationJob(id: number): Promise<OptimizationJob> {
  const { data } = await http.get<OptimizationJob>(`/scheduling-jobs/${id}`)
  return data
}

export async function cancelOptimizationJob(id: number): Promise<OptimizationJob> {
  const { data } = await http.delete<OptimizationJob>(`/scheduling-jobs/${id}`)
  return data
}
