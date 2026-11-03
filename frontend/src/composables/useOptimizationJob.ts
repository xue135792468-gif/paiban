import { getCurrentInstance, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { serverMessage } from '@/api/errors'
import {
  cancelOptimizationJob,
  getOptimizationJob,
  startOptimizationJob,
  type OptimizationJob,
  type StartOptimizationParams,
} from '@/api/scheduling'

export interface OptimizationJobClient {
  startOptimizationJob(params: StartOptimizationParams): Promise<OptimizationJob>
  getOptimizationJob(id: number): Promise<OptimizationJob>
  cancelOptimizationJob(id: number): Promise<OptimizationJob>
}

export interface OptimizationJobNotify {
  error(message: string): void
  success?(message: string): void
  info?(message: string): void
}

export interface UseOptimizationJobOptions {
  /** Injected for tests; production uses `api/scheduling`. */
  client?: OptimizationJobClient
  notify?: OptimizationJobNotify
  /** Same cadence as SchedulingView's job polling. */
  pollIntervalMs?: number
  onFinished?: (job: OptimizationJob) => void
}

const ACTIVE_STATUSES = new Set(['PENDING', 'RUNNING'])

export function isOptimizationActive(job: OptimizationJob | null | undefined): boolean {
  return job != null && ACTIVE_STATUSES.has(job.status)
}

export function isOptimizationTerminal(job: OptimizationJob | null | undefined): boolean {
  return job != null && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)
}

/**
 * One explicit-source scheduling job for the draft editor.
 *
 * This owns nothing but the job lifecycle: submit once, poll until a terminal
 * status, cancel, and resume by id. It deliberately never turns `COMPLETED`
 * into "publishable" — `resultPublishable` is only whatever the server sent.
 */
export function useOptimizationJob(options: UseOptimizationJobOptions = {}) {
  const client: OptimizationJobClient = options.client ?? {
    startOptimizationJob,
    getOptimizationJob,
    cancelOptimizationJob,
  }
  const notify = options.notify ?? ElMessage
  const intervalMs = options.pollIntervalMs ?? 1000

  const job = ref<OptimizationJob | null>(null)
  const jobId = ref<number | null>(null)
  const starting = ref(false)
  const cancelling = ref(false)
  const polling = ref(false)
  const error = ref<string | null>(null)

  let timer: ReturnType<typeof setInterval> | null = null
  let pollSeq = 0

  function applyJob(next: OptimizationJob) {
    job.value = next
    jobId.value = next.id
  }

  function stopPolling() {
    pollSeq += 1
    if (timer != null) {
      clearInterval(timer)
      timer = null
    }
    polling.value = false
  }

  function finishIfTerminal(next: OptimizationJob) {
    if (!isOptimizationTerminal(next)) return false
    stopPolling()
    options.onFinished?.(next)
    return true
  }

  function startPolling(id: number) {
    stopPolling()
    polling.value = true
    const seq = pollSeq
    timer = setInterval(async () => {
      if (seq !== pollSeq) return
      try {
        const next = await client.getOptimizationJob(id)
        if (seq !== pollSeq) return
        applyJob(next)
        finishIfTerminal(next)
      } catch (caught) {
        if (seq !== pollSeq) return
        stopPolling()
        error.value = serverMessage(caught, '读取优化任务状态失败')
      }
    }, intervalMs)
  }

  /**
   * Submits exactly once. A second click while the request is in flight, or while
   * a job is still PENDING/RUNNING, returns the existing job instead of starting
   * a duplicate task (which the server would reject as already running anyway).
   */
  async function start(params: StartOptimizationParams): Promise<OptimizationJob | null> {
    if (starting.value) return job.value
    if (isOptimizationActive(job.value)) return job.value
    starting.value = true
    error.value = null
    try {
      const created = await client.startOptimizationJob(params)
      applyJob(created)
      if (isOptimizationActive(created)) startPolling(created.id)
      else finishIfTerminal(created)
      return created
    } catch (caught) {
      error.value = serverMessage(caught, '发起继续优化失败')
      notify.error(error.value)
      return null
    } finally {
      starting.value = false
    }
  }

  /**
   * Restores an existing job after a page reload. This only reads: reopening a
   * `?job=` page must never submit another task.
   */
  async function resume(id: number): Promise<OptimizationJob | null> {
    stopPolling()
    error.value = null
    jobId.value = id
    try {
      const current = await client.getOptimizationJob(id)
      applyJob(current)
      if (isOptimizationActive(current)) startPolling(id)
      else finishIfTerminal(current)
      return current
    } catch (caught) {
      error.value = serverMessage(caught, '读取优化任务失败')
      return null
    }
  }

  async function cancel(): Promise<OptimizationJob | null> {
    if (!job.value || !isOptimizationActive(job.value) || cancelling.value) return job.value
    cancelling.value = true
    try {
      const updated = await client.cancelOptimizationJob(job.value.id)
      applyJob(updated)
      stopPolling()
      notify.success?.('已取消优化任务')
      return updated
    } catch (caught) {
      error.value = serverMessage(caught, '取消优化任务失败')
      notify.error(error.value)
      return null
    } finally {
      cancelling.value = false
    }
  }

  function reset() {
    stopPolling()
    job.value = null
    jobId.value = null
    error.value = null
  }

  // Unmounting the owning component always stops the timer; a plain unit test that
  // calls this composable outside a component just never registers the hook.
  if (getCurrentInstance()) onBeforeUnmount(stopPolling)

  return {
    job,
    jobId,
    starting,
    cancelling,
    polling,
    error,
    start,
    resume,
    cancel,
    reset,
    stopPolling,
  }
}
