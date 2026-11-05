import { describe, expect, it, vi } from 'vitest'
import { useTeacherSwap } from './useTeacherSwap'
import { useSwapFlow } from './useSwapFlow'
import type { useDraftAdjust } from './useDraftAdjust'
import type {
  DraftLesson,
  SwapCandidate,
  SwapCandidatesResponse,
} from '@/components/draft/types'

function lesson(id: number, overrides: Partial<DraftLesson> = {}): DraftLesson {
  return {
    id,
    assignmentId: id,
    courseId: id,
    courseName: `课程${id}`,
    teacherId: 10,
    teacherName: `教师${id}`,
    classId: 1,
    studentCount: 40,
    periodId: 100 + id,
    dayOfWeek: 1,
    periodNo: id,
    locked: false,
    ...overrides,
  }
}

function candidate(overrides: Partial<SwapCandidate> = {}): SwapCandidate {
  return {
    lessonId: 2,
    courseId: 2,
    courseName: '语文',
    teacherId: 10,
    teacherName: '李老师',
    classId: 1,
    className: '高一1班',
    periodId: 102,
    available: true,
    conflicts: [],
    warnings: [],
    ...overrides,
  }
}

const source = lesson(1, { courseName: '数学' })
const green = lesson(2, { courseName: '语文' })
const blocked = lesson(3, { courseName: '英语', periodNo: 3 })
const foreign = lesson(4, { classId: 2, periodNo: 4 })
const unscheduled = lesson(5, { classId: 1, periodId: null, periodNo: null, dayOfWeek: null })
const lessons = [source, green, blocked, foreign, unscheduled]

const greenCandidate = candidate({
  lessonId: green.id,
  className: '高一1班',
  courseName: '语文',
  teacherName: '李老师',
})
const blockedCandidate = candidate({
  lessonId: blocked.id,
  courseName: '英语',
  available: false,
  conflicts: [
    {
      type: 'TEACHER_CONFLICT',
      message: '李老师 此时在 高一2班 有 英语课（周一第2节）',
      lessonId: blocked.id,
    },
  ],
})

function response(overrides: Partial<SwapCandidatesResponse> = {}): SwapCandidatesResponse {
  return {
    lessonId: source.id,
    intraClass: [greenCandidate, blockedCandidate],
    crossClass: [],
    ...overrides,
  }
}

function fakeAdjust(reply: SwapCandidatesResponse | null = response()) {
  return {
    loadSwapCandidates: vi.fn(async () => reply),
    swap: vi.fn(async () => ({
      saved: true,
      type: 'INTRA_CLASS' as const,
      conflicts: [],
      warnings: [],
    })),
  } as unknown as ReturnType<typeof useDraftAdjust>
}

function fakeNotify() {
  return { warning: vi.fn(), error: vi.fn() }
}

describe('useTeacherSwap', () => {
  it('进入换课模式只请求同班候选（includeUnavailable）', async () => {
    const adjust = fakeAdjust()
    const flow = useTeacherSwap({ adjust, getLessons: () => lessons, notify: fakeNotify() })

    await flow.enter(source)

    expect(adjust.loadSwapCandidates).toHaveBeenCalledTimes(1)
    expect(adjust.loadSwapCandidates).toHaveBeenCalledWith(source.id, true, 'INTRA_CLASS')
    expect(flow.active.value).toBe(true)
    const calls = vi.mocked(adjust.loadSwapCandidates).mock.calls
    expect(calls.some((call) => call[2] === 'CROSS_CLASS')).toBe(false)
  })

  it('同班可换为绿、同班不可换带原因、不同班与空格为灰', async () => {
    const flow = useTeacherSwap({
      adjust: fakeAdjust(),
      getLessons: () => lessons,
      notify: fakeNotify(),
    })

    await flow.enter(source)

    expect(flow.targets.value[source.id]).toMatchObject({ status: 'source' })
    expect(flow.targets.value[green.id]).toMatchObject({
      status: 'swappable',
      reason: '和高一1班的语文互换，李老师',
    })
    expect(flow.targets.value[green.id].candidate?.lessonId).toBe(green.id)
    expect(flow.targets.value[blocked.id]).toMatchObject({
      status: 'blocked',
      reason: '李老师 此时在 高一2班 有 英语课（周一第2节）',
    })
    expect(flow.targets.value[foreign.id]).toMatchObject({
      status: 'foreign',
      reason: '不同班，只能同班换课',
    })
    expect(flow.targets.value[unscheduled.id]).toMatchObject({
      status: 'blocked',
      reason: '没有可换的课',
    })
  })

  it('候选加载中与加载失败时同班格子给出明确状态', async () => {
    let resolve: ((value: SwapCandidatesResponse | null) => void) | undefined
    const pending = new Promise<SwapCandidatesResponse | null>((done) => {
      resolve = done
    })
    const adjust = {
      loadSwapCandidates: vi.fn(() => pending),
      swap: vi.fn(),
    } as unknown as ReturnType<typeof useDraftAdjust>
    const flow = useTeacherSwap({ adjust, getLessons: () => lessons, notify: fakeNotify() })

    const entering = flow.enter(source)
    expect(flow.targets.value[green.id]).toMatchObject({ status: 'checking' })

    resolve?.(null)
    await entering
    expect(flow.targets.value[green.id]).toMatchObject({
      status: 'blocked',
      reason: '候选加载失败，请重试',
    })
  })

  it('再次点击同一节课退出换课模式', async () => {
    const flow = useTeacherSwap({
      adjust: fakeAdjust(),
      getLessons: () => lessons,
      notify: fakeNotify(),
    })
    await flow.enter(source)

    await flow.toggle(source)

    expect(flow.active.value).toBe(false)
    expect(flow.targets.value).toEqual({})
  })

  it('锁定或未排入节次的课节不进入换课模式、不发请求', async () => {
    const adjust = fakeAdjust()
    const flow = useTeacherSwap({ adjust, getLessons: () => lessons, notify: fakeNotify() })

    expect(await flow.enter(lesson(9, { locked: true }))).toBe(false)
    expect(await flow.enter(lesson(9, { periodId: null }))).toBe(false)

    expect(adjust.loadSwapCandidates).not.toHaveBeenCalled()
    expect(flow.active.value).toBe(false)
  })

  it('确认换课调用同一个 swap 接口并带 expectedVersion，成功后退出', async () => {
    const adjust = fakeAdjust()
    const reload = vi.fn(async () => undefined)
    const flow = useTeacherSwap({
      adjust,
      getLessons: () => lessons,
      getVersion: () => 9,
      reload,
      notify: fakeNotify(),
    })
    await flow.enter(source)
    flow.openPreview(greenCandidate)

    expect(flow.previewVisible.value).toBe(true)
    const outcome = await flow.confirm()

    expect(outcome.saved).toBe(true)
    expect(adjust.swap).toHaveBeenCalledWith(source, greenCandidate, 9)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(flow.active.value).toBe(false)
  })

  it('不可换的灰格不能打开预览', async () => {
    const flow = useTeacherSwap({
      adjust: fakeAdjust(),
      getLessons: () => lessons,
      notify: fakeNotify(),
    })
    await flow.enter(source)

    expect(flow.openPreview(blockedCandidate)).toBe(false)
    expect(flow.previewVisible.value).toBe(false)
  })


  it('教师视图点绿格打开预览、点灰格无效，班级视图不接管点击', async () => {
    const flow = useTeacherSwap({
      adjust: fakeAdjust(),
      getLessons: () => lessons,
      getBaseLessons: () => [source, foreign],
      getViewMode: () => 'teacher',
      isEditable: () => true,
      getTargetId: () => 10,
      notify: fakeNotify(),
    })

    expect(flow.handleCellClick(1, source.periodNo!)).toBe(true)
    expect(flow.active.value).toBe(true)
    expect(flow.previewVisible.value).toBe(false)
    await Promise.resolve()

    expect(flow.handleCellClick(1, green.periodNo!)).toBe(true)
    expect(flow.previewVisible.value).toBe(true)

    flow.closePreview()
    expect(flow.handleCellClick(1, blocked.periodNo!)).toBe(true)
    expect(flow.previewVisible.value).toBe(false)

    const classFlow = useTeacherSwap({
      adjust: fakeAdjust(),
      getLessons: () => lessons,
      getViewMode: () => 'class',
      isEditable: () => true,
      notify: fakeNotify(),
    })
    expect(classFlow.handleCellClick(1, green.periodNo!)).toBe(false)
    expect(classFlow.active.value).toBe(false)
  })

  it('教师视图换课与班级视图换课对同一对课节发出相同参数', async () => {
    const adjust = fakeAdjust()
    const notify = fakeNotify()

    const classFlow = useSwapFlow({ adjust, notify, getVersion: () => 9 })
    classFlow.selectLesson(source)
    classFlow.openPanel(source)
    classFlow.selectCandidate(greenCandidate)
    await classFlow.confirm()

    const teacherFlow = useTeacherSwap({
      adjust,
      getLessons: () => lessons,
      getVersion: () => 9,
      notify,
    })
    await teacherFlow.enter(source)
    teacherFlow.openPreview(greenCandidate)
    await teacherFlow.confirm()

    const swap = adjust.swap as unknown as { mock: { calls: unknown[][] } }
    expect(swap.mock.calls).toHaveLength(2)
    expect(swap.mock.calls[0]).toEqual(swap.mock.calls[1])
  })
})
