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

describe('排课页的导入课表入口', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/semesters') return Promise.resolve({ data: [SEMESTER] })
      if (url === '/semesters/current') return Promise.resolve({ status: 200, data: SEMESTER })
      return Promise.resolve({ data: [] })
    })
  })

  it('进入 AI 导入页并默认整本工作簿目标', async () => {
    const { wrapper, router } = await mountScheduling()

    await button(wrapper, '导入课表').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/admin/ai-import')
    expect(router.currentRoute.value.query.target).toBeUndefined()
  })

  it('没有基础数据管理权限时不显示导入入口', async () => {
    const { wrapper } = await mountScheduling(['scheduling:run'])

    expect(wrapper.findAll('button').some((candidate) => candidate.text() === '导入课表')).toBe(false)
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
