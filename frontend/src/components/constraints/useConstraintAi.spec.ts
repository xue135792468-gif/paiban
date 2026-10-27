import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@/api/http', () => ({ http: httpMock }))

import { useConstraintAi, type GenerationRun } from './useConstraintAi'

function run(overrides: Partial<GenerationRun> = {}): GenerationRun {
  return {
    id: 7,
    clientRunId: 'run-1',
    semesterId: 1,
    status: 'SUCCEEDED',
    inputText: '王老师周三下午不排课',
    errorCode: null,
    errorMessage: null,
    providerName: 'stub',
    modelName: 'stub-model',
    inputTokens: 10,
    outputTokens: 20,
    latencyMs: 100,
    startedAt: '2026-10-27T10:00:00Z',
    finishedAt: '2026-10-27T10:00:01Z',
    proposals: [],
    ...overrides,
  }
}

describe('useConstraintAi', () => {
  beforeEach(() => {
    httpMock.get.mockReset()
    httpMock.post.mockReset()
  })

  it('读取不可用原因，不阻止页面继续手动配置', async () => {
    httpMock.get.mockResolvedValue({
      data: {
        available: false,
        reason: 'NOT_CONFIGURED',
        message: '还没有可用的 AI 供应商与模型',
      },
    })
    const ai = useConstraintAi({ semesterId: ref(1) })
    await ai.checkAvailability()
    expect(ai.availability.value.available).toBe(false)
    expect(ai.availability.value.reason).toBe('NOT_CONFIGURED')
    expect(ai.availability.value.message).toContain('AI 供应商')
  })

  it('生成时带上原话和一次点击的稳定标识，并统计三种建议状态', async () => {
    httpMock.get.mockResolvedValue({
      data: { available: true, reason: null, message: null },
    })
    httpMock.post.mockResolvedValue({
      data: run({
        proposals: [
          {
            proposalId: 'p1',
            sourceText: '王老师周三下午不排课',
            type: 'TEACHER_UNAVAILABLE',
            typeName: '教师禁排时段',
            strength: 'HARD',
            effectiveHard: true,
            effectiveWeight: null,
            status: 'READY',
            explanation: '周三下午全部禁排',
            reason: '',
            params: {},
            entries: [],
            candidates: [],
          },
          {
            proposalId: 'p2',
            sourceText: '张老师周三不排',
            type: 'TEACHER_UNAVAILABLE',
            typeName: '教师禁排时段',
            strength: 'HARD',
            effectiveHard: true,
            effectiveWeight: null,
            status: 'NEEDS_INPUT',
            explanation: '',
            reason: '同名教师需要选择',
            params: {},
            entries: [],
            candidates: [],
          },
        ],
      }),
    })
    // The history reload after a run.
    httpMock.get.mockResolvedValue({ data: [] })

    const ai = useConstraintAi({ semesterId: ref(1) })
    ai.text.value = '王老师周三下午不排课'
    await ai.generate()

    expect(httpMock.post).toHaveBeenCalledWith(
      '/constraint-proposals',
      expect.objectContaining({
        clientRunId: expect.any(String),
        text: '王老师周三下午不排课',
      }),
      { params: { semesterId: 1 }, timeout: 65000 },
    )
    expect(ai.run.value?.status).toBe('SUCCEEDED')
    expect(ai.counts.value).toEqual({ total: 2, ready: 1, needsInput: 1, unsupported: 0 })
  })

  it('失败时保留原话并显示服务端原因，不自动重试', async () => {
    httpMock.get.mockResolvedValue({ data: [] })
    httpMock.post.mockResolvedValue({
      data: run({ status: 'TIMEOUT', errorCode: 'TIMEOUT', errorMessage: 'AI 调用超时（60 秒）' }),
    })
    const ai = useConstraintAi({ semesterId: ref(1) })
    ai.text.value = '王老师周三不排课'
    await ai.generate()

    expect(ai.text.value).toBe('王老师周三不排课')
    expect(ai.run.value?.status).toBe('TIMEOUT')
    expect(httpMock.post).toHaveBeenCalledTimes(1)
  })

  it('取消只更新当前运行，晚到的结果不覆盖页面', async () => {
    httpMock.get.mockResolvedValue({ data: [] })
    httpMock.post.mockResolvedValue({
      data: run({ status: 'CANCELLED', proposals: [] }),
    })
    const ai = useConstraintAi({ semesterId: ref(1) })
    ai.run.value = run({ status: 'RUNNING', proposals: [] })
    await ai.cancel()

    expect(httpMock.post).toHaveBeenCalledWith('/constraint-proposals/7/cancel')
    expect(ai.run.value?.status).toBe('CANCELLED')
    expect(ai.proposals.value).toEqual([])
  })

  it('生成请求尚未返回时，用本次点击标识取消', async () => {
    httpMock.get.mockResolvedValue({ data: [] })
    let resolveGenerate: ((value: unknown) => void) | null = null
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/constraint-proposals') {
        return new Promise((resolve) => {
          resolveGenerate = resolve
        })
      }
      return Promise.resolve({ data: run({ status: 'CANCELLED', proposals: [] }) })
    })
    const ai = useConstraintAi({ semesterId: ref(1) })
    ai.text.value = '王老师周三不排课'
    const pending = ai.generate()
    await Promise.resolve()

    await ai.cancel()
    expect(httpMock.post).toHaveBeenCalledWith('/constraint-proposals/cancel', null, {
      params: { clientRunId: expect.any(String) },
    })
    expect(ai.run.value?.status).toBe('CANCELLED')

    ;(resolveGenerate as unknown as (value: unknown) => void)({ data: run() })
    await pending
  })

  it('恢复工作区时载入原话、选中项和补齐结果，不调用模型', async () => {
    httpMock.get.mockResolvedValue({
      data: {
        run: run({
          proposals: [
            {
              proposalId: 'p1',
              sourceText: '张老师周三不排',
              type: 'TEACHER_UNAVAILABLE',
              typeName: '教师禁排时段',
              strength: 'HARD',
              effectiveHard: true,
              effectiveWeight: null,
              status: 'NEEDS_INPUT',
              explanation: '',
              reason: '同名',
              params: {},
              entries: [],
              candidates: [],
            },
          ],
        }),
        rawResponse: '{}',
        modelContent: '{}',
        lateResponse: null,
        contextJson: '{}',
      },
    })
    const ai = useConstraintAi({ semesterId: ref(1) })
    await ai.restoreState({
      text: '改过的原话',
      runId: 7,
      selectedProposalIds: ['p1'],
      resolved: {
        p1: {
          proposalId: 'p1',
          sourceText: '张老师周三不排',
          type: 'TEACHER_UNAVAILABLE',
          typeName: '教师禁排时段',
          strength: 'SOFT',
          effectiveHard: false,
          effectiveWeight: 40,
          status: 'READY',
          explanation: '',
          reason: '',
          params: {},
          entries: [{ params: { teacherId: 3, dayOfWeek: 3, periodNo: 1 } }],
          candidates: [],
        },
      },
    })

    expect(ai.text.value).toBe('改过的原话')
    expect(ai.selectedIds.value).toEqual(['p1'])
    expect(ai.proposals.value[0].status).toBe('READY')
    expect(ai.selectedProposals.value).toHaveLength(1)
    expect(httpMock.post).not.toHaveBeenCalled()
    expect(ai.workspaceState()).toMatchObject({
      text: '改过的原话',
      runId: 7,
      selectedProposalIds: ['p1'],
    })
  })

  it('打开本人记录时载入原始输出，供核对', async () => {
    httpMock.get.mockResolvedValue({
      data: {
        run: run(),
        rawResponse: '{"proposals":[]}',
        lateResponse: null,
        contextJson: '{"teachers":[]}',
      },
    })
    const ai = useConstraintAi({ semesterId: ref(1) })
    await ai.openRun(7)

    expect(httpMock.get).toHaveBeenCalledWith('/constraint-proposals/7')
    expect(ai.detail.value?.rawResponse).toBe('{"proposals":[]}')
    expect(ai.text.value).toBe('王老师周三下午不排课')
  })
})
