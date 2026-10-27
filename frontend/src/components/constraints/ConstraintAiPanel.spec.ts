import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@/api/http', () => ({ http: httpMock }))

import ConstraintAiPanel from './ConstraintAiPanel.vue'
import type { ParamDef, TypeDraft } from './types'

const PARAMS: ParamDef[] = [
  { name: 'teacherId', label: '教师', type: 'ID', required: true },
  { name: 'dayOfWeek', label: '星期', type: 'DAY', required: true },
  { name: 'periodNo', label: '第几节', type: 'PERIOD', required: true },
]

function row(): TypeDraft {
  return {
    code: 'TEACHER_UNAVAILABLE',
    name: '教师禁排时段',
    description: '',
    category: 'PREFERENCE',
    categoryLabel: '教师偏好',
    params: PARAMS,
    entries: [],
    enabled: true,
    hard: true,
    weight: 100,
  }
}

function mountPanel() {
  return mount(ConstraintAiPanel, {
    props: {
      semesterId: 1,
      types: [row()],
      teachers: [{ id: 1, name: '王明' }],
      courses: [],
      canReadAll: true,
    },
    global: { plugins: [ElementPlus] },
  })
}

function proposal(status: 'READY' | 'NEEDS_INPUT' = 'READY') {
  return {
    proposalId: 'p1',
    sourceText: '王老师周三下午不排课',
    type: 'TEACHER_UNAVAILABLE',
    typeName: '教师禁排时段',
    strength: 'HARD',
    effectiveHard: true,
    effectiveWeight: null,
    status,
    explanation: '周三下午全部禁排',
    reason: status === 'READY' ? '' : '同名教师需要选择',
    params: {},
    entries:
      status === 'READY'
        ? [
            { params: { teacherId: 1, dayOfWeek: 3, periodNo: 5 }, groupKey: 'g' },
            { params: { teacherId: 1, dayOfWeek: 3, periodNo: 6 }, groupKey: 'g' },
          ]
        : [],
    candidates:
      status === 'READY'
        ? []
        : [{ field: 'teacherId', label: '教师', options: [{ id: 1, name: '张伟' }, { id: 2, name: '张伟' }] }],
  }
}

describe('ConstraintAiPanel', () => {
  beforeEach(() => {
    httpMock.get.mockReset()
    httpMock.post.mockReset()
  })

  it('AI 不可用时保留入口并说明原因，不显示生成按钮', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/constraint-proposals/availability') {
        return Promise.resolve({
          data: {
            available: false,
            reason: 'FEATURE_DISABLED',
            message: '自然语言生成约束未开启，请在设置中开启；手动配置始终可用。',
          },
        })
      }
      return Promise.resolve({ data: [] })
    })
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('[data-testid="constraint-ai-unavailable"]').text()).toContain(
      '手动配置始终可用',
    )
    expect(wrapper.find('[data-testid="constraint-ai-generate"]').exists()).toBe(false)
  })

  it('生成后按状态分组展示建议，并明确当前只读', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/constraint-proposals/availability') {
        return Promise.resolve({ data: { available: true, reason: null, message: null } })
      }
      return Promise.resolve({ data: [] })
    })
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/constraint-proposals') {
        return Promise.resolve({
          data: {
            id: 7,
            clientRunId: 'run-1',
            semesterId: 1,
            status: 'SUCCEEDED',
            inputText: '王老师周三下午不排课',
            errorCode: null,
            errorMessage: null,
            providerName: 'stub',
            modelName: 'stub',
            inputTokens: 1,
            outputTokens: 1,
            latencyMs: 10,
            startedAt: '2026-10-27T10:00:00Z',
            finishedAt: '2026-10-27T10:00:01Z',
            proposals: [proposal('READY'), proposal('NEEDS_INPUT')],
          },
        })
      }
      return Promise.resolve({ data: {} })
    })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('textarea').setValue('王老师周三下午不排课')
    await wrapper.get('[data-testid="constraint-ai-generate"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="constraint-ai-counts"]').text()).toContain('2 条独立建议')
    expect(wrapper.get('[data-testid="constraint-ai-counts"]').text()).toContain('1 条可加入')
    const cards = wrapper.findAll('[data-testid="constraint-proposal"]')
    expect(cards).toHaveLength(2)
    expect(cards[0].findAll('[data-testid="constraint-proposal-entries"] li')).toHaveLength(2)
    expect(cards[1].get('[data-testid="constraint-proposal-reason"]').text()).toContain('同名教师')
    expect(cards[1].get('[data-testid="constraint-proposal-candidates"]').text()).toContain('张伟')
    expect(wrapper.get('[data-testid="constraint-ai-note"]').text()).toContain('只读建议')
    // No write action exists at this stage: the words only appear in the read-only hint.
    expect(wrapper.findAll('button').some((button) => button.text().includes('添加到约束表'))).toBe(
      false,
    )
  })

  it('生成记录可重新打开并查看完整原始输出', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/constraint-proposals/availability') {
        return Promise.resolve({ data: { available: true, reason: null, message: null } })
      }
      if (url === '/constraint-proposals') {
        return Promise.resolve({
          data: [
            {
              id: 7,
              status: 'SUCCEEDED',
              inputText: '王老师周三下午不排课',
              errorCode: null,
              errorMessage: null,
              startedAt: '2026-10-27T10:00:00Z',
              finishedAt: '2026-10-27T10:00:01Z',
              proposalCount: 1,
            },
          ],
        })
      }
      return Promise.resolve({
        data: {
          run: {
            id: 7,
            clientRunId: 'run-1',
            semesterId: 1,
            status: 'SUCCEEDED',
            inputText: '王老师周三下午不排课',
            errorCode: null,
            errorMessage: null,
            providerName: 'stub',
            modelName: 'stub',
            inputTokens: 1,
            outputTokens: 1,
            latencyMs: 10,
            startedAt: '2026-10-27T10:00:00Z',
            finishedAt: '2026-10-27T10:00:01Z',
            proposals: [],
          },
          rawResponse: '{"proposals":[]}',
          lateResponse: null,
          contextJson: '{}',
        },
      })
    })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('[data-testid="constraint-ai-history"] .el-collapse-item__header').trigger('click')
    await wrapper.get('[data-testid="constraint-ai-history-item"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="constraint-ai-raw"]').text()).toContain('proposals')
  })
})
