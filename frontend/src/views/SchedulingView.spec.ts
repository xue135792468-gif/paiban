import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import SchedulingView from './SchedulingView.vue'
import GradeScopePicker from '@/components/scheduling/GradeScopePicker.vue'
import { useAuthStore } from '@/stores/auth'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/api/http', () => ({ http: httpMock }))

const SEMESTER = { id: 1, name: '2025 秋季学期', current: true }

async function mountScheduling(
  permissions = ['scheduling:run', 'foundation:manage'],
): Promise<{ wrapper: VueWrapper; router: Router }> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore(pinia).user = {
    id: 1,
    username: 'scheduler',
    displayName: '排课员',
    enabled: true,
    roles: [],
    permissions,
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/scheduling', component: { template: '<div />' } },
      { path: '/admin/ai-import', component: { template: '<div />' } },
      { path: '/admin/drafts/:id', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/scheduling')
  await router.isReady()

  const wrapper = mount(SchedulingView, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text() === label)
  if (!found) throw new Error(`button not found: ${label}`)
  return found
}

describe('排课页布局与冲突展示', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      return Promise.resolve({ data: [] })
    })
  })

  it('不把跨模块导入入口混在排课操作中', async () => {
    const { wrapper } = await mountScheduling()
    expect(wrapper.text()).not.toContain('导入课表')
    expect(wrapper.text()).toContain('开始排课')
  })
})

describe('排课历史与草稿冲突摘要', () => {
  const draft = {
    id: 20,
    semesterId: 1,
    name: '方案 A',
    status: 'DRAFT',
    scoreHard: -2,
    scoreSoft: 0,
    createdAt: '2026-01-01T00:00:00Z',
    lessonCount: 10,
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  function stubData(drafts: unknown[], jobs: unknown[]) {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/timetable-drafts') return Promise.resolve({ data: drafts })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: jobs })
      return Promise.resolve({ data: [] })
    })
  }

  it('草稿表用白话显示冲突数，原得分保留在悬停说明', async () => {
    stubData([draft, { ...draft, id: 21, scoreHard: 0 }], [])
    const { wrapper } = await mountScheduling()
    expect(wrapper.text()).toContain('冲突情况')
    expect(wrapper.text()).toContain('2 处冲突')
    expect(wrapper.text()).toContain('无冲突')
    expect(wrapper.find('[title="-2 hard / 0 soft"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('没有草稿且最近一次失败时自动展开历史', async () => {
    stubData([], [{ id: 1, status: 'FAILED', draftName: '失败方案', createdAt: '2026-01-01T00:00:00Z' }])
    const { wrapper } = await mountScheduling()
    expect(wrapper.get('[data-testid="scheduling-history"]').isVisible()).toBe(true)
    expect(wrapper.text()).toContain('失败方案')
    wrapper.unmount()
  })

  it('有草稿时历史默认折叠，用户可以展开', async () => {
    stubData(
      [{ ...draft, scoreHard: 0 }],
      [{ id: 1, status: 'COMPLETED', draftName: '方案 A', createdAt: '2026-01-01T00:00:00Z' }],
    )
    const { wrapper } = await mountScheduling()
    expect(wrapper.get('[data-testid="scheduling-history"]').isVisible()).toBe(false)
    await wrapper.get('[data-testid="toggle-scheduling-history"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="toggle-scheduling-history"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-testid="scheduling-history"]').attributes('style')).not.toContain(
      'display: none',
    )
    wrapper.unmount()
  })
})

describe('按年级排课', () => {
  const GRADES = [
    { id: 1, name: '高一' },
    { id: 2, name: '高二' },
    { id: 3, name: '高三' },
  ]
  const completedJob = {
    id: 7,
    semesterId: 1,
    status: 'COMPLETED',
    score: '0hard/0soft',
    progress: 100,
    elapsedMs: 1000,
    estimatedRemainingMs: 0,
    errorMessage: null,
    draftId: null,
    draftName: '方案 A',
    warmStartSource: 'SOURCE_DRAFT',
    solveSeconds: 60,
    createdAt: '2026-01-01T00:00:00Z',
    gradeIds: [1],
    diagnostics: [],
  }

  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.post.mockResolvedValue({ data: completedJob })
  })

  it('默认全选年级，并把 gradeIds 一起提交', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/grades') return Promise.resolve({ data: GRADES })
      return Promise.resolve({ data: [] })
    })

    const { wrapper } = await mountScheduling()

    expect(wrapper.text()).toContain('本次排全部 3 个年级')
    await button(wrapper, '开始排课').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith(
      '/scheduling-jobs',
      expect.objectContaining({ gradeIds: [1, 2, 3], maxSeconds: 60 }),
      expect.anything(),
    )
    wrapper.unmount()
  })

  it('诊断按钮用选中年级与诊断年级的并集发起新任务', async () => {
    const failedScopeJob = {
      ...completedJob,
      resultPublishable: false,
      diagnostics: [
        {
          teacherId: 9,
          teacherName: '张老师',
          hardCount: 2,
          messages: ['张老师 在 周一第1节 不排课'],
          gradeIds: [1, 3],
          classNames: ['高一1班', '高三1班'],
        },
      ],
    }
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/grades') return Promise.resolve({ data: GRADES })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: [failedScopeJob] })
      return Promise.resolve({ data: [] })
    })

    const { wrapper } = await mountScheduling()
    expect(wrapper.text()).toContain('张老师')

    // Narrow the scope first so the union (selected [1] ∪ diagnostics [1,3]) is visible.
    wrapper.findComponent(GradeScopePicker).vm.$emit('update:modelValue', [1])
    await flushPromises()
    await button(wrapper, '连带重排这些年级').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith(
      '/scheduling-jobs',
      expect.objectContaining({ gradeIds: [1, 3], maxSeconds: 60 }),
      expect.anything(),
    )
    wrapper.unmount()
  })
})

describe('空白学期的 warm start 提示', () => {
  const runningJob = {
    id: 1,
    semesterId: 1,
    status: 'RUNNING',
    score: null,
    progress: 10,
    elapsedMs: 1000,
    estimatedRemainingMs: 5000,
    errorMessage: null,
    draftId: null,
    draftName: '方案 A',
    warmStartSource: 'NONE',
    solveSeconds: 60,
    createdAt: '2026-01-01T00:00:00Z',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: [runningJob] })
      if (url === '/scheduling-jobs/1') return Promise.resolve({ data: runningJob })
      return Promise.resolve({ data: [] })
    })
  })

  it('来源为 NONE 时显示信息性提示', async () => {
    const { wrapper } = await mountScheduling()

    expect(wrapper.text()).toContain('本次将从零搜索')
    wrapper.unmount()
  })

  it('来源为 BASELINE 时不显示提示', async () => {
    const baselineJob = { ...runningJob, warmStartSource: 'BASELINE' }
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: [baselineJob] })
      if (url === '/scheduling-jobs/1') return Promise.resolve({ data: baselineJob })
      return Promise.resolve({ data: [] })
    })

    const { wrapper } = await mountScheduling()

    expect(wrapper.text()).not.toContain('本次将从零搜索')
    wrapper.unmount()
  })

  it('来源为 DRAFT 时不显示提示', async () => {
    const draftJob = { ...runningJob, warmStartSource: 'DRAFT' }
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: [draftJob] })
      if (url === '/scheduling-jobs/1') return Promise.resolve({ data: draftJob })
      return Promise.resolve({ data: [] })
    })

    const { wrapper } = await mountScheduling()

    expect(wrapper.text()).not.toContain('本次将从零搜索')
    wrapper.unmount()
  })
})

describe('草稿删除入口', () => {
  const draftRow = (status: string, name: string) => ({
    id: 11,
    name,
    status,
    scoreHard: 0,
    scoreSoft: 0,
    lessonCount: 6,
    lockedCount: 0,
    createdAt: '2026-01-01T00:00:00Z',
  })

  it('只有 DRAFT 草稿的删除按钮可用，已发布/已归档改为提示先撤回', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/timetable-drafts') {
        return Promise.resolve({
          data: [
            draftRow('DRAFT', '可删草稿'),
            draftRow('PUBLISHED', '已发布草稿'),
            draftRow('ARCHIVED', '已归档草稿'),
          ],
        })
      }
      return Promise.resolve({ data: [] })
    })

    const { wrapper } = await mountScheduling(['scheduling:run', 'timetable:edit'])
    const buttons = wrapper.findAll('[data-testid="draft-delete"]')
    expect(buttons).toHaveLength(3)
    const disabled = buttons.map((button) => button.attributes('disabled') !== undefined)
    expect(disabled).toEqual([false, true, true])
    wrapper.unmount()
  })
})

describe('F-01 / F-02 / U-03：前置条件、局部重排拦截与提交失败状态', () => {
  const GRADES = [
    { id: 1, name: '高一' },
    { id: 2, name: '高二' },
  ]

  function stubBaseData(options: {
    assignments?: unknown[]
    periods?: unknown[]
    drafts?: unknown[]
    jobs?: unknown[]
  }) {
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      if (url === '/grades') return Promise.resolve({ data: GRADES })
      if (url === '/assignments') return Promise.resolve({ data: options.assignments ?? [{ id: 1 }] })
      if (url === '/semesters/1/periods') return Promise.resolve({ data: options.periods ?? [{ id: 1 }] })
      if (url === '/timetable-drafts') return Promise.resolve({ data: options.drafts ?? [] })
      if (url === '/scheduling-jobs') return Promise.resolve({ data: options.jobs ?? [] })
      return Promise.resolve({ data: [] })
    })
  }

  it('U-03：无草稿但已有授课任务和作息表时，不显示缺少授课任务/作息表提示', async () => {
    vi.clearAllMocks()
    stubBaseData({ drafts: [], assignments: [{ id: 1 }], periods: [{ id: 1 }] })

    const { wrapper } = await mountScheduling()
    expect(wrapper.find('[data-testid="prerequisite-hint"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('U-03：仅当真正缺少授课任务或作息表时才显示对应提示', async () => {
    vi.clearAllMocks()
    stubBaseData({ drafts: [], assignments: [], periods: [{ id: 1 }] })

    const { wrapper } = await mountScheduling()
    expect(wrapper.get('[data-testid="prerequisite-hint"]').text()).toContain('请先录入授课任务')
    expect(wrapper.get('[data-testid="prerequisite-hint"]').text()).not.toContain('作息表')
    wrapper.unmount()
  })

  it('F-01：无已发布课表时选部分年级禁用开始排课，点击全选后恢复可用', async () => {
    vi.clearAllMocks()
    stubBaseData({ drafts: [] })

    const { wrapper } = await mountScheduling()
    wrapper.findComponent(GradeScopePicker).vm.$emit('update:modelValue', [1])
    await flushPromises()

    expect(wrapper.get('[data-testid="partial-scope-warning"]').text()).toContain('其他年级尚未发布课表')
    expect(button(wrapper, '开始排课').attributes('disabled')).toBeDefined()

    await button(wrapper, '全选全部年级').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="partial-scope-warning"]').exists()).toBe(false)
    expect(button(wrapper, '开始排课').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('F-01 & F-02：新提交 409 失败时清掉之前的已完成卡片并展示提交失败提示', async () => {
    vi.clearAllMocks()
    stubBaseData({
      drafts: [
        {
          id: 20,
          semesterId: 1,
          name: '旧方案',
          status: 'DRAFT',
          scoreHard: -22,
          scoreSoft: 0,
          createdAt: '2026-01-01T00:00:00Z',
          lessonCount: 10,
        },
      ],
    })
    httpMock.post.mockResolvedValueOnce({
      data: {
        id: 7,
        semesterId: 1,
        status: 'COMPLETED',
        score: '-22hard/0soft',
        progress: 100,
        elapsedMs: 1000,
        draftId: 20,
        draftName: '旧方案',
        solveSeconds: 60,
        createdAt: '2026-01-01T00:00:00Z',
        gradeIds: [1, 2],
        diagnostics: [],
      },
    })

    const { wrapper } = await mountScheduling()
    await button(wrapper, '开始排课').trigger('click')
    await flushPromises()

    // F-02: 有硬冲突时展示警告态
    expect(wrapper.get('[data-testid="active-job-status"]').text()).toBe('已完成，但存在冲突暂不可发布')

    // F-01: 下一次提交 409 失败时，不能继续显示旧的已完成卡片和打开草稿按钮
    httpMock.post.mockRejectedValueOnce({
      response: {
        status: 409,
        data: { message: '其他年级还没有已排课表，无法保持不动' },
      },
    })
    await button(wrapper, '开始排课').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="active-job-card"]').exists()).toBe(false)
    const errCard = wrapper.get('[data-testid="job-submit-error"]')
    expect(errCard.text()).toContain('其他年级还没有已排课表，无法保持不动')
    expect(errCard.text()).not.toContain('打开草稿')
    wrapper.unmount()
  })
})
