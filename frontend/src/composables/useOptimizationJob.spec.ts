import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import {
  isOptimizationActive,
  isOptimizationTerminal,
  useOptimizationJob,
  type OptimizationJobClient,
} from './useOptimizationJob'
import type { OptimizationJob, StartOptimizationParams } from '@/api/scheduling'

function job(overrides: Partial<OptimizationJob> = {}): OptimizationJob {
  return {
    id: 7,
    semesterId: 3,
    status: 'RUNNING',
    progress: 10,
    elapsedMs: 1000,
    solveSeconds: 60,
    createdAt: '2026-11-02T00:00:00Z',
    ...overrides,
  }
}

function fakeClient(overrides: Partial<OptimizationJobClient> = {}) {
  const starts: StartOptimizationParams[] = []
  const gets: number[] = []
  const deletes: number[] = []
  let current = job()
  const client: OptimizationJobClient = {
    startOptimizationJob: vi.fn(async (params: StartOptimizationParams) => {
      starts.push(params)
      return current
    }),
    getOptimizationJob: vi.fn(async (id: number) => {
      gets.push(id)
      return current
    }),
    cancelOptimizationJob: vi.fn(async (id: number) => {
      deletes.push(id)
      current = job({ id, status: 'CANCELLED' })
      return current
    }),
    ...overrides,
  }
  return {
    client,
    starts,
    gets,
    deletes,
    setCurrent(next: OptimizationJob) {
      current = next
    },
  }
}

describe('useOptimizationJob', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('start 请求体带 sourceDraftId、学期、名称与秒数', async () => {
    const fake = fakeClient()
    const optimization = useOptimizationJob({ client: fake.client, pollIntervalMs: 1000 })

    await optimization.start({
      semesterId: 3,
      sourceDraftId: 42,
      draftName: '基于「原稿」优化',
      maxSeconds: 30,
    })

    expect(fake.starts).toEqual([
      { semesterId: 3, sourceDraftId: 42, draftName: '基于「原稿」优化', maxSeconds: 30 },
    ])
    expect(optimization.jobId.value).toBe(7)
    expect(isOptimizationActive(optimization.job.value)).toBe(true)
    optimization.stopPolling()
  })

  it('已有运行任务时重复 start 不再提交请求', async () => {
    const fake = fakeClient()
    const optimization = useOptimizationJob({ client: fake.client })

    await optimization.start({ semesterId: 3, sourceDraftId: 42 })
    await optimization.start({ semesterId: 3, sourceDraftId: 42 })

    expect(fake.starts).toHaveLength(1)
    optimization.stopPolling()
  })

  it('轮询到 COMPLETED 停止，并保留 draftId/resultPublishable/resultBlockers', async () => {
    const fake = fakeClient()
    const optimization = useOptimizationJob({ client: fake.client, pollIntervalMs: 1000 })
    await optimization.start({ semesterId: 3, sourceDraftId: 42 })

    fake.setCurrent(
      job({
        status: 'COMPLETED',
        progress: 100,
        draftId: 88,
        resultPublishable: false,
        resultBlockers: ['有 2 节课未排课', '缺教室'],
      }),
    )
    await vi.advanceTimersByTimeAsync(1000)
    await nextTick()

    expect(optimization.job.value?.status).toBe('COMPLETED')
    expect(optimization.job.value?.draftId).toBe(88)
    expect(optimization.job.value?.resultPublishable).toBe(false)
    expect(optimization.job.value?.resultBlockers).toEqual(['有 2 节课未排课', '缺教室'])
    expect(optimization.polling.value).toBe(false)

    const before = fake.gets.length
    await vi.advanceTimersByTimeAsync(3000)
    expect(fake.gets).toHaveLength(before)
  })

  it('FAILED/CANCELLED 停止轮询并保留错误', async () => {
    const fake = fakeClient()
    const optimization = useOptimizationJob({ client: fake.client, pollIntervalMs: 1000 })
    await optimization.start({ semesterId: 3, sourceDraftId: 42 })

    fake.setCurrent(job({ status: 'FAILED', errorMessage: '授课任务已变更' }))
    await vi.advanceTimersByTimeAsync(1000)
    await nextTick()

    expect(isOptimizationTerminal(optimization.job.value)).toBe(true)
    expect(optimization.job.value?.errorMessage).toBe('授课任务已变更')
    expect(optimization.polling.value).toBe(false)
  })

  it('cancel 走 DELETE 并停止轮询', async () => {
    const fake = fakeClient()
    const optimization = useOptimizationJob({ client: fake.client, pollIntervalMs: 1000 })
    await optimization.start({ semesterId: 3, sourceDraftId: 42 })

    await optimization.cancel()

    expect(fake.deletes).toEqual([7])
    expect(optimization.job.value?.status).toBe('CANCELLED')
    expect(optimization.polling.value).toBe(false)
    expect(fake.gets).toHaveLength(0)
  })

  it('resume 只读取、不重复 start', async () => {
    const fake = fakeClient()
    const optimization = useOptimizationJob({ client: fake.client, pollIntervalMs: 1000 })

    const restored = await optimization.resume(7)

    expect(restored?.status).toBe('RUNNING')
    expect(fake.starts).toHaveLength(0)
    expect(fake.gets).toEqual([7])
    expect(optimization.polling.value).toBe(true)

    optimization.stopPolling()
    expect(optimization.polling.value).toBe(false)
  })

  it('start 失败显示服务端原因且不进入轮询', async () => {
    const fake = fakeClient({
      startOptimizationJob: vi.fn(async () => {
        throw { response: { data: { message: '全部课节已锁定，没有可优化的课程' } } }
      }),
    })
    const notify = { error: vi.fn() }
    const optimization = useOptimizationJob({ client: fake.client, notify })

    const created = await optimization.start({ semesterId: 3, sourceDraftId: 42 })

    expect(created).toBeNull()
    expect(optimization.error.value).toBe('全部课节已锁定，没有可优化的课程')
    expect(notify.error).toHaveBeenCalledWith('全部课节已锁定，没有可优化的课程')
    expect(optimization.polling.value).toBe(false)
  })
})
